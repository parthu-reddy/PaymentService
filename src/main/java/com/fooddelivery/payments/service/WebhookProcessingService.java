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

    public WebhookProcessingService(
            IWebhookDeliveryRepository webhookDeliveryRepository,
            IPaymentIntentRepository paymentIntentRepository,
            IOrderRepository orderRepository,
            ObjectMapper objectMapper) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public boolean isEventProcessed(String eventId) {
        return webhookDeliveryRepository.findByEventId(eventId).isPresent();
    }

    @Async
    @Transactional
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
            delivery.setPayload(maskSensitiveData(rawBody));
            delivery.setProcessingStatus(DeliveryStatus.PENDING);
            webhookDeliveryRepository.save(delivery);

            if ("VYAPAR".equalsIgnoreCase(gatewayName)) {
                handleVyaparEvent(eventType, rootNode);
            }
            
            delivery.setProcessingStatus(DeliveryStatus.COMPLETED);
            webhookDeliveryRepository.save(delivery);
        } catch (Exception e) {
            logger.error("Failed to process webhook event: {}", eventId, e);
            delivery.setProcessingStatus(DeliveryStatus.FAILED);
            delivery.setErrorLog(e.getMessage());
            webhookDeliveryRepository.save(delivery);
        }
    }

    private void handleVyaparEvent(String eventType, JsonNode rootNode) {
        if ("payment.success".equals(eventType) || "refund.success".equals(eventType)) {
            String gatewayOrderId = rootNode.path("payload").path("payment").path("entity").path("order_id").asText();
            if (gatewayOrderId == null || gatewayOrderId.isEmpty()) {
                // Fallback structure check
                gatewayOrderId = rootNode.path("order_id").asText();
            }
            
            Optional<PaymentIntent> intentOpt = paymentIntentRepository.findByGatewayOrderId(gatewayOrderId);
            if (intentOpt.isEmpty()) {
                throw new RuntimeException("PaymentIntent not found for gatewayOrderId: " + gatewayOrderId);
            }
            
            PaymentIntent intent = intentOpt.get();
            Order order = intent.getOrder();

            if ("payment.success".equals(eventType)) {
                intent.setStatus(IntentStatus.SUCCESS);
                order.setStatus(OrderStatus.PAID);
            } else if ("refund.success".equals(eventType)) {
                BigDecimal refundAmount = new BigDecimal(rootNode.path("amount_refunded").asText("0"));
                // Protect against missing fields by grabbing amount from another location if needed,
                // but vyapar docs specify it should be present.
                if (refundAmount.compareTo(BigDecimal.ZERO) == 0 && rootNode.has("amount")) {
                     refundAmount = new BigDecimal(rootNode.path("amount").asText("0"));
                }
                
                intent.setAmountRefunded(intent.getAmountRefunded().add(refundAmount));
                
                if (intent.getAmountRefunded().compareTo(intent.getAmount()) >= 0) {
                    intent.setStatus(IntentStatus.REFUNDED);
                    order.setStatus(OrderStatus.CANCELLED_AND_REFUNDED);
                } else {
                    intent.setStatus(IntentStatus.PARTIALLY_REFUNDED);
                    order.setStatus(OrderStatus.PARTIALLY_REFUNDED);
                }
            }

            paymentIntentRepository.save(intent);
            orderRepository.save(order);
        }
    }

    private String maskSensitiveData(String rawBody) {
        try {
            JsonNode rootNode = objectMapper.readTree(rawBody);
            maskNode(rootNode);
            return objectMapper.writeValueAsString(rootNode);
        } catch (Exception e) {
            logger.warn("Failed to mask sensitive data, returning redacted payload", e);
            return "{\"error\": \"payload redacted due to masking failure\"}";
        }
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
