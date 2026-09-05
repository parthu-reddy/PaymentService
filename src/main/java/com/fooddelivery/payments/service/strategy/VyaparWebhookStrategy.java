package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class VyaparWebhookStrategy implements WebhookHandlerStrategy {

    private static final String EVENT_PAYMENT_SUCCESS = "payment.success";
    private static final String EVENT_PAYMENT_FAILED = "payment.failed";
    private static final String EVENT_REFUND_SUCCESS = "refund.success";
    private static final String EVENT_REFUND_FAILED = "refund.failed";

    @Override
    public com.fooddelivery.common.enums.PaymentGateway getSupportedGateway() {
        return com.fooddelivery.common.enums.PaymentGateway.VYAPAR;
    }

    @Override
    public void handleEvent(String eventType, JsonNode rootNode, PaymentActionDelegate delegate) {
        String gatewayOrderId = rootNode.path("payload").path("payment").path("entity").path("order_id").asText();
        if (gatewayOrderId == null || gatewayOrderId.isEmpty()) {
            gatewayOrderId = rootNode.path("order_id").asText();
        }
        
        if (EVENT_PAYMENT_SUCCESS.equals(eventType)) {
            java.math.BigDecimal paidAmount = rootNode.path("payload").path("payment").path("entity").path("amount").decimalValue();
            if (paidAmount == null) {
                paidAmount = rootNode.path("amount").decimalValue();
            }
            delegate.handleSuccessfulPayment(gatewayOrderId, paidAmount);
        } else if (EVENT_PAYMENT_FAILED.equals(eventType)) {
            delegate.handleFailedPayment(gatewayOrderId, "Vyapar payment failed");
        } else if (EVENT_REFUND_SUCCESS.equals(eventType)) {
            String refundId = rootNode.path("payload").path("refund").path("entity").path("id").asText();
            java.math.BigDecimal amount = rootNode.path("payload").path("refund").path("entity").path("amount").decimalValue();
            
            com.fasterxml.jackson.databind.node.ObjectNode syntheticPayload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            syntheticPayload.put("amount_refunded", amount);
            
            delegate.handleRefundSuccess(gatewayOrderId, refundId, syntheticPayload);
        } else if (EVENT_REFUND_FAILED.equals(eventType)) {
            String refundId = rootNode.path("payload").path("refund").path("entity").path("id").asText();
            String reason = rootNode.path("payload").path("refund").path("entity").path("error_message").asText("Refund failed at Vyapar");
            delegate.handleRefundFailure(gatewayOrderId, refundId, reason);
        }
    }
}
