package com.fooddelivery.payments.service.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.PaymentRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class VyaparGatewayStrategyTest {

    private VyaparGatewayStrategy strategy;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        strategy = new VyaparGatewayStrategy(objectMapper);
    }

    @Test
    void testCreateOrder_ReturnsIntentString() {
        PaymentRequestContext context = new PaymentRequestContext();
        context.setInternalOrderId(UUID.randomUUID().toString());
        context.setAmountInInr(new BigDecimal("150.00"));

        String intent = strategy.createOrder(context);

        assertNotNull(intent);
        assertTrue(intent.startsWith("upi://pay"));
        assertTrue(intent.contains("am=150.00"));
    }

    @Test
    void testVerifyWebhookSignature_ValidSignature() throws Exception {
        String payload = "{\"event\":\"payment.success\"}";
        String secret = "TEST_VYAPAR_SECRET"; // hardcoded in strategy for now
        
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
