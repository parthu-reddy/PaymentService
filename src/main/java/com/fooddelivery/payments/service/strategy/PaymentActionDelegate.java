package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;

public interface PaymentActionDelegate {
    void handleSuccessfulPayment(String gatewayOrderId);
    void handleFailedPayment(String gatewayOrderId, String failureReason);
    void handleRefundSuccess(String gatewayOrderId, String gatewayRefundId, JsonNode rootNode);
}
