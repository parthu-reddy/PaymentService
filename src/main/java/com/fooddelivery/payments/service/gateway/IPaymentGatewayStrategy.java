package com.fooddelivery.payments.service.gateway;

import com.fooddelivery.common.enums.PaymentGateway;

public interface IPaymentGatewayStrategy {
    
    /**
     * Identifies the gateway (e.g., "RAZORPAY", "CASHFREE").
     */
    PaymentGateway getGatewayName();
    
    /**
     * Creates an order on the payment gateway and returns the gateway's order ID.
     */
    String createOrder(PaymentRequestContext context);
    
    /**
     * Verifies the cryptographic signature of an incoming webhook.
     */
    boolean verifyWebhookSignature(String payload, String signature, String timestamp);

    /**
     * Verifies the status of the order on the gateway.
     */
    com.fooddelivery.common.constants.PaymentIntentStatus verifyStatus(String gatewayOrderId);

    /**
     * Initiates a refund on the gateway.
     */
    boolean initiateRefund(String gatewayOrderId, String refundId, java.math.BigDecimal amount, String reason);
}
