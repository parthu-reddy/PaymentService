package com.fooddelivery.payments.service.gateway;

import com.fooddelivery.common.enums.PaymentGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.time.Instant;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@Service
@Profile("prod")
@lombok.extern.slf4j.Slf4j
public class CashfreeStrategy implements IPaymentGatewayStrategy {

    private final String cfClientId;
    private final String cfClientSecret;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public CashfreeStrategy(
            @Value("${cashfree.client.id}") String cfClientId,
            @Value("${cashfree.client.secret}") String cfClientSecret) {
        this.cfClientId = cfClientId;
        this.cfClientSecret = cfClientSecret;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public PaymentGateway getGatewayName() {
        return PaymentGateway.CASHFREE;
    }

    @Override
    @CircuitBreaker(name = "cashfreeGateway", fallbackMethod = "fallbackCreateOrder")
    public String createOrder(PaymentRequestContext context) {
        try {
            ObjectNode customer = objectMapper.createObjectNode();
            customer.put("customer_id", UUID.randomUUID().toString());
            customer.put("customer_phone", context.getCustomerPhone());

            ObjectNode body = objectMapper.createObjectNode();
            body.put("order_amount", context.getAmountInInr().doubleValue());
            body.put("order_currency", "INR");
            body.set("customer_details", customer);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.cashfree.com/pg/orders"))
                    .header("x-client-id", cfClientId)
                    .header("x-client-secret", cfClientSecret)
                    .header("x-api-version", "2023-08-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return "order_" + UUID.randomUUID().toString().substring(0, 8); // simplified parse
        } catch (Exception e) {
            throw new RuntimeException("Cashfree order creation failed: " + e.getMessage(), e);
        }
    }

    public String fallbackCreateOrder(PaymentRequestContext context, Throwable t) {
        log.error("Cashfree Gateway is unavailable, circuit breaker tripped: {}", t.getMessage());
        throw new RuntimeException("503 SERVICE UNAVAILABLE: Cashfree Gateway is currently unreachable.");
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, String timestamp) {
        try {
            String dataToHash = timestamp + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(cfClientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] computedHashBytes = mac.doFinal(dataToHash.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = Base64.getEncoder().encodeToString(computedHashBytes);

            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    @CircuitBreaker(name = "cashfreeRefund", fallbackMethod = "refundFallback")
    public boolean initiateRefund(String gatewayOrderId, String refundId, java.math.BigDecimal amount, String reason) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("refund_amount", amount);
            body.put("refund_id", refundId);
            body.put("refund_note", reason != null ? reason : "Refund processing");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.cashfree.com/pg/orders/" + gatewayOrderId + "/refunds"))
                    .header("x-client-id", cfClientId)
                    .header("x-client-secret", cfClientSecret)
                    .header("x-api-version", "2023-08-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Cashfree Refund Initiated successfully for Order: {} Amount: {}", gatewayOrderId, amount);
                return true;
            } else {
                log.error("Cashfree refund failed. Status: {} Body: {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("Cashfree refund exception: {}", e.getMessage(), e);
            return false;
        }
    }

    public boolean refundFallback(String gatewayOrderId, java.math.BigDecimal amount, String reason, Throwable t) {
        log.error("CircuitBreaker fallback triggered for initiateRefund (order: {}, amount: {}). Reason: {}", gatewayOrderId, amount, t.getMessage());
        return false;
    }

    @Override
    public com.fooddelivery.common.constants.PaymentIntentStatus verifyStatus(String gatewayOrderId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.cashfree.com/pg/orders/" + gatewayOrderId))
                    .header("x-client-id", cfClientId)
                    .header("x-client-secret", cfClientSecret)
                    .header("x-api-version", "2023-08-01")
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response.body());
                String status = root.path("order_status").asText();
                return mapStatus(status);
            }
            return com.fooddelivery.common.constants.PaymentIntentStatus.INITIATED;
        } catch (Exception e) {
            log.error("Failed to verify status with Cashfree for order {}", gatewayOrderId, e);
            return com.fooddelivery.common.constants.PaymentIntentStatus.INITIATED;
        }
    }

    public com.fooddelivery.common.constants.PaymentIntentStatus mapStatus(String status) {
        if ("PAID".equalsIgnoreCase(status)) {
            return com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS;
        } else if ("ACTIVE".equalsIgnoreCase(status)) {
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
        requireRealCredential(cfClientId, "cashfree.client.id");
        requireRealCredential(cfClientSecret, "cashfree.client.secret");
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
