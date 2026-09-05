package com.fooddelivery.payments.service;

import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import com.fooddelivery.payments.service.gateway.PaymentRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import com.fooddelivery.common.enums.PaymentGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentGatewayOrchestratorTest {

    @Mock
    private IPaymentGatewayStrategy mockStrategy;

    private PaymentGatewayOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        when(mockStrategy.getGatewayName()).thenReturn(PaymentGateway.VYAPAR);
        orchestrator = new PaymentGatewayOrchestrator(List.of(mockStrategy));
    }

    @Test
    void getStrategy_ShouldReturnCorrectStrategy() {
        IPaymentGatewayStrategy strategy = orchestrator.getStrategy(PaymentGateway.VYAPAR);
        assertThat(strategy).isEqualTo(mockStrategy);
    }

    @Test
    void getStrategy_ShouldThrowExceptionForUnsupportedGateway() {
        assertThatThrownBy(() -> orchestrator.getStrategy(PaymentGateway.RAZORPAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported gateway");
    }

    @Test
    void getStrategy_ShouldThrowExceptionForNullGateway() {
        assertThatThrownBy(() -> orchestrator.getStrategy(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Gateway name cannot be null");
    }

    @Test
    void createOrder_ShouldDelegateToStrategy() {
        PaymentRequestContext context = PaymentRequestContext.builder()
                .internalOrderId(java.util.UUID.randomUUID().toString())
                .amountInInr(new java.math.BigDecimal("100.00"))
                .receiptRef("receipt")
                .customerPhone("1234567890")
                .build();
        when(mockStrategy.createOrder(context)).thenReturn("ORDER_123");

        String orderId = orchestrator.createOrder(PaymentGateway.VYAPAR, context);

        assertThat(orderId).isEqualTo("ORDER_123");
        verify(mockStrategy).createOrder(context);
    }

    @Test
    void initiateRefund_ShouldDelegateToStrategy() {
        when(mockStrategy.initiateRefund("GATEWAY_ORDER_123", "REFUND_123", new java.math.BigDecimal("100.0"), "Customer Request"))
                .thenReturn(true);

        boolean result = orchestrator.initiateRefund(PaymentGateway.VYAPAR, "GATEWAY_ORDER_123", "REFUND_123", new java.math.BigDecimal("100.0"), "Customer Request");

        assertThat(result).isTrue();
        verify(mockStrategy).initiateRefund("GATEWAY_ORDER_123", "REFUND_123", new java.math.BigDecimal("100.0"), "Customer Request");
    }
}
