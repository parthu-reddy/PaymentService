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
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentGatewayOrchestrator orchestrator;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final WebhookProcessingService webhookProcessingService;
    private final IIdempotencyKeyRepository idempotencyKeyRepository;

    public OrderEventConsumer(ObjectMapper objectMapper, PaymentGatewayOrchestrator orchestrator, io.micrometer.core.instrument.MeterRegistry meterRegistry, WebhookProcessingService webhookProcessingService, IIdempotencyKeyRepository idempotencyKeyRepository) {
        this.objectMapper = objectMapper;
        this.orchestrator = orchestrator;
        this.meterRegistry = meterRegistry;
        this.webhookProcessingService = webhookProcessingService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 10000)
    )
    @KafkaListener(topics = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, groupId = com.fooddelivery.common.constants.KafkaConstants.GROUP_PAYMENT_SERVICE)
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

            if (idempotencyKeyRepository.existsById(idempotencyKeyStr)) {
                log.info("Duplicate event ignored: {}", idempotencyKeyStr);
                return;
            }

            try {
                JsonNode rootNode = objectMapper.readTree(payload);
                String eventType = rootNode.path("eventType").asText();
                if (com.fooddelivery.common.constants.EventType.PAYMENT_REFUND_REQUESTED.name().equals(eventType)) {
                    log.info("Received PAYMENT_REFUND_REQUESTED event");
                    JsonNode payloadNode = rootNode;
                    
                    String gatewayOrderId = payloadNode.path("gatewayOrderId").asText(null);
                    double amountInInr = payloadNode.path("amountInInr").asDouble(0);
                    String gatewayNameStr = payloadNode.path("gatewayName").asText(null);
                    String refundDestStr = payloadNode.path("refundDestination").asText("GATEWAY");
                    
                    if (gatewayOrderId != null && amountInInr > 0 && gatewayNameStr != null) {
                        try {
                            PaymentGateway gateway = PaymentGateway.valueOf(gatewayNameStr.toUpperCase());
                            
                            if ("WALLET".equals(refundDestStr)) {
                                log.info("Wallet refund requested. Bypassing gateway for order: {}", gatewayOrderId);
                                webhookProcessingService.processWalletRefund(gatewayOrderId, amountInInr, gatewayNameStr);
                            } else {
                                log.info("Initiating refund for gatewayOrderId: {}, gateway: {}", gatewayOrderId, gateway);
                                meterRegistry.counter("refunds.requested", "gateway", gatewayNameStr).increment();
                                boolean success = orchestrator.initiateRefund(gateway, gatewayOrderId, amountInInr, "Order cancelled or rejected");
                                if (success) {
                                    log.info("Refund initiated successfully for gatewayOrderId: {}", gatewayOrderId);
                                } else {
                                    meterRegistry.counter("refunds.failed", "gateway", gatewayNameStr).increment();
                                    log.error("Failed to initiate refund for gatewayOrderId: {}", gatewayOrderId);
                                    throw new RuntimeException("Refund failed for gatewayOrderId: " + gatewayOrderId);
                                }
                            }
                        } catch (IllegalArgumentException e) {
                            log.error("Invalid gateway name in refund requested event: {}", gatewayNameStr);
                        }
                    } else {
                        log.error("Missing required fields in PAYMENT_REFUND_REQUESTED event payload: {}", payload);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to process payment event", e);
            }

            // Mark as processed (At-Least-Once Pattern)
            try {
                if (!idempotencyKeyRepository.existsById(idempotencyKeyStr)) {
                    idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKeyStr));
                }
            } catch (Exception e) {
                log.warn("Failed to save idempotency key {}, but external action was completed", idempotencyKeyStr, e);
            }

        } catch (RuntimeException e) {
            log.error("Transient error processing order event in PaymentGatewayIntegration, triggering retry", e);
            throw e; // Let Kafka retry mechanism handle it (or DLQ if exhausted)
        } catch (Exception e) {
            log.error("Unrecoverable error processing order event in PaymentGatewayIntegration", e);
        }
    }

    @DltHandler
    public void processDeadLetterTopic(@Payload(required = false) String payload, @org.springframework.messaging.handler.annotation.Header(name = org.springframework.kafka.support.KafkaHeaders.EXCEPTION_MESSAGE, required = false) String exceptionMessage) {
        log.error("Terminal failure for event in PaymentGateway. Payload: {}. Moving to manual intervention queue. Exception: {}", payload, exceptionMessage);
    }
}
