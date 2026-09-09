package com.fooddelivery.payments.service.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fooddelivery.common.enums.PaymentGateway;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import org.springframework.context.annotation.Profile;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@Service
@Profile("prod")
@lombok.extern.slf4j.Slf4j
public class RazorpayStrategy implements IPaymentGatewayStrategy {

    private final RazorpayClient razorpayClient;
    private final String rzpKeyId;
    private final String rzpKeySecret;
    private final String webhookSecret;

    @org.springframework.beans.factory.annotation.Autowired
    public RazorpayStrategy(
            @Value("${razorpay.key.id}") String rzpKeyId,
            @Value("${razorpay.key.secret}") String rzpKeySecret,
            @Value("${razorpay.webhook.secret}") String webhookSecret) throws RazorpayException {
        this.razorpayClient = new RazorpayClient(rzpKeyId, rzpKeySecret);
        this.webhookSecret = webhookSecret;
        this.rzpKeyId = rzpKeyId;
        this.rzpKeySecret = rzpKeySecret;
    }

    @Override
    public PaymentGateway getGatewayName() {
        return PaymentGateway.RAZORPAY;
    }

    @Override
    @CircuitBreaker(name = "razorpayGateway", fallbackMethod = "fallbackCreateOrder")
    public String createOrder(PaymentRequestContext context) {
        try {
            // Razorpay processes amounts in the smallest currency subunit (paise)
            int amountInPaise = context.getAmountInInr().multiply(new BigDecimal("100")).intValue();

            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountInPaise);
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", context.getReceiptRef());

            // Embedding internal references aids in manual reconciliation
            JSONObject notes = new JSONObject();
            notes.put("internal_order_id", context.getInternalOrderId().toString());
            orderRequest.put("notes", notes);

            Order razorpayOrder = razorpayClient.orders.create(orderRequest);
            return razorpayOrder.get("id");

        } catch (RazorpayException e) {
            throw new RuntimeException("Razorpay order creation failed: " + e.getMessage(), e);
        }
    }

    public String fallbackCreateOrder(PaymentRequestContext context, Throwable t) {
        log.error("Razorpay Gateway is unavailable, circuit breaker tripped: {}", t.getMessage());
        throw new RuntimeException("503 SERVICE UNAVAILABLE: Razorpay Gateway is currently unreachable.");
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, String timestamp) {
        try {
            return Utils.verifyWebhookSignature(payload, signature, webhookSecret);
        } catch (RazorpayException e) {
            return false;
        }
    }

    @Override
    @CircuitBreaker(name = "razorpayRefund", fallbackMethod = "refundFallback")
    public boolean initiateRefund(String gatewayOrderId, String refundId, java.math.BigDecimal amount, String reason) {
        try {
            // Amount must be in paise (amount * 100)
            int amountInPaise = amount.multiply(new java.math.BigDecimal("100")).intValue();
            
            JSONObject refundRequest = new JSONObject();
            refundRequest.put("amount", amountInPaise);
            refundRequest.put("receipt", refundId);
            
            if (reason != null && !reason.trim().isEmpty()) {
                JSONObject notes = new JSONObject();
                notes.put("reason", reason);
                refundRequest.put("notes", notes);
            }
            
            // GatewayOrderId is the Razorpay order_id. We need to fetch the captured payment for this order to issue a refund.
            java.util.List<com.razorpay.Payment> payments = razorpayClient.orders.fetchPayments(gatewayOrderId);
            String paymentId = null;
            if (payments != null) {
                for (com.razorpay.Payment p : payments) {
                    if ("captured".equals(p.get("status"))) {
                        paymentId = p.get("id");
                        break;
                    }
                }
            }
            
            if (paymentId == null) {
                log.error("Razorpay refund failed: No captured payment found for order: {}", gatewayOrderId);
                return false;
            }
            
            com.razorpay.Refund refund = razorpayClient.payments.refund(paymentId, refundRequest);
            log.info("Razorpay Refund Initiated successfully for Payment: {} (Order: {}) Amount: {} RefundID: {}", paymentId, gatewayOrderId, amount, refund.get("id"));
            return true;
        } catch (RazorpayException e) {
            log.error("Razorpay refund failed for gatewayOrderId: {}. Error: {}", gatewayOrderId, e.getMessage(), e);
            return false;
        }
    }

    public boolean refundFallback(String gatewayOrderId, String refundId, java.math.BigDecimal amount, String reason, Throwable t) {
        log.error("CircuitBreaker fallback triggered for initiateRefund (order: {}, amount: {}). Reason: {}", gatewayOrderId, amount, t.getMessage());
        return false;
    }

    @Override
    public com.fooddelivery.common.constants.PaymentIntentStatus verifyStatus(String gatewayOrderId) {
        try {
            Order order = razorpayClient.orders.fetch(gatewayOrderId);
            String status = order.get("status");
            return mapStatus(status);
        } catch (RazorpayException e) {
            log.error("Failed to verify status with Razorpay for order {}", gatewayOrderId, e);
            return com.fooddelivery.common.constants.PaymentIntentStatus.INITIATED;
        }
    }

    public com.fooddelivery.common.constants.PaymentIntentStatus mapStatus(String status) {
        if ("paid".equalsIgnoreCase(status)) {
            return com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS;
        } else if ("created".equalsIgnoreCase(status) || "attempted".equalsIgnoreCase(status)) {
            return com.fooddelivery.common.constants.PaymentIntentStatus.INITIATED;
        } else {
            return com.fooddelivery.common.constants.PaymentIntentStatus.FAILED;
        }
    }

    /**
     * These strategies only exist under the {@code prod} profile, so this guard is inherently
     * production-only. A blank or placeholder credential must stop the service starting rather than
     * fail on the first real payment.
     */
    @jakarta.annotation.PostConstruct
    public void assertProductionCredentials() {
        requireRealCredential(rzpKeyId, "razorpay.key.id");
        requireRealCredential(rzpKeySecret, "razorpay.key.secret");
        requireRealCredential(webhookSecret, "razorpay.webhook.secret");
    }

    private static void requireRealCredential(String value, String name) {
        // "dev-placeholder-" is what the non-prod profile document in payment-service.yml resolves
        // to. It must never reach a real gateway call, so it is refused here alongside a blank or a
        // test_ key.
        if (value == null || value.isBlank()
                || value.startsWith("test_") || value.startsWith("dev-placeholder-")) {
            throw new IllegalStateException(
                    "Refusing to start: " + name + " is missing or is a placeholder value. "
                    + "Set it from the vault before deploying.");
        }
    }
}
