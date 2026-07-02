package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;

public interface WebhookHandlerStrategy {
    
    /**
     * @return The gateway name this strategy supports (e.g., "VYAPAR", "RAZORPAY", "CASHFREE").
     */
    String getSupportedGateway();

    /**
     * Handle the incoming webhook event for the supported gateway.
     * 
     * @param eventType The resolved event type (e.g., "payment.success").
     * @param rootNode The JSON payload of the webhook.
     * @param delegate The delegate for common actions.
     */
    void handleEvent(String eventType, JsonNode rootNode, PaymentActionDelegate delegate);
}
