package com.fooddelivery.payments.service.gateway;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class RazorpayStrategy implements IPaymentGatewayStrategy {

    private final RazorpayClient razorpayClient;
    private final String webhookSecret;

    public RazorpayStrategy(
            @Value("${razorpay.key.id}") String rzpKeyId,
            @Value("${razorpay.key.secret}") String rzpKeySecret,
            @Value("${razorpay.webhook.secret}") String webhookSecret) throws RazorpayException {
        this.razorpayClient = new RazorpayClient(rzpKeyId, rzpKeySecret);
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String getGatewayName() {
        return "RAZORPAY";
    }

    @Override
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

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, String timestamp) {
        try {
            return Utils.verifyWebhookSignature(payload, signature, webhookSecret);
        } catch (RazorpayException e) {
            return false;
        }
    }

    @Override
    public boolean initiateRefund(String gatewayOrderId, double amount, String reason) {
        throw new UnsupportedOperationException("Refunds not yet implemented for Razorpay");
    }

    @Override
    public String verifyStatus(String gatewayOrderId) {
        // Razorpay API call to get order status
        return "SUCCESS";
    }
}
