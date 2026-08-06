package com.fooddelivery.payments.service.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public class CashfreeStrategyTest {

    @InjectMocks
    private CashfreeStrategy strategy;

    private HttpClient httpClientMock;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(strategy, "cfClientId", "test_id");
        ReflectionTestUtils.setField(strategy, "cfClientSecret", "test_secret");
        
        httpClientMock = mock(HttpClient.class);
        ReflectionTestUtils.setField(strategy, "httpClient", httpClientMock);
    }

    @Test
    void testGetName() {
        assertEquals(com.fooddelivery.common.enums.PaymentGateway.CASHFREE, strategy.getGatewayName());
    }

    @Test
    void testCreateOrder() throws Exception {
        HttpResponse<String> httpResponseMock = (HttpResponse<String>) mock(HttpResponse.class);
        lenient().when(httpResponseMock.body()).thenReturn("{\"order_id\":\"cf_ord_123\"}");
        lenient().when(httpClientMock.<String>send(any(HttpRequest.class), any())).thenReturn(httpResponseMock);

        PaymentRequestContext context = PaymentRequestContext.builder()
                .amountInInr(new BigDecimal("100.00"))
                .internalOrderId(java.util.UUID.randomUUID().toString())
                .receiptRef("rcpt_1")
                .customerPhone("9876543210")
                .build();

        String gatewayOrderId = strategy.createOrder(context);
        assertTrue(gatewayOrderId.startsWith("order_"));
    }
}
