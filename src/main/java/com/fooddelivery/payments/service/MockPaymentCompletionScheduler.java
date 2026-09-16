package com.fooddelivery.payments.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Completes dev/test payments only after PaymentController has saved the intent.
 *
 * <p>Scheduling inside a mock gateway raced the database insert: a busy database could make the
 * callback run first, fail with "PaymentIntent not found", and leave the order unpaid forever.
 */
@Component
@Profile("!prod & (debug | dev | default | test | local | contract-test)")
@Slf4j
public class MockPaymentCompletionScheduler implements PaymentCompletionScheduler {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final WebhookProcessingService webhookProcessingService;

    public MockPaymentCompletionScheduler(WebhookProcessingService webhookProcessingService) {
        this.webhookProcessingService = webhookProcessingService;
    }

    @Override
    public void afterIntentPersisted(String internalOrderId, String gatewayOrderId, BigDecimal amount) {
        log.info("DEV_PAYMENT_SUCCESS_SCHEDULED internalOrderId={} gatewayOrderId={} delayMs=250",
                internalOrderId, gatewayOrderId);
        scheduler.schedule(() -> {
            try {
                webhookProcessingService.handleSuccessfulPayment(gatewayOrderId, amount);
                log.info("DEV_PAYMENT_SUCCESS_COMPLETED internalOrderId={} gatewayOrderId={} amount={}",
                        internalOrderId, gatewayOrderId, amount);
            } catch (RuntimeException e) {
                log.error("DEV_PAYMENT_SUCCESS_FAILED internalOrderId={} gatewayOrderId={} errorType={} error={}",
                        internalOrderId, gatewayOrderId, e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }, 250, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
