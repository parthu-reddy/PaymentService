package com.fooddelivery.payments.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.repository.ITransactionRepository;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import com.fooddelivery.payments.repository.IRefundRepository;
import com.fooddelivery.payments.model.Refund;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.transaction.support.TransactionTemplate;
import com.fooddelivery.common.event.PaymentSucceededEvent;

import java.math.BigDecimal;
import java.util.Optional;
import com.fooddelivery.common.constants.PaymentIntentStatus;
import com.fooddelivery.payments.model.enums.DeliveryStatus;

import com.fooddelivery.common.enums.PaymentGateway;
import com.fooddelivery.payments.service.strategy.PaymentActionDelegate;
import com.fooddelivery.payments.service.strategy.WebhookHandlerStrategy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@lombok.extern.slf4j.Slf4j
public class WebhookProcessingService implements PaymentActionDelegate {

private static final String LOCK_PREFIX_WEBHOOK = "webhook:payment:";
    private static final String LOCK_VALUE = "locked";
    private static final String DEFAULT_EVENT_TYPE = "UNKNOWN";
    private static final String EMPTY_JSON_PAYLOAD = "{}";
    private static final String FIELD_EVENT = "event";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_AMOUNT_REFUNDED = "amount_refunded";
    private static final String FIELD_AMOUNT = "amount";
    
    private final IWebhookDeliveryRepository webhookDeliveryRepository;
    private final IPaymentIntentRepository paymentIntentRepository;
    private final ITransactionRepository transactionRepository;
    private final IRefundRepository refundRepository;
    private final ObjectMapper objectMapper;
    private final com.fooddelivery.common.outbox.repository.OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final StringRedisTemplate redisTemplate;

