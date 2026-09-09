package com.fooddelivery.payments.service.gateway;

import com.fooddelivery.common.enums.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

import org.springframework.context.annotation.Profile;

@Service
@Profile("prod")
@lombok.extern.slf4j.Slf4j
public class VyaparGatewayStrategy implements IPaymentGatewayStrategy {

    private final String apiKey;
    private final String baseUrl;
    private final String webhookSecret;
    private final HttpClient httpClient;

    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public VyaparGatewayStrategy(
            @Value("${vyapargateway.api.key}") String apiKey,
            @Value("${vyapargateway.base.url:https://api.vyapargateway.com/v1}") String baseUrl,
            @Value("${vyapargateway.webhook.secret}") String webhookSecret,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.webhookSecret = webhookSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = objectMapper;
    }

    VyaparGatewayStrategy(String apiKey, String baseUrl, String webhookSecret, HttpClient httpClient, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.webhookSecret = webhookSecret;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentGateway getGatewayName() {
        return PaymentGateway.VYAPAR;
    }

    @Override
    @CircuitBreaker(name = "gatewayCB", fallbackMethod = "fallbackCreateOrder")
    public String createOrder(PaymentRequestContext context) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("amount", context.getAmountInInr().doubleValue());
            body.put("client_txn_id", context.getInternalOrderId().toString());
            body.put("customer_mobile", context.getCustomerPhone());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/create_order"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode jsonResponse = objectMapper.readTree(response.body());
                // For Vyapar, the response often contains the intent and potentially a gateway order ID.
                // Assuming it returns an 'order_id' or we use 'intent' string if appropriate.
                // We will return the intent or order_id based on typical gateway flow.
                if (jsonResponse.has("order_id")) {
                    return jsonResponse.get("order_id").asText();
                }
                return jsonResponse.get("intent").asText(); // Returning intent if order_id is missing
            } else {
                log.error("Failed to execute VyaparGateway payload: {}", response.body());
                throw new RuntimeException("Gateway initialization error status " + response.statusCode());
            }
        } catch (Exception e) {
            log.error("Error creating payment intent for internal order: {}", context.getInternalOrderId(), e);
            throw new RuntimeException("Vyapar payment service down", e);
        }
    }
    
    public String fallbackCreateOrder(PaymentRequestContext context, Throwable t) {
        log.error("Vyapar Gateway is unavailable, circuit breaker tripped or call failed: {}", t.getMessage());
        throw new RuntimeException("503 SERVICE UNAVAILABLE: Payment Gateway is currently unreachable.");
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
    @CircuitBreaker(name = "vyaparRefund", fallbackMethod = "refundFallback")
    public boolean initiateRefund(String gatewayOrderId, String refundId, java.math.BigDecimal amount, String reason) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("order_id", gatewayOrderId);
            body.put("amount", amount);
            body.put("idempotency_key", refundId);
            body.put("reason", reason);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/refunds"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.error("Exception thrown when initiating gateway refund for sequence: {}", gatewayOrderId, e);
            throw new RuntimeException("Vyapar API exception during refund", e);
        }
    }

    public boolean refundFallback(String gatewayOrderId, String refundId, java.math.BigDecimal amount, String reason, Throwable t) {
        log.error("CircuitBreaker fallback triggered for initiateRefund (order: {}, amount: {}). Reason: {}", gatewayOrderId, amount, t.getMessage());
        return false;
    }

    @Override
    @CircuitBreaker(name = "gatewayCB", fallbackMethod = "fallbackVerifyStatus")
    public com.fooddelivery.common.constants.PaymentIntentStatus verifyStatus(String gatewayOrderId) {
        log.info("Calling Vyapar API to verify status for order: {}", gatewayOrderId);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/check_order_status"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"order_id\":\"" + gatewayOrderId + "\"}"))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode jsonResponse = objectMapper.readTree(response.body());
                String status = jsonResponse.has("status") ? jsonResponse.get("status").asText() : "";
                return switch (status.toUpperCase()) {
                    case "SUCCESS", "COMPLETED", "CAPTURED" -> com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS;
                    case "PENDING", "CREATED" -> {
                        // PENDING_VERIFICATION (not implemented yet)
                        yield com.fooddelivery.common.constants.PaymentIntentStatus.INITIATED;
                    }
                    case "FAILED", "CANCELLED", "EXPIRED" -> com.fooddelivery.common.constants.PaymentIntentStatus.FAILED;
                    default -> {
                        log.warn("Unknown payment status '{}' for order {}", status, gatewayOrderId);
                        throw new IllegalStateException("Unknown payment status from gateway: " + status);
                    }
                };
            } else {
                log.error("Vyapar status check returned non-200: {} body={}", response.statusCode(), response.body());
                throw new RuntimeException("Gateway status check failed with status " + response.statusCode());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error verifying payment status for order: {}", gatewayOrderId, e);
            throw new RuntimeException("Vyapar payment status verification failed", e);
        }
    }

    public com.fooddelivery.common.constants.PaymentIntentStatus fallbackVerifyStatus(String gatewayOrderId, Throwable t) {
        log.error("Vyapar status check unavailable, circuit breaker tripped for order {}: {}", gatewayOrderId, t.getMessage());
        throw new IllegalStateException("Payment status verification is currently unavailable. Cannot confirm payment.");
    }

    /**
     * These strategies only exist under the {@code prod} profile, so this guard is inherently
     * production-only. A blank or placeholder credential must stop the service starting rather than
     * fail on the first real payment.
     */
    @jakarta.annotation.PostConstruct
    public void assertProductionCredentials() {
        requireRealCredential(apiKey, "vyapargateway.api.key");
        requireRealCredential(webhookSecret, "vyapargateway.webhook.secret");
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
