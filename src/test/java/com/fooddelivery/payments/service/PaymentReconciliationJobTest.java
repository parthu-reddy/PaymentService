package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.enums.IntentStatus;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PaymentReconciliationJobTest {

    @Mock
    private IPaymentIntentRepository paymentIntentRepository;
    @Mock
    private PaymentEventPublisher eventPublisher;
    @Mock
    private TransactionTemplate transactionTemplate;

    private PaymentReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new PaymentReconciliationJob(paymentIntentRepository, eventPublisher, transactionTemplate);
        
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void testReconcileStuckPayments() {
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("ord_123");
        intent.setStatus(IntentStatus.INITIATED);
        intent.setAmount(new BigDecimal("100.00"));
        intent.setOrderId(UUID.randomUUID().toString());

        when(paymentIntentRepository.findByStatusAndCreatedAtBefore(eq(IntentStatus.INITIATED), any(LocalDateTime.class)))
                .thenReturn(List.of(intent));

        job.reconcileStuckPayments();

        assertEquals(IntentStatus.SUCCESS, intent.getStatus());
        verify(paymentIntentRepository).save(intent);
    }
}
