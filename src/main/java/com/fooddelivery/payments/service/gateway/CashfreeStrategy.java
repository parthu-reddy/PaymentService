package com.fooddelivery.payments.service.gateway;

import com.cashfree.pg.Cashfree;
import com.cashfree.pg.models.ApiResponse;
import com.cashfree.pg.models.CreateOrderRequest;
import com.cashfree.pg.models.CustomerDetails;
import com.cashfree.pg.models.OrderEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.UUID;
import java.time.Instant;

@Service
public class CashfreeStrategy implements PaymentGatewayStrategy {

    private final String cfClientSecret;

    public CashfreeStrategy(
            @Value("${cashfree.client.id}") String cfClientId,
            @Value("${cashfree.client.secret}") String cfClientSecret) {
        this.cfClientSecret = cfClientSecret;
        
        Cashfree.XClientId = cfClientId;
        Cashfree.XClientSecret = cfClientSecret;
        Cashfree.XEnvironment = Cashfree.Environment.PRODUCTION;
    }

    @Override
    public String getGatewayName() {
        return "CASHFREE";
    }

    @Override
    public String createOrder(PaymentRequestContext context) {
        try {
            CustomerDetails customer = new CustomerDetails();
            customer.setCustomerId(UUID.randomUUID().toString());
            customer.setCustomerPhone(context.getCustomerPhone());

            CreateOrderRequest request = new CreateOrderRequest();
            request.setOrderAmount(context.getAmountInInr().doubleValue());
            request.setOrderCurrency("INR");
            request.setCustomerDetails(customer);

            String apiVersion = "2023-08-01";
            ApiResponse<OrderEntity> response = Cashfree.PGCreateOrder(apiVersion, request, null, null, null);

            return response.getData().getOrderId();
        } catch (Exception e) {
            throw new RuntimeException("Cashfree order creation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return false;
        }

        // Verify time window (e.g., 5 minutes) to prevent replay attacks
        long currentTimestamp = Instant.now().toEpochMilli();
        long webhookTimestamp;
        try {
            webhookTimestamp = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }

        // timestamp might be in seconds or ms, let's assume ms based on standard, but if it's seconds we should multiply by 1000
        // Cashfree timestamp is epoch in milliseconds usually
        if (currentTimestamp - webhookTimestamp > 300000) { // 5 minutes in milliseconds
            return false; 
        }

        try {
            String dataToSign = timestamp + payload;
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(cfClientSecret.getBytes(), "HmacSHA256");
            sha256_HMAC.init(secret_key);

            byte[] hash = sha256_HMAC.doFinal(dataToSign.getBytes());
            String expectedSignature = Base64.getEncoder().encodeToString(hash);

            return expectedSignature.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
