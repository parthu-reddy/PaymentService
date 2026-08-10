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
@Profile({"debug", "dev", "default", "test", "local"})
public class MockRazorpayStrategy implements IPaymentGatewayStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MockRazorpayStrategy.class);
    
    @Value("${razorpay.webhook.secret:test_rzp_webhook_secret}")
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
        return PaymentGateway.RAZORPAY;
    }

    @Override
    public String createOrder(PaymentRequestContext context) {
        String gatewayOrderId = "mock_rzp_txn_" + context.getInternalOrderId();
        logger.info("[DEBUG PROFILE] Mocking Razorpay Gateway create order for internal order: {}", context.getInternalOrderId());
        
        scheduler.schedule(() -> {
            try {
                logger.info("MockRazorpayStrategy scheduled task started for gatewayOrderId: {}", gatewayOrderId);
                logger.info("Calling webhookService.handleSuccessfulPayment for gatewayOrderId: {}", gatewayOrderId);
                webhookService.handleSuccessfulPayment(gatewayOrderId);
                logger.info("Successfully called webhookService.handleSuccessfulPayment for gatewayOrderId: {}", gatewayOrderId);
            } catch (Throwable e) {
                logger.error("Error auto-triggering payment success in MockRazorpayStrategy task", e);
            }
        }, 2, TimeUnit.SECONDS);

        return gatewayOrderId;
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, String timestamp) {
        return true; // Simplified for mock
    }

    @Override
    public boolean initiateRefund(String gatewayOrderId, double amount, String reason) {
        logger.info("[DEBUG PROFILE] Mocking Razorpay Gateway initiate refund for gateway order: {}", gatewayOrderId);
        
        scheduler.schedule(() -> {
            try {
                logger.info("MockRazorpayStrategy refund scheduled task started for gatewayOrderId: {}", gatewayOrderId);
                
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("amount_refunded", amount);
                
                String mockRefundId = "mock_rfnd_" + java.util.UUID.randomUUID().toString().substring(0, 8);
                logger.info("Calling webhookService.handleRefundSuccess for gatewayOrderId: {} with refundId: {}", gatewayOrderId, mockRefundId);
                webhookService.handleRefundSuccess(gatewayOrderId, mockRefundId, payload);
                logger.info("Successfully called webhookService.handleRefundSuccess for gatewayOrderId: {}", gatewayOrderId);
            } catch (Throwable e) {
                logger.error("Error auto-triggering refund success in MockRazorpayStrategy task", e);
            }
        }, 2, TimeUnit.SECONDS);

        return true;
    }

    @Override
    public com.fooddelivery.common.constants.PaymentIntentStatus verifyStatus(String gatewayOrderId) {
        return com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS;
    }
}
