package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class CashfreeWebhookStrategy implements WebhookHandlerStrategy {

    private static final String EVENT_PAYMENT_SUCCESS = "PAYMENT_SUCCESS_WEBHOOK";
    private static final String EVENT_PAYMENT_FAILED = "PAYMENT_FAILED_WEBHOOK";

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
            }
        }
    }
}
