package com.fooddelivery.payments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fooddelivery.payments.service.WebhookProcessingService;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.repository.ITransactionRepository;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import com.fooddelivery.payments.repository.IRefundRepository;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.common.enums.PaymentGateway;
import com.fooddelivery.common.constants.PaymentIntentStatus;
import org.springframework.transaction.TransactionStatus;
import java.util.function.Consumer;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Collections;

public class WebhookAmountVerificationTest {

    private WebhookProcessingService webhookProcessingService;
    private IPaymentIntentRepository paymentIntentRepository;
    private MeterRegistry meterRegistry;
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        paymentIntentRepository = mock(IPaymentIntentRepository.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        
        // Mock transaction template to execute the callback immediately
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        meterRegistry = new SimpleMeterRegistry();
        outboxEventRepository = mock(OutboxEventRepository.class);

        webhookProcessingService = new WebhookProcessingService(
            mock(IWebhookDeliveryRepository.class),
            paymentIntentRepository,
            mock(ITransactionRepository.class),
            mock(IRefundRepository.class),
            new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()),
            outboxEventRepository,
            transactionTemplate,
            mock(StringRedisTemplate.class),
            Collections.emptyList(),
            meterRegistry
        );
    }

    @Test
    void testAmountMismatch_SetsIntentToFailed() {
        String gatewayOrderId = "order_123";
        PaymentIntent intent = new PaymentIntent();
        intent.setOrderId(java.util.UUID.randomUUID().toString());
        intent.setGatewayOrderId(gatewayOrderId);
        intent.setAmount(new BigDecimal("100.00"));
        intent.setGatewayName(PaymentGateway.CASHFREE);
        intent.setStatus(PaymentIntentStatus.INITIATED);

        when(paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId)).thenReturn(Optional.of(intent));

        // Attempt successful payment with mismatched amount
        webhookProcessingService.handleSuccessfulPayment(gatewayOrderId, new BigDecimal("50.00"));

        // Intent should be marked FAILED
        assertEquals(PaymentIntentStatus.FAILED, intent.getStatus());
        verify(paymentIntentRepository, times(1)).save(intent);
        
        // Ensure metric was incremented
        assertEquals(1.0, meterRegistry.counter("payment.amount.mismatch", "gateway", "CASHFREE").count());
        
        // Ensure outbox event for FAILED payment was emitted
        verify(outboxEventRepository, times(1)).save(any());
    }

    @Test
    void testAmountMatch_SetsIntentToSuccess() {
        String gatewayOrderId = "order_123";
        PaymentIntent intent = new PaymentIntent();
        intent.setOrderId(java.util.UUID.randomUUID().toString());
        intent.setGatewayOrderId(gatewayOrderId);
        intent.setAmount(new BigDecimal("100.00"));
        intent.setGatewayName(PaymentGateway.CASHFREE);
        intent.setStatus(PaymentIntentStatus.INITIATED);

        when(paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId)).thenReturn(Optional.of(intent));

        webhookProcessingService.handleSuccessfulPayment(gatewayOrderId, new BigDecimal("100.00"));

        assertEquals(PaymentIntentStatus.SUCCESS, intent.getStatus());
        verify(paymentIntentRepository, times(1)).save(intent);
        verify(outboxEventRepository, times(1)).save(any());
    }
}
