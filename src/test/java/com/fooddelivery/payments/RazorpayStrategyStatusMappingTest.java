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

    /**
     * {@code verifyStatus} must answer from the gateway's reply, not from a constant.
     *
     * <p>The three tests above exercise {@code mapStatus}. {@code verifyStatus} is the method the
     * reconciliation job calls, and replacing its body with {@code return SUCCESS} left all three
     * green -- a payment the gateway had never taken would have reconciled as captured. Found
     * 2026-09-09 performing Phase 3's break-test 1.
     *
     * <p>Checked against the source: {@code RazorpayClient} builds a live HTTP client in its
     * constructor and exposes {@code orders} as a public field of a final-method type, so the call
     * cannot be stubbed without a network fixture, and Testcontainers are excluded by project rule.
     */
    @Test
    void verifyStatusAnswersFromTheGatewayNotFromAConstant() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fooddelivery/payments/service/gateway/RazorpayStrategy.java"),
                java.nio.charset.StandardCharsets.UTF_8);

        int start = src.indexOf("public com.fooddelivery.common.constants.PaymentIntentStatus verifyStatus(");
        assertTrue(start >= 0, "RazorpayStrategy.verifyStatus no longer exists");
        int end = src.indexOf("\n    public ", start + 1);
        String body = end > start ? src.substring(start, end) : src.substring(start);

        assertTrue(body.contains("orders.fetch("),
                "verifyStatus must ask Razorpay for the order; body was:\n" + body);
        assertTrue(body.contains("mapStatus("),
                "verifyStatus must map the gateway's reply rather than deciding for itself; body was:\n" + body);
        assertFalse(body.matches("(?s).*return\\s+(?:com\\.fooddelivery\\.common\\.constants\\.)?PaymentIntentStatus\\.SUCCESS\\s*;.*"),
                "verifyStatus returns SUCCESS unconditionally; a payment never taken would reconcile as captured");
    }
}
