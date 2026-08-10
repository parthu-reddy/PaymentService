package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class CashfreeWebhookStrategy implements WebhookHandlerStrategy {

    private static final String EVENT_PAYMENT_SUCCESS = "PAYMENT_SUCCESS_WEBHOOK";
    private static final String EVENT_PAYMENT_FAILED = "PAYMENT_FAILED_WEBHOOK";
    private static final String EVENT_REFUND_SUCCESS = "REFUND_SUCCESS_WEBHOOK";

    @Override
    public com.fooddelivery.common.enums.PaymentGateway getSupportedGateway() {
        return com.fooddelivery.common.enums.PaymentGateway.CASHFREE;
    }

    @Override
    public void handleEvent(String eventType, JsonNode rootNode, PaymentActionDelegate delegate) {
        String gatewayOrderId = rootNode.path("data").path("order").path("order_id").asText();
        if (gatewayOrderId != null && !gatewayOrderId.isEmpty()) {
            if (EVENT_PAYMENT_SUCCESS.equals(eventType)) {
                delegate.handleSuccessfulPayment(gatewayOrderId);
            } else if (EVENT_PAYMENT_FAILED.equals(eventType)) {
                delegate.handleFailedPayment(gatewayOrderId, "Cashfree payment failed");
            } else if (EVENT_REFUND_SUCCESS.equals(eventType)) {
                String refundId = rootNode.path("data").path("refund").path("refund_id").asText();
                double refundAmount = rootNode.path("data").path("refund").path("refund_amount").asDouble(0);
                com.fasterxml.jackson.databind.node.ObjectNode syntheticPayload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                syntheticPayload.put("amount_refunded", refundAmount);
                delegate.handleRefundSuccess(gatewayOrderId, refundId, syntheticPayload);
            }
        }
    }
}
