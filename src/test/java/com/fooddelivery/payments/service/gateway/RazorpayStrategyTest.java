package com.fooddelivery.payments.service.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import com.razorpay.RazorpayClient;
import com.razorpay.OrderClient;
import com.razorpay.Order;
import org.json.JSONObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RazorpayStrategyTest {

    private RazorpayStrategy strategy;
    private RazorpayClient razorpayClientMock;
    private OrderClient orderClientMock;

    @BeforeEach
    void setUp() throws Exception {
        strategy = new RazorpayStrategy("fake_key", "fake_secret", "webhook_secret");
        
        razorpayClientMock = mock(RazorpayClient.class);
        orderClientMock = mock(OrderClient.class);
        razorpayClientMock.orders = orderClientMock;
        
        ReflectionTestUtils.setField(strategy, "razorpayClient", razorpayClientMock);
    }

    @Test
    void testGetName() {
        assertEquals(com.fooddelivery.common.enums.PaymentGateway.RAZORPAY, strategy.getGatewayName());
    }

    @Test
    void testCreateOrder() throws Exception {
        Order mockOrder = new Order(new JSONObject("{\"id\":\"rzp_ord_123\"}"));
        when(orderClientMock.create(any(JSONObject.class))).thenReturn(mockOrder);

        PaymentRequestContext context = PaymentRequestContext.builder()
                .amountInInr(new BigDecimal("100.00"))
                .internalOrderId(java.util.UUID.randomUUID())
                .receiptRef("rcpt_1")
                .customerPhone("9876543210")
                .build();

        String gatewayOrderId = strategy.createOrder(context);
        assertEquals("rzp_ord_123", gatewayOrderId);
    }
}
