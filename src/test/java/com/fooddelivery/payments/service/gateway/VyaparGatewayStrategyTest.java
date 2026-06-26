package com.fooddelivery.payments.service.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.service.gateway.PaymentRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VyaparGatewayStrategyTest {

    private VyaparGatewayStrategy strategy;
    private ObjectMapper objectMapper;

    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        httpClient = mock(HttpClient.class);
        strategy = new VyaparGatewayStrategy("client-id", "http://api.vyapar.com", "client-secret", httpClient, objectMapper);
    }

    @Test
    void testCreateOrder_ReturnsIntentString() throws Exception {
        PaymentRequestContext context = PaymentRequestContext.builder()
                .internalOrderId(UUID.randomUUID())
                .amountInInr(new BigDecimal("100.50"))
                .customerPhone("9876543210")
                .build();

        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"intent\":\"upi://pay?am=100.50\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

        String intent = strategy.createOrder(context);

        assertNotNull(intent);
        assertTrue(intent.startsWith("upi://pay"));
        assertTrue(intent.contains("am=100.50"));
    }

    @Test
    void testVerifyWebhookSignature_ValidSignature() throws Exception {
        String payload = "{\"event\":\"payment.success\"}";
        String secret = "client-secret";
        
        // Generate valid HMAC
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(secret.getBytes(), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(payload.getBytes());
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        String validSignature = hexString.toString();

        assertTrue(strategy.verifyWebhookSignature(payload, validSignature, null));
    }

    @Test
    void testVerifyWebhookSignature_InvalidSignature() {
        String payload = "{\"event\":\"payment.success\"}";
        String invalidSignature = "invalid_hash_string";

        assertFalse(strategy.verifyWebhookSignature(payload, invalidSignature, null));
    }
}
