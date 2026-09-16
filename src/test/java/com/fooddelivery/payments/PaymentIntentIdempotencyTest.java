package com.fooddelivery.payments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fooddelivery.payments.controller.PaymentController;
import com.fooddelivery.payments.controller.PaymentController.CreateOrderRequest;
import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.config.PaymentRoutingConfig;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.common.enums.PaymentGateway;
import com.fooddelivery.common.enums.PaymentMethod;

import java.math.BigDecimal;
import java.util.Optional;

public class PaymentIntentIdempotencyTest {

    private PaymentController paymentController;
    private PaymentGatewayOrchestrator orchestrator;
    private IPaymentIntentRepository paymentIntentRepository;
    private PaymentRoutingConfig routingConfig;
    private com.fooddelivery.payments.service.PaymentCompletionScheduler paymentCompletionScheduler;

    @BeforeEach
    void setUp() {
        orchestrator = mock(PaymentGatewayOrchestrator.class);
        paymentIntentRepository = mock(IPaymentIntentRepository.class);
        routingConfig = mock(PaymentRoutingConfig.class);
        paymentCompletionScheduler = mock(com.fooddelivery.payments.service.PaymentCompletionScheduler.class);
        paymentController = new PaymentController(orchestrator, paymentIntentRepository, routingConfig,
                paymentCompletionScheduler);
    }

    @Test
    void testCreateOrder_IdempotentResponse() {
        String idempotencyKey = "idemp-key-123";
        CreateOrderRequest request = new CreateOrderRequest();
        request.internalOrderId = "order-123";
        request.amountInInr = new BigDecimal("100.00");
        request.paymentMethod = PaymentMethod.CARD;

        when(routingConfig.getGatewayForMethod(PaymentMethod.CARD)).thenReturn(PaymentGateway.CASHFREE);

        PaymentIntent existingIntent = new PaymentIntent();
        existingIntent.setGatewayName(PaymentGateway.CASHFREE);
        existingIntent.setGatewayOrderId("gateway-order-123");
        existingIntent.setOrderId(request.internalOrderId);
        existingIntent.setAmount(request.amountInInr);
        when(paymentIntentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingIntent));

        ResponseEntity<?> response = paymentController.createOrder(idempotencyKey, request);

        assertEquals(200, response.getStatusCodeValue());
        com.fooddelivery.common.dto.payment.CreatePaymentResponse body =
                (com.fooddelivery.common.dto.payment.CreatePaymentResponse) response.getBody();
        assertEquals("gateway-order-123", body.gatewayOrderId());
        assertEquals(PaymentGateway.CASHFREE, body.gateway());
        verify(orchestrator, never()).createOrder(any(), any());
        verify(paymentCompletionScheduler).afterIntentPersisted(
                request.internalOrderId, "gateway-order-123", request.amountInInr);
    }

    @Test
    void testCreateOrder_DifferentGatewayThrowsError() {
        String idempotencyKey = "idemp-key-123";
        CreateOrderRequest request = new CreateOrderRequest();
        request.internalOrderId = "order-123";
        request.amountInInr = new BigDecimal("100.00");
        request.paymentMethod = PaymentMethod.CARD;

        when(routingConfig.getGatewayForMethod(PaymentMethod.CARD)).thenReturn(PaymentGateway.CASHFREE);

        PaymentIntent existingIntent = new PaymentIntent();
        existingIntent.setGatewayName(PaymentGateway.RAZORPAY);
        when(paymentIntentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingIntent));

        ResponseEntity<?> response = paymentController.createOrder(idempotencyKey, request);

        assertEquals(400, response.getStatusCodeValue());
        assertTrue(response.getBody().toString().contains("Order already initiated with different gateway"));
    }

    @Test
    void newIntentIsPersistedBeforeDevCompletionIsScheduled() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.internalOrderId = "123e4567-e89b-12d3-a456-426614174000";
        request.amountInInr = new BigDecimal("100.00");
        request.paymentMethod = PaymentMethod.UPI;

        when(routingConfig.getGatewayForMethod(PaymentMethod.UPI)).thenReturn(PaymentGateway.CASHFREE);
        when(paymentIntentRepository.findByIdempotencyKey(request.internalOrderId)).thenReturn(Optional.empty());
        when(orchestrator.createOrder(any(), any())).thenReturn("mock_cf_txn_123");

        ResponseEntity<?> response = paymentController.createOrder(null, request);

        assertEquals(200, response.getStatusCodeValue());
        org.mockito.InOrder inOrder = inOrder(paymentIntentRepository, paymentCompletionScheduler);
        inOrder.verify(paymentIntentRepository).save(any(PaymentIntent.class));
        inOrder.verify(paymentCompletionScheduler).afterIntentPersisted(
                request.internalOrderId, "mock_cf_txn_123", request.amountInInr);
    }
}
