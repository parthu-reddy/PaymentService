package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.common.constants.PaymentIntentStatus;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fooddelivery.common.enums.PaymentGateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PaymentReconciliationJobTest {

    @Mock
    private IPaymentIntentRepository paymentIntentRepository;
    @Mock
    private PaymentGatewayOrchestrator orchestrator;
    @Mock
    private WebhookProcessingService webhookProcessingService;
    @Mock
    private IPaymentGatewayStrategy mockStrategy;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private PaymentReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new PaymentReconciliationJob(paymentIntentRepository, orchestrator, webhookProcessingService, redisTemplate);
    }

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;

    @Test
    void testReconcileStuckPayments() {
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("ord_123");
        intent.setGatewayName(PaymentGateway.VYAPAR);
        intent.setStatus(PaymentIntentStatus.INITIATED);
        intent.setAmount(new BigDecimal("100.00"));
        intent.setOrderId(UUID.randomUUID().toString());

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:reconcilePendingPayments"), org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(true);

        when(paymentIntentRepository.findTop100ByStatusAndCreatedAtBefore(eq(PaymentIntentStatus.INITIATED), any(Instant.class)))
                .thenReturn(List.of(intent));
        
        when(orchestrator.getStrategy(PaymentGateway.VYAPAR)).thenReturn(mockStrategy);
        when(mockStrategy.verifyStatus("ord_123")).thenReturn(com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS);

        job.reconcileStuckPayments();

        verify(webhookProcessingService).handleSuccessfulPayment("ord_123", new BigDecimal("100.00"));
    }

    private PaymentIntent stuckIntent() {
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("ord_123");
        intent.setGatewayName(PaymentGateway.VYAPAR);
        intent.setStatus(PaymentIntentStatus.INITIATED);
        intent.setAmount(new BigDecimal("100.00"));
        intent.setOrderId(UUID.randomUUID().toString());
        return intent;
    }

    private void lockGranted() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:reconcilePendingPayments"),
                org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(true);
    }

    /** A gateway that reports failure must fail the intent, not leave it stuck forever. */
    @Test
    void aFailedPaymentAtTheGatewayIsRecordedAsFailed() {
        lockGranted();
        when(paymentIntentRepository.findTop100ByStatusAndCreatedAtBefore(eq(PaymentIntentStatus.INITIATED), any(Instant.class)))
                .thenReturn(List.of(stuckIntent()));
        when(orchestrator.getStrategy(PaymentGateway.VYAPAR)).thenReturn(mockStrategy);
        when(mockStrategy.verifyStatus("ord_123")).thenReturn(PaymentIntentStatus.FAILED);

        job.reconcileStuckPayments();

        verify(webhookProcessingService).handleFailedPayment(eq("ord_123"), org.mockito.ArgumentMatchers.anyString());
        verify(webhookProcessingService, never()).handleSuccessfulPayment(any(), any());
    }

    /** Still pending at the gateway means leave it alone: it is not stuck, it is in flight. */
    @Test
    void aPaymentStillInFlightIsLeftAlone() {
        lockGranted();
        when(paymentIntentRepository.findTop100ByStatusAndCreatedAtBefore(eq(PaymentIntentStatus.INITIATED), any(Instant.class)))
                .thenReturn(List.of(stuckIntent()));
        when(orchestrator.getStrategy(PaymentGateway.VYAPAR)).thenReturn(mockStrategy);
        when(mockStrategy.verifyStatus("ord_123")).thenReturn(PaymentIntentStatus.INITIATED);

        job.reconcileStuckPayments();

        verify(webhookProcessingService, never()).handleSuccessfulPayment(any(), any());
        verify(webhookProcessingService, never()).handleFailedPayment(any(), any());
    }

    /**
     * The job holds a Redis lock. Without it every replica reconciles the same intents at once and
     * the gateway is asked the same question N times.
     */
    @Test
    void anotherReplicaHoldingTheLockMeansThisOneDoesNothing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock:reconcilePendingPayments"),
                org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(false);

        job.reconcileStuckPayments();

        verify(paymentIntentRepository, never()).findTop100ByStatusAndCreatedAtBefore(any(), any());
        verifyNoInteractions(webhookProcessingService);
    }

    /** One unreachable gateway must not stop the rest of the queue being reconciled. */
    @Test
    void aGatewayThatThrowsDoesNotAbandonTheRun() {
        lockGranted();
        when(paymentIntentRepository.findTop100ByStatusAndCreatedAtBefore(eq(PaymentIntentStatus.INITIATED), any(Instant.class)))
                .thenReturn(List.of(stuckIntent()));
        when(orchestrator.getStrategy(PaymentGateway.VYAPAR)).thenThrow(new RuntimeException("gateway unreachable"));

        job.reconcileStuckPayments();

        verifyNoInteractions(webhookProcessingService);
    }
}
