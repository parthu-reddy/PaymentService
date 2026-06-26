package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.Order;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.enums.IntentStatus;
import com.fooddelivery.payments.model.enums.OrderStatus;
import com.fooddelivery.payments.repository.IOrderRepository;
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
    private final IOrderRepository orderRepository;
    private final PaymentGatewayOrchestrator orchestrator;

    public PaymentReconciliationJob(
            IPaymentIntentRepository paymentIntentRepository,
            IOrderRepository orderRepository,
            PaymentGatewayOrchestrator orchestrator) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.orderRepository = orderRepository;
        this.orchestrator = orchestrator;
    }

    // Run every 5 minutes
    @Scheduled(fixedRate = 300000)
    public void reconcileStuckPayments() {
        logger.info("Starting Payment Reconciliation Job");

        // Find intents stuck in INITIATED for more than 15 minutes
        List<PaymentIntent> stuckIntents = paymentIntentRepository.findByStatusAndCreatedAtBefore(
                IntentStatus.INITIATED, 
                LocalDateTime.now().minusMinutes(15)
        );

        for (PaymentIntent intent : stuckIntents) {
            try {
                logger.info("Reconciling stuck payment intent: {}", intent.getId());
                String status = orchestrator.getStrategy(intent.getGatewayName())
                        .verifyStatus(intent.getGatewayOrderId());

                if ("SUCCESS".equalsIgnoreCase(status)) {
                    intent.setStatus(IntentStatus.SUCCESS);
                    Order order = intent.getOrder();
                    order.setStatus(OrderStatus.PAID);
                    
                    paymentIntentRepository.save(intent);
                    orderRepository.save(order);
                    logger.info("Successfully reconciled payment intent to PAID: {}", intent.getId());
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
