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
import java.time.ZonedDateTime;
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

        when(paymentIntentRepository.findTop100ByStatusAndCreatedAtBefore(eq(PaymentIntentStatus.INITIATED), any(ZonedDateTime.class)))
                .thenReturn(List.of(intent));
        
        when(orchestrator.getStrategy(PaymentGateway.VYAPAR)).thenReturn(mockStrategy);
        when(mockStrategy.verifyStatus("ord_123")).thenReturn(com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS);

        job.reconcileStuckPayments();

        verify(webhookProcessingService).handleSuccessfulPayment("ord_123", new BigDecimal("100.00"));
    }
}
