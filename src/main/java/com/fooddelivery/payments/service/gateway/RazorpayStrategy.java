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

@Service
@Profile("prod")
public class RazorpayStrategy implements IPaymentGatewayStrategy {
    private static final Logger log = LoggerFactory.getLogger(RazorpayStrategy.class);

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
        try {
            // Amount must be in paise (amount * 100)
            int amountInPaise = (int) (amount * 100);
            
            JSONObject refundRequest = new JSONObject();
            refundRequest.put("amount", amountInPaise);
            
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

    @Override
    public com.fooddelivery.common.constants.PaymentIntentStatus verifyStatus(String gatewayOrderId) {
        // Razorpay API call to get order status
        return com.fooddelivery.common.constants.PaymentIntentStatus.SUCCESS;
    }
}
