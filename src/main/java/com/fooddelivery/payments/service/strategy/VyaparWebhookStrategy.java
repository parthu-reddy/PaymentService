package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class VyaparWebhookStrategy implements WebhookHandlerStrategy {

    private static final String EVENT_PAYMENT_SUCCESS = "payment.success";
    private static final String EVENT_PAYMENT_FAILED = "payment.failed";
    private static final String EVENT_REFUND_SUCCESS = "refund.success";

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
            delegate.handleSuccessfulPayment(gatewayOrderId);
        } else if (EVENT_PAYMENT_FAILED.equals(eventType)) {
            delegate.handleFailedPayment(gatewayOrderId, "Vyapar payment failed");
        } else if (EVENT_REFUND_SUCCESS.equals(eventType)) {
            delegate.handleRefundSuccess(gatewayOrderId, rootNode);
        }
    }
}
