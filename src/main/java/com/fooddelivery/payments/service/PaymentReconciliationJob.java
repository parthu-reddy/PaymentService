package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.common.constants.PaymentIntentStatus;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@lombok.extern.slf4j.Slf4j
public class PaymentReconciliationJob {

    private final IPaymentIntentRepository paymentIntentRepository;
    private final PaymentGatewayOrchestrator orchestrator;
    private final WebhookProcessingService webhookProcessingService;
    private final StringRedisTemplate redisTemplate;

    public PaymentReconciliationJob(
            IPaymentIntentRepository paymentIntentRepository,
            PaymentGatewayOrchestrator orchestrator,
            WebhookProcessingService webhookProcessingService,
            StringRedisTemplate redisTemplate) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.orchestrator = orchestrator;
        this.webhookProcessingService = webhookProcessingService;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation.interval:600000}")
    public void reconcileStuckPayments() {
        com.fooddelivery.common.lock.RedisLock _redisLock = new com.fooddelivery.common.lock.RedisLock(redisTemplate);
        String _lockToken = java.util.UUID.randomUUID().toString();
        boolean locked = _redisLock.tryAcquire(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_RECONCILE_PENDING_PAYMENTS, _lockToken, Duration.ofSeconds(500));
        if (!locked) { return; }

        try {
            log.info("Starting Payment Reconciliation Job");

            // Find intents stuck in INITIATED for more than 10 minutes
            List<PaymentIntent> stuckIntents = paymentIntentRepository.findTop100ByStatusAndCreatedAtBefore(
                    PaymentIntentStatus.INITIATED, 
                    java.time.ZonedDateTime.now().minusMinutes(10)
            );

            for (PaymentIntent intent : stuckIntents) {
                try {
                    log.info("Reconciling stuck payment intent: {}", intent.getId());
                    PaymentIntentStatus status = orchestrator.getStrategy(intent.getGatewayName())
                            .verifyStatus(intent.getGatewayOrderId());

                    if (status == PaymentIntentStatus.SUCCESS || 
                        status == PaymentIntentStatus.CAPTURED || 
                        status == PaymentIntentStatus.PAID) {
                        
                        log.info("Payment intent {} was actually successful on gateway. Triggering fulfillment.", intent.getId());
                        webhookProcessingService.handleSuccessfulPayment(intent.getGatewayOrderId());
                    } else if (status == PaymentIntentStatus.FAILED) {
                        webhookProcessingService.handleFailedPayment(intent.getGatewayOrderId(), "Reconciliation determined payment failed");
                        log.info("Reconciled payment intent to FAILED: {}", intent.getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to reconcile intent: {}", intent.getId(), e);
                }
            }
            log.info("Completed Payment Reconciliation Job");
        } finally {
            _redisLock.release(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_RECONCILE_PENDING_PAYMENTS, _lockToken);
        }
    }
}
