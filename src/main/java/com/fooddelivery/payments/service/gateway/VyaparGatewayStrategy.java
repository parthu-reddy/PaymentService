package com.fooddelivery.payments.service.gateway;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

@Service
public class VyaparGatewayStrategy implements IPaymentGatewayStrategy {

    private static final Logger logger = LoggerFactory.getLogger(VyaparGatewayStrategy.class);

    private final String apiKey;
    private final String baseUrl;
    private final String webhookSecret;
    private final HttpClient httpClient;

    public VyaparGatewayStrategy(
            @Value("${vyapargateway.api.key}") String apiKey,
            @Value("${vyapargateway.base.url:https://api.vyapargateway.com/v1}") String baseUrl,
            @Value("${vyapargateway.webhook.secret}") String webhookSecret) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.webhookSecret = webhookSecret;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String getGatewayName() {
        return "VYAPAR";
    }

    @Override
    public String createOrder(PaymentRequestContext context) {
        try {
            JSONObject body = new JSONObject();
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
                JSONObject jsonResponse = new JSONObject(response.body());
                // For Vyapar, the response often contains the intent and potentially a gateway order ID.
                // Assuming it returns an 'order_id' or we use 'intent' string if appropriate.
                // We will return the intent or order_id based on typical gateway flow.
                if (jsonResponse.has("order_id")) {
                    return jsonResponse.getString("order_id");
                }
                return jsonResponse.getString("intent"); // Returning intent if order_id is missing
            } else {
                logger.error("Failed to execute VyaparGateway payload: {}", response.body());
                throw new RuntimeException("Gateway initialization error status " + response.statusCode());
            }
        } catch (Exception e) {
            logger.error("Error creating payment intent for internal order: {}", context.getInternalOrderId(), e);
            throw new RuntimeException("Vyapar payment service down", e);
        }
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
            
            // Mitigate timing vulnerability attacks via safe array comparisons
            return MessageDigest.isEqual(
                    hexString.toString().getBytes(StandardCharsets.UTF_8), 
                    signature.toLowerCase().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean initiateRefund(String gatewayOrderId, double amount, String reason) {
        try {
            JSONObject body = new JSONObject();
            body.put("order_id", gatewayOrderId);
            body.put("amount", amount);
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
            logger.error("Exception thrown when initiating gateway refund for sequence: {}", gatewayOrderId, e);
            return false;
        }
    }
}
