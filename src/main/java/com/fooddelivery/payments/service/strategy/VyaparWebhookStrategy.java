package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class VyaparWebhookStrategy implements WebhookHandlerStrategy {

    @Override
    public String getSupportedGateway() {
        return "VYAPAR";
    }

    @Override
    public void handleEvent(String eventType, JsonNode rootNode, PaymentActionDelegate delegate) {
        String gatewayOrderId = rootNode.path("payload").path("payment").path("entity").path("order_id").asText();
        if (gatewayOrderId == null || gatewayOrderId.isEmpty()) {
            gatewayOrderId = rootNode.path("order_id").asText();
        }
        
        if ("payment.success".equals(eventType)) {
            delegate.handleSuccessfulPayment(gatewayOrderId);
        } else if ("payment.failed".equals(eventType)) {
            delegate.handleFailedPayment(gatewayOrderId, "Vyapar payment failed");
        } else if ("refund.success".equals(eventType)) {
            delegate.handleRefundSuccess(gatewayOrderId, rootNode);
        }
    }
}
