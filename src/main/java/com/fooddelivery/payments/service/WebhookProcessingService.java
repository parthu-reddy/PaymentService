package com.fooddelivery.payments.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.repository.ITransactionRepository;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
public class WebhookProcessingService implements PaymentActionDelegate {

    private static final Logger logger = LoggerFactory.getLogger(WebhookProcessingService.class);
    
    private final IWebhookDeliveryRepository webhookDeliveryRepository;
    private final IPaymentIntentRepository paymentIntentRepository;
    private final ITransactionRepository transactionRepository;
    private final ObjectMapper objectMapper;
    private final com.fooddelivery.common.outbox.repository.OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate transactionTemplate;

    private final Map<String, WebhookHandlerStrategy> strategyMap;

    public WebhookProcessingService(
            IWebhookDeliveryRepository webhookDeliveryRepository,
            IPaymentIntentRepository paymentIntentRepository,
            ITransactionRepository transactionRepository,
            ObjectMapper objectMapper,
            com.fooddelivery.common.outbox.repository.OutboxEventRepository outboxEventRepository,
            TransactionTemplate transactionTemplate,
            List<WebhookHandlerStrategy> strategies) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.transactionRepository = transactionRepository;
        this.objectMapper = objectMapper;
        this.outboxEventRepository = outboxEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(s -> s.getSupportedGateway().name(), Function.identity()));
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
        if (isEventProcessed(eventId)) {
            return;
        }
        
        WebhookDelivery delivery;
        try {
            delivery = new WebhookDelivery();
            delivery.setEventId(eventId);
            delivery.setGatewayName(gatewayName);
            delivery.setProcessingStatus(DeliveryStatus.PENDING);
            delivery.setEventType("UNKNOWN");
            delivery.setPayload("{}"); // temporary payload to satisfy not-null constraint
            delivery = webhookDeliveryRepository.saveAndFlush(delivery);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            logger.info("Duplicate webhook eventId {}. Another thread is processing it.", eventId);
            return;
        }
        
        String eventType = "UNKNOWN";
        try {
            JsonNode rootNode = objectMapper.readTree(rawBody);
            
            if (rootNode.has("event")) {
                eventType = rootNode.get("event").asText();
            } else if (rootNode.has("type")) {
                eventType = rootNode.get("type").asText();
            }

            delivery.setEventType(eventType);
            
            // Performance optimization: Parse once, create deep copy, mask, and serialize
            JsonNode payloadCopy = rootNode.deepCopy();
            maskNode(payloadCopy);
            delivery.setPayload(objectMapper.writeValueAsString(payloadCopy));
            
            delivery.setProcessingStatus(DeliveryStatus.PENDING);
            webhookDeliveryRepository.save(delivery);

            processGatewayEvent(gatewayName, eventType, rootNode);
            
            delivery.setProcessingStatus(DeliveryStatus.COMPLETED);
            webhookDeliveryRepository.save(delivery);
        } catch (org.springframework.dao.CannotAcquireLockException | org.springframework.dao.DeadlockLoserDataAccessException e) {
            logger.warn("Transient locking failure for webhook event: {}. Will be retried.", eventId);
            throw e;
        } catch (Exception e) {
            logger.error("Failed to process webhook event: {}", eventId, e);
            delivery.setProcessingStatus(DeliveryStatus.FAILED);
            delivery.setErrorLog(e.getMessage());
            webhookDeliveryRepository.save(delivery);
        }
    }

    public void retryWebhook(WebhookDelivery delivery) {
        try {
            JsonNode rootNode = objectMapper.readTree(delivery.getPayload());
            processGatewayEvent(delivery.getGatewayName(), delivery.getEventType(), rootNode);
            delivery.setProcessingStatus(DeliveryStatus.COMPLETED);
            webhookDeliveryRepository.save(delivery);
        } catch (Exception e) {
            logger.error("Failed to retry webhook event: {}", delivery.getEventId(), e);
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
            logger.warn("Received webhook for unknown gateway: {}", gatewayName);
        }
    }

    @Override
    public void handleSuccessfulPayment(String gatewayOrderId) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<PaymentIntent> intentOpt = paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId);
            if (intentOpt.isEmpty()) {
                throw new RuntimeException("PaymentIntent not found for gatewayOrderId: " + gatewayOrderId);
            }
            
            PaymentIntent intent = intentOpt.get();
            if (intent.getStatus() == PaymentIntentStatus.SUCCESS) {
                logger.info("PaymentIntent {} is already marked as SUCCESS.", intent.getId());
                return;
            }

            intent.setStatus(PaymentIntentStatus.SUCCESS);
                    
            PaymentSucceededEvent event = new PaymentSucceededEvent(
                    intent.getOrderId(),
                    intent.getGatewayOrderId(),
                    intent.getAmount(),
                    intent.getGatewayName().name()
            );

            paymentIntentRepository.save(intent);
            try {
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outbox = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AppConstants.AGGREGATE_PAYMENT)
                        .aggregateId(intent.getOrderId())
                        .eventType(com.fooddelivery.common.constants.EventType.PAYMENT_COMPLETED)
                        .payload(objectMapper.writeValueAsString(event))
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
                logger.info("PaymentIntent {} is already in terminal state {}.", intent.getId(), intent.getStatus());
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
                com.fooddelivery.common.outbox.entity.OutboxEventEntity outbox = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AppConstants.AGGREGATE_PAYMENT)
                        .aggregateId(intent.getOrderId())
                        .eventType(com.fooddelivery.common.constants.EventType.PAYMENT_FAILED)
                        .payload(objectMapper.writeValueAsString(event))
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
    public void handleRefundSuccess(String gatewayOrderId, JsonNode rootNode) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<PaymentIntent> intentOpt = paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId);
            if (intentOpt.isEmpty()) {
                throw new RuntimeException("PaymentIntent not found for gatewayOrderId: " + gatewayOrderId);
            }
            
            PaymentIntent intent = intentOpt.get();
            
            BigDecimal tempRefund = new BigDecimal(rootNode.path("amount_refunded").asText("0"));
            if (tempRefund.compareTo(BigDecimal.ZERO) == 0 && rootNode.has("amount")) {
                 tempRefund = new BigDecimal(rootNode.path("amount").asText("0"));
            }
            final BigDecimal finalRefundAmount = tempRefund;
            
            BigDecimal currentRefund = intent.getAmountRefunded() != null ? intent.getAmountRefunded() : BigDecimal.ZERO;
            intent.setAmountRefunded(currentRefund.add(finalRefundAmount));
            
            if (intent.getAmountRefunded().compareTo(intent.getAmount()) >= 0) {
                intent.setStatus(PaymentIntentStatus.REFUNDED);
            } else {
                intent.setStatus(PaymentIntentStatus.PARTIALLY_REFUNDED);
            }

            // Also track on the transaction if one exists
            Optional<com.fooddelivery.payments.model.Transaction> txOpt = transactionRepository.findFirstByPaymentIntentIdAndStatusOrderByCreatedAtDesc(intent.getId(), com.fooddelivery.common.enums.TransactionStatus.SUCCESS);

            paymentIntentRepository.save(intent);
            if (txOpt.isPresent()) {
                com.fooddelivery.payments.model.Transaction tx = txOpt.get();
                BigDecimal txCurrentRefund = tx.getAmountRefunded() != null ? tx.getAmountRefunded() : BigDecimal.ZERO;
                tx.setAmountRefunded(txCurrentRefund.add(finalRefundAmount));
                transactionRepository.save(tx);
            }
            
            try {
                com.fooddelivery.common.event.PaymentRefundedEvent refundEvent = com.fooddelivery.common.event.PaymentRefundedEvent.builder()
                        .orderId(intent.getOrderId().toString())
                        .gatewayOrderId(intent.getGatewayOrderId())
                        .amountRefunded(finalRefundAmount)
                        .gatewayName(intent.getGatewayName())
                        .build();

                com.fooddelivery.common.outbox.entity.OutboxEventEntity outbox = com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(com.fooddelivery.common.constants.AppConstants.AGGREGATE_PAYMENT)
                        .aggregateId(intent.getOrderId().toString())
                        .eventType(com.fooddelivery.common.constants.EventType.PAYMENT_REFUNDED)
                        .payload(objectMapper.writeValueAsString(refundEvent))
                        .createdAt(java.time.LocalDateTime.now())
                        .status(com.fooddelivery.common.enums.OutboxStatus.UNPROCESSED)
                        .build();
                outboxEventRepository.save(outbox);
            } catch (Exception e) {
                logger.error("Failed to serialize or save PaymentRefundedEvent for gatewayOrderId: " + gatewayOrderId, e);
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
