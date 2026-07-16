package com.fooddelivery.payments.service.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
@Profile("debug")
public class MockVyaparGatewayStrategy implements IPaymentGatewayStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MockVyaparGatewayStrategy.class);
    
    @Value("${vyapargateway.webhook.secret:test_vyapar_webhook_secret}")
    private String webhookSecret;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.fooddelivery.payments.service.WebhookProcessingService webhookService;

    @Override
    public String getGatewayName() {
        return "VYAPAR";
    }

    @Override
    public String createOrder(PaymentRequestContext context) {
        String gatewayOrderId = "mock_vyapar_txn_" + context.getInternalOrderId();
        logger.info("[DEBUG PROFILE] Mocking Vyapar Gateway create order for internal order: {}", context.getInternalOrderId());
        
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait 2s to simulate network delay and allow intent to be saved
                webhookService.handleSuccessfulPayment(gatewayOrderId);
            } catch (Exception e) {
                logger.error("Error auto-triggering payment success", e);
            }
        }).start();

        return gatewayOrderId;
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, String timestamp) {
        try {
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] computedHashBytes = mac.doFinal(payloadBytes);

            StringBuilder hexString = new StringBuilder();
            for (byte b : computedHashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return MessageDigest.isEqual(
                hexString.toString().getBytes(StandardCharsets.UTF_8), 
                signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean initiateRefund(String gatewayOrderId, double amount, String reason) {
        logger.info("[DEBUG PROFILE] Mocking Vyapar Gateway initiate refund for gateway order: {}", gatewayOrderId);
        return true;
    }

    @Override
    public String verifyStatus(String gatewayOrderId) {
        return com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS;
    }
}
