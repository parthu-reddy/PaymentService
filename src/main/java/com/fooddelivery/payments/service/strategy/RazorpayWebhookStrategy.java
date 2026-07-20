package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class RazorpayWebhookStrategy implements WebhookHandlerStrategy {

    @Override
    public com.fooddelivery.common.enums.PaymentGateway getSupportedGateway() {
        return com.fooddelivery.common.enums.PaymentGateway.RAZORPAY;
    }

    @Override
    public void handleEvent(String eventType, JsonNode rootNode, PaymentActionDelegate delegate) {
        String gatewayOrderId = rootNode.path("payload").path("payment").path("entity").path("order_id").asText();
        if (gatewayOrderId != null && !gatewayOrderId.isEmpty()) {
            if ("order.paid".equals(eventType)) {
                delegate.handleSuccessfulPayment(gatewayOrderId);
            } else if ("payment.failed".equals(eventType)) {
                delegate.handleFailedPayment(gatewayOrderId, "Razorpay payment failed");
            }
        }
    }
}
