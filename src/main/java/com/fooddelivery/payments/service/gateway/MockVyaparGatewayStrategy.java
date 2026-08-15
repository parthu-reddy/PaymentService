package com.fooddelivery.payments.service.gateway;

import com.fooddelivery.common.enums.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@Service
@Profile({"debug", "dev", "default", "test"})
@lombok.extern.slf4j.Slf4j
public class MockVyaparGatewayStrategy implements IPaymentGatewayStrategy {

    @Value("${vyapargateway.webhook.secret:test_vyapar_webhook_secret}")
    private String webhookSecret;
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @PreDestroy
    public void cleanup() {
        scheduler.shutdown();
    }

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.fooddelivery.payments.service.WebhookProcessingService webhookService;

    @Override
    public PaymentGateway getGatewayName() {
        return PaymentGateway.VYAPAR;
    }

    @Override
    public String createOrder(PaymentRequestContext context) {
        String gatewayOrderId = "mock_vyapar_txn_" + context.getInternalOrderId();
        log.info("[DEBUG PROFILE] Mocking Vyapar Gateway create order for internal order: {}", context.getInternalOrderId());
        
        scheduler.schedule(() -> {
            try {
                log.info("MockVyaparGatewayStrategy scheduled task started for gatewayOrderId: {}", gatewayOrderId);
                log.info("Calling webhookService.handleSuccessfulPayment for gatewayOrderId: {}", gatewayOrderId);
                webhookService.handleSuccessfulPayment(gatewayOrderId);
                log.info("Successfully called webhookService.handleSuccessfulPayment for gatewayOrderId: {}", gatewayOrderId);
            } catch (Throwable e) {
                log.error("Error auto-triggering payment success in MockVyaparGatewayStrategy task", e);
            }
        }, 2, TimeUnit.SECONDS);

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
        log.info("[DEBUG PROFILE] Mocking Vyapar Gateway initiate refund for gateway order: {}", gatewayOrderId);
        
        scheduler.schedule(() -> {
            try {
                log.info("MockVyaparGatewayStrategy refund scheduled task started for gatewayOrderId: {}", gatewayOrderId);
                
                com.fasterxml.jackson.databind.node.ObjectNode payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                payload.put("amount_refunded", amount);
                
                String mockRefundId = "mock_rfnd_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                log.info("Calling webhookService.handleRefundSuccess for gatewayOrderId: {} with refundId: {}", gatewayOrderId, mockRefundId);
                webhookService.handleRefundSuccess(gatewayOrderId, mockRefundId, payload);
                log.info("Successfully called webhookService.handleRefundSuccess for gatewayOrderId: {}", gatewayOrderId);
            } catch (Throwable e) {
                log.error("Error auto-triggering refund success in MockVyaparGatewayStrategy task", e);
            }
        }, 2, TimeUnit.SECONDS);

        return true;
    }

    @Override
    public com.fooddelivery.common.constants.PaymentIntentStatus verifyStatus(String gatewayOrderId) {
        return com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS;
    }
}
