package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.Order;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.enums.IntentStatus;
import com.fooddelivery.payments.model.enums.OrderStatus;
import com.fooddelivery.payments.repository.IOrderRepository;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PaymentReconciliationJobTest {

    @Mock
    private IPaymentIntentRepository paymentIntentRepository;

    @Mock
    private IOrderRepository orderRepository;

    @Mock
    private PaymentGatewayOrchestrator orchestrator;

    @Mock
    private IPaymentGatewayStrategy strategy;

    @InjectMocks
    private PaymentReconciliationJob job;

    @Test
    void testReconcileStuckPayments_Success() {
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayName("VYAPAR");
        intent.setGatewayOrderId("vyapar_123");
        intent.setStatus(IntentStatus.INITIATED);
        intent.setCreatedAt(java.time.ZonedDateTime.now().minusMinutes(20));
        
        Order order = new Order();
        order.setStatus(OrderStatus.CREATED);
        intent.setOrder(order);

        when(paymentIntentRepository.findTop100ByStatusAndCreatedAtBefore(eq(IntentStatus.INITIATED), any())).thenReturn(List.of(intent));
        when(orchestrator.getStrategy("VYAPAR")).thenReturn(strategy);
        when(strategy.verifyStatus("vyapar_123")).thenReturn("SUCCESS");

        job.reconcileStuckPayments();

        assertEquals(IntentStatus.SUCCESS, intent.getStatus());
        assertEquals(OrderStatus.PAID, order.getStatus());
        verify(paymentIntentRepository).save(intent);
        verify(orderRepository).save(order);
    }
}
