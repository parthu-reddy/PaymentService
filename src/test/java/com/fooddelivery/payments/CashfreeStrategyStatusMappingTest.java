package com.fooddelivery.payments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

import com.fooddelivery.payments.service.gateway.CashfreeStrategy;

public class CashfreeStrategyStatusMappingTest {

    private CashfreeStrategy cashfreeStrategy;

    @BeforeEach
    void setUp() {
        cashfreeStrategy = new CashfreeStrategy("dummyId", "dummySecret");
    }

    @Test
    void testVerifyWebhookSignature_Valid() throws Exception {
        // Prepare dummy payload
        String timestamp = "123456789";
        String payload = "{\"orderId\":\"test_1\"}";
        String dataToHash = timestamp + payload;
        
        // Generate valid signature manually
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec("dummySecret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] computedHashBytes = mac.doFinal(dataToHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String validSignature = java.util.Base64.getEncoder().encodeToString(computedHashBytes);

        assertTrue(cashfreeStrategy.verifyWebhookSignature(payload, validSignature, timestamp));
    }

    @Test
    void testVerifyWebhookSignature_Invalid() {
        String timestamp = "123456789";
        String payload = "{\"orderId\":\"test_1\"}";
        String invalidSignature = "invalid_base64_sig";

        assertFalse(cashfreeStrategy.verifyWebhookSignature(payload, invalidSignature, timestamp));
    }

    @Test
    void testStatusMapping() {
        assertEquals(com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS, cashfreeStrategy.mapStatus("PAID"));
        assertEquals(com.fooddelivery.common.constants.PaymentIntentStatus.INITIATED, cashfreeStrategy.mapStatus("ACTIVE"));
        assertEquals(com.fooddelivery.common.constants.PaymentIntentStatus.FAILED, cashfreeStrategy.mapStatus("EXPIRED"));
        assertEquals(com.fooddelivery.common.constants.PaymentIntentStatus.FAILED, cashfreeStrategy.mapStatus("UNKNOWN"));
    }
}
