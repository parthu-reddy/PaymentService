package com.fooddelivery.payments.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** Production payments complete only through a verified gateway webhook. */
@Component
@Profile("prod")
public class ProductionPaymentCompletionScheduler implements PaymentCompletionScheduler {
    @Override
    public void afterIntentPersisted(String internalOrderId, String gatewayOrderId, BigDecimal amount) {
        // Intentionally empty. The real provider webhook is the source of truth in production.
    }
}
