package com.fooddelivery.payments.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.entity.IdempotencyKey;
import com.fooddelivery.common.repository.IIdempotencyKeyRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.retry.annotation.Backoff;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.fooddelivery.common.enums.PaymentGateway;

import java.util.UUID;

@Service
@lombok.extern.slf4j.Slf4j
public class PaymentEventConsumer {

    private final ObjectMapper objectMapper;
    private final com.fooddelivery.common.event.EventBinder eventBinder;
    private final PaymentGatewayOrchestrator orchestrator;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final WebhookProcessingService webhookProcessingService;
    private final IIdempotencyKeyRepository idempotencyKeyRepository;

    public PaymentEventConsumer(ObjectMapper objectMapper, com.fooddelivery.common.event.EventBinder eventBinder, PaymentGatewayOrchestrator orchestrator, io.micrometer.core.instrument.MeterRegistry meterRegistry, WebhookProcessingService webhookProcessingService, IIdempotencyKeyRepository idempotencyKeyRepository) {
        this.objectMapper = objectMapper;
        this.eventBinder = eventBinder;
        this.orchestrator = orchestrator;
        this.meterRegistry = meterRegistry;
        this.webhookProcessingService = webhookProcessingService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 10000), exclude = {com.fooddelivery.common.event.EventBindingException.class}, traversingCauses = "true")
    @KafkaListener(topics = com.fooddelivery.common.constants.KafkaConstants.TOPIC_PAYMENT_EVENTS, groupId = com.fooddelivery.common.constants.KafkaConstants.GROUP_PAYMENT_SERVICE + "-paymenteventconsumer")
    public void consumeOrderEvents(String payload, @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {
        try {
            String extractedEventId = com.fooddelivery.common.util.KafkaHeaderUtils.extractHeaderValue(headers, "eventId");
            final String resolvedEventId;
            if (extractedEventId == null) {
                resolvedEventId = UUID.nameUUIDFromBytes(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            } else {
                resolvedEventId = extractedEventId;
            }

            String idempotencyKeyStr = "processed_event:" + resolvedEventId;
            String receivedEventType = com.fooddelivery.common.util.KafkaHeaderUtils.extractHeaderValue(headers, "eventType");
            log.info("PAYMENT_COMMAND_EVENT_RECEIVED eventId={} eventType={} payloadBytes={}",
                    resolvedEventId, receivedEventType, payload == null ? 0 : payload.length());

            if (idempotencyKeyRepository.existsById(idempotencyKeyStr)) {
                log.info("Duplicate event ignored: {}", idempotencyKeyStr);
                return;
            }

            boolean handled = false;
            try {
                String eventType = com.fooddelivery.common.util.EventPayloadUtils.resolveEventType(objectMapper.readTree(payload), headers);
                java.util.Optional<com.fooddelivery.common.event.PaymentRefundRequestedEvent> eventOpt = eventBinder.bindIf(com.fooddelivery.common.constants.EventType.PAYMENT_REFUND_REQUESTED, eventType, payload, com.fooddelivery.common.event.PaymentRefundRequestedEvent.class);
                if (eventOpt.isPresent()) {
                    handled = true;
                    log.info("Received PAYMENT_REFUND_REQUESTED event");
                    com.fooddelivery.common.event.PaymentRefundRequestedEvent event = eventOpt.get();
                    
                    String gatewayOrderId = event.getGatewayOrderId();
                    java.math.BigDecimal amount = event.getAmount();
                    String gatewayNameStr = event.getGatewayName().name();
                    String refundIdStr = event.getRefundId();
                    
                    if (gatewayOrderId != null && amount != null && amount.compareTo(java.math.BigDecimal.ZERO) > 0 && gatewayNameStr != null && refundIdStr != null) {
                        try {
                            PaymentGateway gateway = PaymentGateway.valueOf(gatewayNameStr.toUpperCase());
                            
                            // Enforce idempotency on refundId
                            String refundIdempotencyKey = "refund_req:" + refundIdStr;
                            if (idempotencyKeyRepository.existsById(refundIdempotencyKey)) {
                                log.info("Duplicate refund request ignored: {}", refundIdStr);
                                return; // Handled already
                            }
                            
                            log.info("Initiating refund for gatewayOrderId: {}, gateway: {}, refundId: {}", gatewayOrderId, gateway, refundIdStr);
                            meterRegistry.counter("refunds.requested", "gateway", gatewayNameStr).increment();
                            boolean success = orchestrator.initiateRefund(gateway, gatewayOrderId, refundIdStr, amount, "Order cancelled or rejected");
                            if (success) {
                                log.info("Refund initiated successfully for gatewayOrderId: {}", gatewayOrderId);
                                try {
                                    idempotencyKeyRepository.save(new IdempotencyKey(refundIdempotencyKey));
                                } catch (Exception e) {
                                    log.warn("Failed to save refund idempotency key {}, but refund was initiated", refundIdempotencyKey, e);
                                }
                            } else {
                                meterRegistry.counter("refunds.failed", "gateway", gatewayNameStr).increment();
                                log.error("Failed to initiate refund for gatewayOrderId: {}", gatewayOrderId);
                                
                                // Emit PAYMENT_REFUND_FAILED event via WebhookProcessingService (since we deleted processWalletRefund, we can reuse handleRefundFailure or write an outbox event)
                                webhookProcessingService.handleRefundFailure(gatewayOrderId, refundIdStr, "Gateway rejected refund initiation");
                            }
                        } catch (IllegalArgumentException e) {
                            log.error("Invalid gateway name in refund requested event: {}", gatewayNameStr);
                        }
                    } else {
                        log.error("PAYMENT_REFUND_REQUEST_INVALID eventId={} reason=missing_required_fields",
                                resolvedEventId);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to process payment event", e);
            }

            // Mark as processed (At-Least-Once Pattern)
            // Claimed only when this consumer actually did something. It listens on payment-events
            // alongside the four PAYMENT aggregates its own WebhookProcessingService publishes, and
            // keying every message it ignores grew the table at the rate of all payment traffic.
            // An event that performs no work has nothing to be idempotent about.
            if (handled) {
                try {
                    if (!idempotencyKeyRepository.existsById(idempotencyKeyStr)) {
                        idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKeyStr));
                    }
                } catch (Exception e) {
                    log.warn("Failed to save idempotency key {}, but external action was completed", idempotencyKeyStr, e);
                }
            }

        } catch (RuntimeException e) {
            log.error("Transient error processing order event in PaymentGatewayIntegration, triggering retry", e);
            throw e; // Let Kafka retry mechanism handle it (or DLQ if exhausted)
        } catch (Exception e) {
            log.error("Unrecoverable error processing order event in PaymentGatewayIntegration", e);
        }
    }

    @DltHandler
    public void processDeadLetterTopic(@Payload(required = false) String payload, @org.springframework.messaging.handler.annotation.Header(name = org.springframework.kafka.support.KafkaHeaders.EXCEPTION_MESSAGE, required = false) String exceptionMessage,
                                       @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {
        log.error("PAYMENT_EVENT_DLT payloadBytes={} exception={} replay={}",
                payload == null ? 0 : payload.length(), exceptionMessage, com.fooddelivery.common.util.KafkaHeaderUtils.deadLetterPosition(headers));
    }
}
