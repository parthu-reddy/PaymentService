package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class CashfreeWebhookStrategy implements WebhookHandlerStrategy {

    @Override
    public com.fooddelivery.common.enums.PaymentGateway getSupportedGateway() {
        return com.fooddelivery.common.enums.PaymentGateway.CASHFREE;
    }

    @Override
    public void handleEvent(String eventType, JsonNode rootNode, PaymentActionDelegate delegate) {
        String gatewayOrderId = rootNode.path("data").path("order").path("order_id").asText();
        if (gatewayOrderId != null && !gatewayOrderId.isEmpty()) {
            if ("PAYMENT_SUCCESS_WEBHOOK".equals(eventType)) {
                delegate.handleSuccessfulPayment(gatewayOrderId);
            } else if ("PAYMENT_FAILED_WEBHOOK".equals(eventType)) {
                delegate.handleFailedPayment(gatewayOrderId, "Cashfree payment failed");
            }
        }
    }
}
