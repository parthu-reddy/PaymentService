package com.fooddelivery.payments.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.Order;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.repository.IOrderRepository;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;
import com.fooddelivery.payments.model.PaymentSucceededEvent;

import java.math.BigDecimal;
import java.util.Optional;
import com.fooddelivery.payments.model.enums.OrderStatus;
import com.fooddelivery.payments.model.enums.IntentStatus;
import com.fooddelivery.payments.model.enums.DeliveryStatus;

@Service
public class WebhookProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookProcessingService.class);
    
    private final IWebhookDeliveryRepository webhookDeliveryRepository;
    private final IPaymentIntentRepository paymentIntentRepository;
    private final IOrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final PaymentEventPublisher eventPublisher;

    public WebhookProcessingService(
            IWebhookDeliveryRepository webhookDeliveryRepository,
            IPaymentIntentRepository paymentIntentRepository,
            IOrderRepository orderRepository,
            ObjectMapper objectMapper,
            PaymentEventPublisher eventPublisher) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public boolean isEventProcessed(String eventId) {
        return webhookDeliveryRepository.findByEventId(eventId).isPresent();
    }

    @Async
    @Transactional
    @Retryable(
      retryFor = { org.springframework.dao.CannotAcquireLockException.class, org.springframework.dao.DeadlockLoserDataAccessException.class },
      maxAttempts = 3,
      backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public void processWebhookAsync(String eventId, String gatewayName, String rawBody) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEventId(eventId);
        delivery.setGatewayName(gatewayName);
        
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
        } catch (Exception e) {
            logger.error("Failed to process webhook event: {}", eventId, e);
            delivery.setProcessingStatus(DeliveryStatus.FAILED);
            delivery.setErrorLog(e.getMessage());
            webhookDeliveryRepository.save(delivery);
        }
    }

    @Transactional
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

    private void processGatewayEvent(String gatewayName, String eventType, JsonNode rootNode) {
        if ("VYAPAR".equalsIgnoreCase(gatewayName)) {
            handleVyaparEvent(eventType, rootNode);
        } else if ("RAZORPAY".equalsIgnoreCase(gatewayName)) {
            handleRazorpayEvent(eventType, rootNode);
        } else if ("CASHFREE".equalsIgnoreCase(gatewayName)) {
            handleCashfreeEvent(eventType, rootNode);
        } else {
            logger.warn("Received webhook for unknown gateway: {}", gatewayName);
        }
    }

    private void handleVyaparEvent(String eventType, JsonNode rootNode) {
        String gatewayOrderId = rootNode.path("payload").path("payment").path("entity").path("order_id").asText();
        if (gatewayOrderId == null || gatewayOrderId.isEmpty()) {
            gatewayOrderId = rootNode.path("order_id").asText();
        }
        
        if ("payment.success".equals(eventType)) {
            handleSuccessfulPayment(gatewayOrderId);
        } else if ("refund.success".equals(eventType)) {
            handleRefundSuccess(gatewayOrderId, rootNode);
        }
    }

    private void handleRazorpayEvent(String eventType, JsonNode rootNode) {
        if ("order.paid".equals(eventType)) {
            String gatewayOrderId = rootNode.path("payload").path("payment").path("entity").path("order_id").asText();
            if (gatewayOrderId != null && !gatewayOrderId.isEmpty()) {
                handleSuccessfulPayment(gatewayOrderId);
            }
        }
    }

    private void handleCashfreeEvent(String eventType, JsonNode rootNode) {
        if ("PAYMENT_SUCCESS_WEBHOOK".equals(eventType)) {
            String gatewayOrderId = rootNode.path("data").path("order").path("order_id").asText();
            if (gatewayOrderId != null && !gatewayOrderId.isEmpty()) {
                handleSuccessfulPayment(gatewayOrderId);
            }
        }
    }

    private void handleSuccessfulPayment(String gatewayOrderId) {
        Optional<PaymentIntent> intentOpt = paymentIntentRepository.findByGatewayOrderId(gatewayOrderId);
        if (intentOpt.isEmpty()) {
            throw new RuntimeException("PaymentIntent not found for gatewayOrderId: " + gatewayOrderId);
        }
        
        PaymentIntent intent = intentOpt.get();
        if (intent.getStatus() == IntentStatus.SUCCESS) {
            logger.info("PaymentIntent {} is already marked as SUCCESS.", intent.getId());
            return;
        }

        Order order = intent.getOrder();
        intent.setStatus(IntentStatus.SUCCESS);
        order.setStatus(OrderStatus.PAID);
        
        eventPublisher.publishPaymentSuccess(new PaymentSucceededEvent(
                order.getId().toString(),
                intent.getGatewayOrderId(),
                order.getTotalAmount(),
                intent.getGatewayName()
        ));

        paymentIntentRepository.save(intent);
        orderRepository.save(order);
    }

    private void handleRefundSuccess(String gatewayOrderId, JsonNode rootNode) {
        Optional<PaymentIntent> intentOpt = paymentIntentRepository.findByGatewayOrderId(gatewayOrderId);
        if (intentOpt.isEmpty()) {
            throw new RuntimeException("PaymentIntent not found for gatewayOrderId: " + gatewayOrderId);
        }
        
        PaymentIntent intent = intentOpt.get();
        Order order = intent.getOrder();

        BigDecimal refundAmount = new BigDecimal(rootNode.path("amount_refunded").asText("0"));
        if (refundAmount.compareTo(BigDecimal.ZERO) == 0 && rootNode.has("amount")) {
             refundAmount = new BigDecimal(rootNode.path("amount").asText("0"));
        }
        BigDecimal currentRefund = intent.getAmountRefunded() != null ? intent.getAmountRefunded() : BigDecimal.ZERO;
        intent.setAmountRefunded(currentRefund.add(refundAmount));
        
        if (intent.getAmountRefunded().compareTo(intent.getAmount()) >= 0) {
            intent.setStatus(IntentStatus.REFUNDED);
            order.setStatus(OrderStatus.CANCELLED_AND_REFUNDED);
        } else {
            intent.setStatus(IntentStatus.PARTIALLY_REFUNDED);
            order.setStatus(OrderStatus.PARTIALLY_REFUNDED);
        }

        paymentIntentRepository.save(intent);
        orderRepository.save(order);
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
