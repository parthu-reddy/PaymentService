package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.enums.IntentStatus;
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

    private PaymentReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new PaymentReconciliationJob(paymentIntentRepository, orchestrator, webhookProcessingService);
    }

    @Test
    void testReconcileStuckPayments() {
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("ord_123");
        intent.setGatewayName("VYAPAR");
        intent.setStatus(IntentStatus.INITIATED);
        intent.setAmount(new BigDecimal("100.00"));
        intent.setOrderId(UUID.randomUUID().toString());

        when(paymentIntentRepository.findTop100ByStatusAndCreatedAtBefore(eq(IntentStatus.INITIATED), any(ZonedDateTime.class)))
                .thenReturn(List.of(intent));
        
        when(orchestrator.getStrategy("VYAPAR")).thenReturn(mockStrategy);
        when(mockStrategy.verifyStatus("ord_123")).thenReturn(com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS);

        job.reconcileStuckPayments();

        verify(webhookProcessingService).handleSuccessfulPayment("ord_123");
    }
}