    private final Map<String, WebhookHandlerStrategy> strategyMap;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public WebhookProcessingService(
            IWebhookDeliveryRepository webhookDeliveryRepository,
            IPaymentIntentRepository paymentIntentRepository,
            ITransactionRepository transactionRepository,
            IRefundRepository refundRepository,
            ObjectMapper objectMapper,
            com.fooddelivery.common.outbox.repository.OutboxEventRepository outboxEventRepository,
            TransactionTemplate transactionTemplate,
            StringRedisTemplate redisTemplate,
            List<WebhookHandlerStrategy> strategies,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.transactionRepository = transactionRepository;
        this.refundRepository = refundRepository;
        this.objectMapper = objectMapper;
        this.outboxEventRepository = outboxEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.redisTemplate = redisTemplate;
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(s -> s.getSupportedGateway().name(), Function.identity()));
        this.meterRegistry = meterRegistry;
    }

    @Transactional(readOnly = true)
    public boolean isEventProcessed(String eventId) {
        return webhookDeliveryRepository.findByEventId(eventId).isPresent();
    }

    @Async
    @Retryable(
      retryFor = { org.springframework.dao.CannotAcquireLockException.class, org.springframework.dao.DeadlockLoserDataAccessException.class },
      maxAttempts = 3,
      backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public void processWebhookAsync(String eventId, PaymentGateway gatewayName, String rawBody) {
        String lockKey = LOCK_PREFIX_WEBHOOK + eventId;
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, java.time.Duration.ofMinutes(2));
        
        if (Boolean.FALSE.equals(lockAcquired)) {
            log.info("Duplicate webhook eventId {} rejected by Redis lock.", eventId);
            return;
        }

        try {
            if (isEventProcessed(eventId)) {
                return;
            }
            
            WebhookDelivery delivery = null;
            try {
                try {
                    delivery = new WebhookDelivery();
                    delivery.setEventId(eventId);
                    delivery.setGatewayName(gatewayName);
                    delivery.setProcessingStatus(DeliveryStatus.PENDING);
                    delivery.setEventType(DEFAULT_EVENT_TYPE);
                    delivery.setPayload(EMPTY_JSON_PAYLOAD); // temporary payload to satisfy not-null constraint
                    delivery = webhookDeliveryRepository.saveAndFlush(delivery);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    log.info("Duplicate webhook eventId {}. Another thread is processing it.", eventId);
                    return;
                }
                
                String eventType = DEFAULT_EVENT_TYPE;
                JsonNode rootNode = objectMapper.readTree(rawBody);
                
                if (rootNode.has(FIELD_EVENT)) {
                    eventType = rootNode.get(FIELD_EVENT).asText();
                } else if (rootNode.has(FIELD_TYPE)) {
                    eventType = rootNode.get(FIELD_TYPE).asText();
                }
    
                delivery.setEventType(eventType);
                
                // Performance optimization: Parse once, create deep copy, mask, and serialize
                JsonNode payloadCopy = rootNode.deepCopy();
                maskNode(payloadCopy);
                delivery.setPayload(objectMapper.writeValueAsString(payloadCopy));
                
                delivery.setProcessingStatus(DeliveryStatus.PENDING);
                delivery = webhookDeliveryRepository.save(delivery);
    
                processGatewayEvent(gatewayName, eventType, rootNode);
                
                delivery.setProcessingStatus(DeliveryStatus.COMPLETED);
                delivery = webhookDeliveryRepository.save(delivery);
            } catch (org.springframework.dao.CannotAcquireLockException | org.springframework.dao.DeadlockLoserDataAccessException e) {
                log.warn("Transient locking failure for webhook event: {}. Will be retried.", eventId);
                throw e;
            } catch (Exception e) {
                log.error("Failed to process webhook event: {}", eventId, e);
                if (delivery != null && delivery.getId() != null) {
                    try {
                        delivery.setProcessingStatus(DeliveryStatus.FAILED);
                        delivery.setErrorLog(e.getMessage());
                        // Fetch the latest version from DB to avoid optimistic locking failure when saving failure status
                        webhookDeliveryRepository.findById(delivery.getId()).ifPresent(latestDelivery -> {
                            latestDelivery.setProcessingStatus(DeliveryStatus.FAILED);
                            latestDelivery.setErrorLog(e.getMessage());
                            webhookDeliveryRepository.save(latestDelivery);
                        });
                    } catch (Exception dbEx) {
                        log.error("Failed to save FAILED status for webhook event: {}", eventId, dbEx);
                    }
                }
            }
        } finally {
            // Only release the lock if we are the ones who acquired it
            String currentValue = redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(currentValue)) {
                redisTemplate.delete(lockKey);
            }
        }
    }

    public void retryWebhook(WebhookDelivery delivery) {
        try {
            JsonNode rootNode = objectMapper.readTree(delivery.getPayload());
            processGatewayEvent(delivery.getGatewayName(), delivery.getEventType(), rootNode);
            delivery.setProcessingStatus(DeliveryStatus.COMPLETED);
            webhookDeliveryRepository.save(delivery);
        } catch (Exception e) {
            log.error("Failed to retry webhook event: {}", delivery.getEventId(), e);
            delivery.setErrorLog("DLQ Retry Failed: " + e.getMessage());
            webhookDeliveryRepository.save(delivery);
            throw new RuntimeException(e);
        }
    }

    private void processGatewayEvent(PaymentGateway gatewayName, String eventType, JsonNode rootNode) {
        String normalizedGatewayName = gatewayName.name().toUpperCase();
        WebhookHandlerStrategy strategy = strategyMap.get(normalizedGatewayName);
        if (strategy != null) {
            strategy.handleEvent(eventType, rootNode, this);
        } else {
            log.warn("Received webhook for unknown gateway: {}", gatewayName);
        }
    }

    @Override
    public void handleSuccessfulPayment(String gatewayOrderId, java.math.BigDecimal paidAmount) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<PaymentIntent> intentOpt = paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId);
            if (intentOpt.isEmpty()) {
                throw new RuntimeException("PaymentIntent not found for gatewayOrderId: " + gatewayOrderId);
            }
            
            PaymentIntent intent = intentOpt.get();
            if (intent.getStatus() == PaymentIntentStatus.SUCCESS) {
                log.info("PaymentIntent {} is already marked as SUCCESS.", intent.getId());
                return;
            }

            if (paidAmount != null && intent.getAmount().compareTo(paidAmount) != 0) {
                log.warn("Payment amount mismatch for order {}: expected {}, received {}", intent.getOrderId(), intent.getAmount(), paidAmount);
                meterRegistry.counter("payment.amount.mismatch", "gateway", intent.getGatewayName().name()).increment();
                handleFailedPayment(gatewayOrderId, "AMOUNT_MISMATCH: expected " + intent.getAmount() + " but received " + paidAmount);
                return;
            }

            intent.setStatus(PaymentIntentStatus.SUCCESS);
                    
            PaymentSucceededEvent event = new PaymentSucceededEvent(
                    intent.getOrderId(),
                    intent.getGatewayOrderId(),
                    intent.getAmount(),
                    intent.getGatewayName().name(),
                    null,
                    java.time.Instant.now()
            );

            paymentIntentRepository.save(intent);
            try {
                com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.valueToTree(event);
                
                boolean isWalletTopup = intent.getOrderId().startsWith("WALLET_");
                com.fooddelivery.common.constants.EventType eventType = isWalletTopup ? 
                    com.fooddelivery.common.constants.EventType.AD_WALLET_TOPUP_COMPLETED : 
                    com.fooddelivery.common.constants.EventType.PAYMENT_COMPLETED;
                
                payloadNode.put("eventType", eventType.name());
                
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outbox = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AggregateType.PAYMENT)
                        .aggregateId(intent.getOrderId())
                        .eventType(eventType)
                        .payload(objectMapper.writeValueAsString(payloadNode))
                        .createdAt(java.time.LocalDateTime.now())
                        .status(com.fooddelivery.common.enums.OutboxStatus.UNPROCESSED)
                        .build();
                outboxEventRepository.save(outbox);
            } catch (Exception e) {
                throw new RuntimeException("Failed to save PaymentCompletedEvent to outbox", e);
            }
        });
    }

    @Override
    public void handleFailedPayment(String gatewayOrderId, String failureReason) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<PaymentIntent> intentOpt = paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId);
            if (intentOpt.isEmpty()) {
                throw new RuntimeException("PaymentIntent not found for gatewayOrderId: " + gatewayOrderId);
            }
            
            PaymentIntent intent = intentOpt.get();
            if (intent.getStatus() == PaymentIntentStatus.FAILED || intent.getStatus() == PaymentIntentStatus.SUCCESS) {
                log.info("PaymentIntent {} is already in terminal state {}.", intent.getId(), intent.getStatus());
                return;
            }

            intent.setStatus(PaymentIntentStatus.FAILED);
                    
            com.fooddelivery.common.event.PaymentFailedEvent event = new com.fooddelivery.common.event.PaymentFailedEvent(
                    java.util.UUID.fromString(intent.getOrderId()),
                    intent.getGatewayOrderId(),
                    intent.getGatewayName().name(),
                    failureReason
            );

            paymentIntentRepository.save(intent);
            try {
                com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.valueToTree(event);
                payloadNode.put("eventType", com.fooddelivery.common.constants.EventType.PAYMENT_FAILED.name());
                
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outbox = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AggregateType.PAYMENT)
                        .aggregateId(intent.getOrderId())
                        .eventType(com.fooddelivery.common.constants.EventType.PAYMENT_FAILED)
                        .payload(objectMapper.writeValueAsString(payloadNode))
                        .createdAt(java.time.LocalDateTime.now())
                        .status(com.fooddelivery.common.enums.OutboxStatus.UNPROCESSED)
                        .build();
                outboxEventRepository.save(outbox);
            } catch (Exception e) {
                throw new RuntimeException("Failed to save PaymentFailedEvent to outbox", e);
            }
        });
    }

    @Override
    public void handleRefundSuccess(String gatewayOrderId, String gatewayRefundId, JsonNode rootNode) {
        if (gatewayRefundId == null || gatewayRefundId.trim().isEmpty()) {
            throw new IllegalArgumentException("gatewayRefundId must be provided for idempotency tracking");
        }
        
        transactionTemplate.executeWithoutResult(status -> {
            Optional<PaymentIntent> intentOpt = paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId);
            if (intentOpt.isEmpty()) {
                throw new IllegalStateException("PaymentIntent not found for gatewayOrderId: " + gatewayOrderId);
            }
            
            if (refundRepository.findByGatewayRefundId(gatewayRefundId).isPresent()) {
                log.info("Refund ID {} has already been processed. Skipping to guarantee idempotency.", gatewayRefundId);
                return;
            }
            
            PaymentIntent intent = intentOpt.get();
            
            JsonNode amountNode = rootNode.path(FIELD_AMOUNT_REFUNDED);
            if (amountNode.isMissingNode() || amountNode.isNull() || amountNode.asText().isEmpty()) {
                 amountNode = rootNode.path(FIELD_AMOUNT);
            }
            if (amountNode.isMissingNode() || amountNode.isNull() || amountNode.asText().isEmpty()) {
                 throw new IllegalArgumentException("Refund amount must be present in webhook payload. Cannot use fallback values.");
            }
            
            BigDecimal finalRefundAmount = new BigDecimal(amountNode.asText());
            if (finalRefundAmount.compareTo(BigDecimal.ZERO) <= 0) {
                 throw new IllegalArgumentException("Refund amount must be strictly positive");
            }
            
            if (intent.getAmountRefunded() == null) {
                throw new IllegalStateException("PaymentIntent amountRefunded cannot be null for intent: " + intent.getId());
            }
            BigDecimal currentRefund = intent.getAmountRefunded();
            BigDecimal newTotalRefund = currentRefund.add(finalRefundAmount);
            if (newTotalRefund.compareTo(intent.getAmount()) > 0) {
                log.error("CRITICAL: Incoming webhook refund amount {} would exceed original intent amount {}. Capping it.", finalRefundAmount, intent.getAmount());
                finalRefundAmount = intent.getAmount().subtract(currentRefund);
                newTotalRefund = intent.getAmount();
            }
            intent.setAmountRefunded(newTotalRefund);
            
            if (intent.getAmountRefunded().compareTo(intent.getAmount()) >= 0) {
                intent.setStatus(PaymentIntentStatus.REFUNDED);
            } else {
                intent.setStatus(PaymentIntentStatus.PARTIALLY_REFUNDED);
            }

            // Also track on the transaction if one exists
            Optional<com.fooddelivery.payments.model.Transaction> txOpt = transactionRepository.findLockedFirstByPaymentIntentIdAndStatusOrderByCreatedAtDesc(intent.getId(), com.fooddelivery.common.enums.TransactionStatus.SUCCESS);
            if (txOpt.isEmpty()) {
                throw new IllegalStateException("Cannot process refund: No successful transaction found for PaymentIntent " + intent.getId());
            }

            paymentIntentRepository.save(intent);
            com.fooddelivery.payments.model.Transaction tx = txOpt.get();
            if (tx.getAmountRefunded() == null) {
                throw new IllegalStateException("Transaction amountRefunded cannot be null for tx: " + tx.getId());
            }
            BigDecimal txCurrentRefund = tx.getAmountRefunded();
            tx.setAmountRefunded(txCurrentRefund.add(finalRefundAmount));
            transactionRepository.save(tx);
            
            Refund refundRecord = new Refund();
            refundRecord.setTransaction(tx);
            refundRecord.setGatewayRefundId(gatewayRefundId);
            refundRecord.setAmount(finalRefundAmount);
            refundRecord.setReason("Webhook automated refund capture");
            refundRecord.setStatus(com.fooddelivery.common.enums.RefundStatus.COMPLETED);
            refundRecord.setRefundDestination(com.fooddelivery.common.enums.RefundDestination.ORIGINAL_METHOD);
            refundRepository.save(refundRecord);

            meterRegistry.counter("refunds.success", "gateway", intent.getGatewayName().name()).increment();
            
            try {
                com.fooddelivery.common.event.PaymentRefundedEvent refundEvent = com.fooddelivery.common.event.PaymentRefundedEvent.builder()
                        .orderId(intent.getOrderId().toString())
                        .gatewayOrderId(intent.getGatewayOrderId())
                        .amountRefunded(finalRefundAmount)
                        .gatewayName(intent.getGatewayName())
                        .refundDestination(com.fooddelivery.common.enums.RefundDestination.ORIGINAL_METHOD)
                        .refundId(gatewayRefundId)
                        .gatewayRefundId(gatewayRefundId)
                        .status("COMPLETED")
                        .build();
                
                com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.valueToTree(refundEvent);
                com.fooddelivery.common.constants.EventType outboxEventType = (intent.getStatus() == PaymentIntentStatus.PARTIALLY_REFUNDED) ? 
                    com.fooddelivery.common.constants.EventType.PAYMENT_PARTIALLY_REFUNDED : 
                    com.fooddelivery.common.constants.EventType.PAYMENT_REFUNDED;
                payloadNode.put("eventType", outboxEventType.name());
                
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outbox = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AggregateType.PAYMENT)
                        .aggregateId(intent.getOrderId().toString())
                        .eventType(outboxEventType)
                        .payload(objectMapper.writeValueAsString(payloadNode))
                        .createdAt(java.time.LocalDateTime.now())
                        .status(com.fooddelivery.common.enums.OutboxStatus.UNPROCESSED)
                        .build();
                outboxEventRepository.save(outbox);
            } catch (Exception e) {
                log.error("Failed to serialize or save PaymentRefundedEvent for gatewayOrderId: " + gatewayOrderId, e);
                throw new RuntimeException("Failed to save PaymentRefundedEvent to Outbox", e);
            }
        });
    }

    @Override
    public void handleRefundFailure(String gatewayOrderId, String gatewayRefundId, String failureReason) {
        if (gatewayRefundId == null || gatewayRefundId.trim().isEmpty()) {
            throw new IllegalArgumentException("gatewayRefundId must be provided for idempotency tracking");
        }
        
        transactionTemplate.executeWithoutResult(status -> {
            Optional<PaymentIntent> intentOpt = paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId);
            if (intentOpt.isEmpty()) {
                throw new IllegalStateException("PaymentIntent not found for gatewayOrderId: " + gatewayOrderId);
            }
            
            PaymentIntent intent = intentOpt.get();
            meterRegistry.counter("refunds.failure", "gateway", intent.getGatewayName().name()).increment();
            
            try {
                com.fooddelivery.common.event.PaymentRefundedEvent refundEvent = com.fooddelivery.common.event.PaymentRefundedEvent.builder()
                        .orderId(intent.getOrderId().toString())
                        .gatewayOrderId(intent.getGatewayOrderId())
                        .amountRefunded(BigDecimal.ZERO)
                        .gatewayName(intent.getGatewayName())
                        .refundDestination(com.fooddelivery.common.enums.RefundDestination.ORIGINAL_METHOD)
                        .refundId(gatewayRefundId)
                        .gatewayRefundId(gatewayRefundId)
                        .status("FAILED")
                        .failureReason(failureReason)
                        .build();
                
                com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.valueToTree(refundEvent);
                payloadNode.put("eventType", com.fooddelivery.common.constants.EventType.PAYMENT_REFUNDED.name());
                
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outbox = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AggregateType.PAYMENT)
                        .aggregateId(intent.getOrderId().toString())
                        .eventType(com.fooddelivery.common.constants.EventType.PAYMENT_REFUNDED)
                        .payload(objectMapper.writeValueAsString(payloadNode))
                        .createdAt(java.time.LocalDateTime.now())
                        .status(com.fooddelivery.common.enums.OutboxStatus.UNPROCESSED)
                        .build();
                outboxEventRepository.save(outbox);
            } catch (Exception e) {
                log.error("Failed to serialize or save PaymentRefundedEvent for failed refund: " + gatewayOrderId, e);
                throw new RuntimeException("Failed to save PaymentRefundedEvent to Outbox", e);
            }
        });
    }


    private void maskNode(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode objNode = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = objNode.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase();
                if (key.contains("phone") || key.contains("mobile") || key.contains("email")) {
                    if (field.getValue().isTextual()) {
                        String val = field.getValue().asText();
                        if (val.length() > 4) {
                            objNode.put(field.getKey(), "****" + val.substring(val.length() - 4));
                        } else {
                            objNode.put(field.getKey(), "****");
                        }
                    } else if (field.getValue().isObject() || field.getValue().isArray()) {
                        maskNode(field.getValue());
                    }
                } else {
                    maskNode(field.getValue());
                }
            }
        } else if (node.isArray()) {
            for (JsonNode arrayItem : node) {
                maskNode(arrayItem);
            }
        }
    }
}
