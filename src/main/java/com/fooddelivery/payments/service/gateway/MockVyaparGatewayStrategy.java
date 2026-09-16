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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
// "local" was missing while its two siblings had it, so under SPRING_PROFILES_ACTIVE=local no
// Vyapar strategy registered at all -- neither this nor the @Profile("prod") real one -- and the
// context failed to satisfy the dependency.
// Never alongside the real strategy: PaymentGatewayOrchestrator keys its map by gateway, so a
// profile list containing both (e.g. "prod,debug") would fail bean construction on a duplicate key.
@Profile("!prod & (debug | dev | default | test | local)")
@lombok.extern.slf4j.Slf4j
public class MockVyaparGatewayStrategy implements IPaymentGatewayStrategy {

    @Value("${vyapargateway.webhook.secret:test_vyapar_webhook_secret}")
    private String webhookSecret;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
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
    public boolean initiateRefund(String gatewayOrderId, String refundId, java.math.BigDecimal amount, String reason) {
        log.info("[DEBUG PROFILE] Mocking Vyapar Gateway initiate refund for gateway order: {}", gatewayOrderId);
        
        scheduler.schedule(() -> {
            try {
                log.info("MockVyaparGatewayStrategy refund scheduled task started for gatewayOrderId: {}", gatewayOrderId);
                
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("amount", amount);
                
                String mockRefundId = refundId;
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
        // PENDING_VERIFICATION
        return com.fooddelivery.common.constants.PaymentIntentStatus.INITIATED;
    }
}
