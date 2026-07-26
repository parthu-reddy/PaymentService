package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class RazorpayWebhookStrategy implements WebhookHandlerStrategy {

    private static final String EVENT_ORDER_PAID = "order.paid";
    private static final String EVENT_PAYMENT_FAILED = "payment.failed";

    @Override
    public com.fooddelivery.common.enums.PaymentGateway getSupportedGateway() {
        return com.fooddelivery.common.enums.PaymentGateway.RAZORPAY;
    }

    @Override
    public void handleEvent(String eventType, JsonNode rootNode, PaymentActionDelegate delegate) {
        String gatewayOrderId = rootNode.path("payload").path("payment").path("entity").path("order_id").asText();
        if (gatewayOrderId != null && !gatewayOrderId.isEmpty()) {
            if (EVENT_ORDER_PAID.equals(eventType)) {
                delegate.handleSuccessfulPayment(gatewayOrderId);
            } else if (EVENT_PAYMENT_FAILED.equals(eventType)) {
                delegate.handleFailedPayment(gatewayOrderId, "Razorpay payment failed");
            }
        }
    }
}
