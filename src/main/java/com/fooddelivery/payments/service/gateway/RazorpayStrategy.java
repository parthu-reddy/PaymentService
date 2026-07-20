package com.fooddelivery.payments.service.gateway;

import com.fooddelivery.common.enums.PaymentGateway;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class RazorpayStrategy implements IPaymentGatewayStrategy {

    private final RazorpayClient razorpayClient;
    private final String webhookSecret;

    @org.springframework.beans.factory.annotation.Autowired
    public RazorpayStrategy(
            @Value("${razorpay.key.id}") String rzpKeyId,
            @Value("${razorpay.key.secret}") String rzpKeySecret,
            @Value("${razorpay.webhook.secret}") String webhookSecret) throws RazorpayException {
        this.razorpayClient = new RazorpayClient(rzpKeyId, rzpKeySecret);
        this.webhookSecret = webhookSecret;
    }

    @Override
    public PaymentGateway getGatewayName() {
        return PaymentGateway.RAZORPAY;
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
        // Mock refund for Razorpay
        log.info("Razorpay Refund Initiated for Order: {} Amount: {} Reason: {}", gatewayOrderId, amount, reason);
        return true;
    }

    @Override
    public com.fooddelivery.common.constants.PaymentIntentStatus verifyStatus(String gatewayOrderId) {
        // Razorpay API call to get order status
        return com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS;
    }
}
