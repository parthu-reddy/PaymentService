package com.fooddelivery.payments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import com.fooddelivery.payments.service.gateway.RazorpayStrategy;

public class RazorpayStrategyStatusMappingTest {

    private RazorpayStrategy razorpayStrategy;

    @BeforeEach
    void setUp() throws Exception {
        razorpayStrategy = new RazorpayStrategy("dummyId", "dummySecret", "dummyWebhookSecret");
    }

    @Test
    void testVerifyWebhookSignature_Valid() throws Exception {
        String payload = "{\"event\":\"payment.captured\"}";
        String secret = "dummyWebhookSecret";
        
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] computedHashBytes = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : computedHashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        String validSignature = hexString.toString();

        assertTrue(razorpayStrategy.verifyWebhookSignature(payload, validSignature, null));
    }

    @Test
    void testVerifyWebhookSignature_Invalid() {
        String payload = "{\"event\":\"payment.captured\"}";
        String invalidSignature = "invalid_hex_sig";

        assertFalse(razorpayStrategy.verifyWebhookSignature(payload, invalidSignature, null));
    }

    @Test
    void testStatusMapping() {
        assertEquals(com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS, razorpayStrategy.mapStatus("paid"));
        assertEquals(com.fooddelivery.common.constants.PaymentIntentStatus.INITIATED, razorpayStrategy.mapStatus("created"));
        assertEquals(com.fooddelivery.common.constants.PaymentIntentStatus.INITIATED, razorpayStrategy.mapStatus("attempted"));
        assertEquals(com.fooddelivery.common.constants.PaymentIntentStatus.FAILED, razorpayStrategy.mapStatus("failed"));
    }
}
