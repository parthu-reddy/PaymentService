package com.fooddelivery.payments.service;

import java.math.BigDecimal;

/** Schedules environment-specific completion after a payment intent has been persisted. */
public interface PaymentCompletionScheduler {
    void afterIntentPersisted(String internalOrderId, String gatewayOrderId, BigDecimal amount);
}
