package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.enums.IntentStatus;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PaymentReconciliationJob {

    private static final Logger logger = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private final IPaymentIntentRepository paymentIntentRepository;
    private final PaymentGatewayOrchestrator orchestrator;
    private final WebhookProcessingService webhookProcessingService;

    public PaymentReconciliationJob(
            IPaymentIntentRepository paymentIntentRepository,
            PaymentGatewayOrchestrator orchestrator,
            WebhookProcessingService webhookProcessingService) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.orchestrator = orchestrator;
        this.webhookProcessingService = webhookProcessingService;
    }

    @Scheduled(fixedRateString = "${payment.reconciliation.interval:600000}")
    public void reconcileStuckPayments() {
        logger.info("Starting Payment Reconciliation Job");

        // Find intents stuck in INITIATED for more than 10 minutes
        List<PaymentIntent> stuckIntents = paymentIntentRepository.findTop100ByStatusAndCreatedAtBefore(
                IntentStatus.INITIATED, 
                java.time.ZonedDateTime.now().minusMinutes(10)
        );

        for (PaymentIntent intent : stuckIntents) {
            try {
                logger.info("Reconciling stuck payment intent: {}", intent.getId());
                String status = orchestrator.getStrategy(intent.getGatewayName())
                        .verifyStatus(intent.getGatewayOrderId());

                if ("SUCCESS".equalsIgnoreCase(status) || 
                    "CAPTURED".equalsIgnoreCase(status) || 
                    "PAID".equalsIgnoreCase(status)) {
                    
                    logger.info("Payment intent {} was actually successful on gateway. Triggering fulfillment.", intent.getId());
                    webhookProcessingService.handleSuccessfulPayment(intent.getGatewayOrderId());
                } else if ("FAILED".equalsIgnoreCase(status)) {
                    intent.setStatus(IntentStatus.FAILED);
                    paymentIntentRepository.save(intent);
                    logger.info("Reconciled payment intent to FAILED: {}", intent.getId());
                }
            } catch (Exception e) {
                logger.error("Failed to reconcile intent: {}", intent.getId(), e);
            }
        }
        logger.info("Completed Payment Reconciliation Job");
    }
}
