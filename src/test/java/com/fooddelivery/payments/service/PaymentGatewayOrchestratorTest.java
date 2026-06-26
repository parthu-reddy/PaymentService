package com.fooddelivery.payments.service;

import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PaymentGatewayOrchestratorTest {

    private PaymentGatewayOrchestrator orchestrator;
    private IPaymentGatewayStrategy razorpayStrategy;
    private IPaymentGatewayStrategy cashfreeStrategy;

    @BeforeEach
    void setUp() {
        razorpayStrategy = mock(IPaymentGatewayStrategy.class);
        when(razorpayStrategy.getGatewayName()).thenReturn("RAZORPAY");

        cashfreeStrategy = mock(IPaymentGatewayStrategy.class);
        when(cashfreeStrategy.getGatewayName()).thenReturn("CASHFREE");

        orchestrator = new PaymentGatewayOrchestrator(Arrays.asList(razorpayStrategy, cashfreeStrategy));
    }

    @Test
    void testGetStrategy_Success() {
        IPaymentGatewayStrategy strategy = orchestrator.getStrategy("RAZORPAY");
        assertEquals("RAZORPAY", strategy.getGatewayName());
    }

    @Test
    void testGetStrategy_NotFound() {
        assertThrows(IllegalArgumentException.class, () -> {
            orchestrator.getStrategy("UNKNOWN");
        });
    }
}
