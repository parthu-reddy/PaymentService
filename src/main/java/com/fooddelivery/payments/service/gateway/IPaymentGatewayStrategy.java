package com.fooddelivery.payments.service.gateway;

public interface IPaymentGatewayStrategy {
    
    /**
     * Identifies the gateway (e.g., "RAZORPAY", "CASHFREE").
     */
    String getGatewayName();
    
    /**
     * Creates an order on the payment gateway and returns the gateway's order ID.
     */
    String createOrder(PaymentRequestContext context);
    
    /**
     * Verifies the cryptographic signature of an incoming webhook.
     */
    boolean verifyWebhookSignature(String payload, String signature, String timestamp);

    /**
     * Initiates a refund on the gateway.
     */
    boolean initiateRefund(String gatewayOrderId, double amount, String reason);
}
