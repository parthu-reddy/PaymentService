package com.fooddelivery.payments.service.gateway;

import com.fooddelivery.common.enums.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@Service
// Never alongside the real strategy: PaymentGatewayOrchestrator keys its map by gateway, so a
// profile list containing both (e.g. "prod,debug") would fail bean construction on a duplicate key.
@Profile("!prod & (debug | dev | default | test | local)")
@lombok.extern.slf4j.Slf4j
public class MockCashfreeStrategy implements IPaymentGatewayStrategy {

    @Value("${cashfree.webhook.secret:test_cf_webhook_secret}")
    private String webhookSecret;
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @PreDestroy
    public void cleanup() {
        scheduler.shutdown();
    }

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.fooddelivery.payments.service.WebhookProcessingService webhookService;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper objectMapper;

    @Override
    public PaymentGateway getGatewayName() {
        return PaymentGateway.CASHFREE;
    }

    @Override
    public String createOrder(PaymentRequestContext context) {
        String gatewayOrderId = "mock_cf_txn_" + context.getInternalOrderId();
        log.info("[DEBUG PROFILE] Mocking Cashfree Gateway create order for internal order: {}", context.getInternalOrderId());
        
        return gatewayOrderId;
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, String timestamp) {
        return true; // Simplified for mock
    }

    @Override
    public boolean initiateRefund(String gatewayOrderId, String refundId, java.math.BigDecimal amount, String reason) {
        log.info("[DEBUG PROFILE] Mocking Cashfree Gateway initiate refund for gateway order: {}", gatewayOrderId);
        
        scheduler.schedule(() -> {
            try {
                log.info("MockCashfreeStrategy refund scheduled task started for gatewayOrderId: {}", gatewayOrderId);
                
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("amount_refunded", amount);
                
                String mockRefundId = refundId;
                log.info("Calling webhookService.handleRefundSuccess for gatewayOrderId: {} with refundId: {}", gatewayOrderId, mockRefundId);
                webhookService.handleRefundSuccess(gatewayOrderId, mockRefundId, payload);
                log.info("Successfully called webhookService.handleRefundSuccess for gatewayOrderId: {}", gatewayOrderId);
            } catch (Throwable e) {
                log.error("Error auto-triggering refund success in MockCashfreeStrategy task", e);
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
