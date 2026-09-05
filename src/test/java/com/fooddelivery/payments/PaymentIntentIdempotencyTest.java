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

    @BeforeEach
    void setUp() {
        orchestrator = mock(PaymentGatewayOrchestrator.class);
        paymentIntentRepository = mock(IPaymentIntentRepository.class);
        routingConfig = mock(PaymentRoutingConfig.class);
        paymentController = new PaymentController(orchestrator, paymentIntentRepository, routingConfig);
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
        when(paymentIntentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingIntent));

        ResponseEntity<String> response = paymentController.createOrder(idempotencyKey, request);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("gateway-order-123", response.getBody());
        verify(orchestrator, never()).createOrder(any(), any());
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

        ResponseEntity<String> response = paymentController.createOrder(idempotencyKey, request);

        assertEquals(400, response.getStatusCodeValue());
        assertTrue(response.getBody().contains("Order already initiated with different gateway"));
    }
}
