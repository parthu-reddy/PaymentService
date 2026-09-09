package com.fooddelivery.payments.strategy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import com.fooddelivery.common.enums.PaymentGateway;

import java.util.List;

public class PaymentGatewayStrategyUniquenessTest {
    
    @Test
    public void testStrategyUniqueness() {
        IPaymentGatewayStrategy mockStrategy1 = mock(IPaymentGatewayStrategy.class);
        when(mockStrategy1.getGatewayName()).thenReturn(PaymentGateway.RAZORPAY);

        IPaymentGatewayStrategy mockStrategy2 = mock(IPaymentGatewayStrategy.class);
        when(mockStrategy2.getGatewayName()).thenReturn(PaymentGateway.CASHFREE);

        // Should not throw
        PaymentGatewayOrchestrator orchestrator = new PaymentGatewayOrchestrator(List.of(mockStrategy1, mockStrategy2));
        assertNotNull(orchestrator);
    }
    
    @Test
    public void testStrategyUniqueness2() {
        IPaymentGatewayStrategy mockStrategy1 = mock(IPaymentGatewayStrategy.class);
        when(mockStrategy1.getGatewayName()).thenReturn(PaymentGateway.RAZORPAY);

        IPaymentGatewayStrategy mockStrategy2 = mock(IPaymentGatewayStrategy.class);
        when(mockStrategy2.getGatewayName()).thenReturn(PaymentGateway.RAZORPAY);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            new PaymentGatewayOrchestrator(List.of(mockStrategy1, mockStrategy2));
        });
        
        assertTrue(ex.getMessage().contains("Two strategies registered for gateway RAZORPAY"));
    }
}
