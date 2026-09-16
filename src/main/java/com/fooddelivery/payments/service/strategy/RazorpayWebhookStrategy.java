package com.fooddelivery.payments.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
@lombok.extern.slf4j.Slf4j
public class RazorpayWebhookStrategy implements WebhookHandlerStrategy {

    private static final String EVENT_ORDER_PAID = "order.paid";
    private static final String EVENT_PAYMENT_FAILED = "payment.failed";
    private static final String EVENT_REFUND_PROCESSED = "refund.processed";
    private static final String EVENT_REFUND_FAILED = "refund.failed";

    @Override
    public com.fooddelivery.common.enums.PaymentGateway getSupportedGateway() {
        return com.fooddelivery.common.enums.PaymentGateway.RAZORPAY;
    }

    @Override
    public void handleEvent(String eventType, JsonNode rootNode, PaymentActionDelegate delegate) {
        if (EVENT_REFUND_PROCESSED.equals(eventType)) {
            // For refund.processed, order_id might be inside payload.refund.entity.payment_id or similar, 
            // but we need the gatewayOrderId which is the order_id. 
            // wait, if we used payment_id for refund, we don't have order_id here easily.
            // Let me extract payment_id and maybe we need to find intent by payment_id?
            // Actually, we must make sure how we can find it.
            // Let's pass the payload and let the service handle it, or we extract payment_id.
            String refundId = rootNode.path("payload").path("refund").path("entity").path("id").asText();
            // Since our intent stores order_id, we can't look up by payment_id easily unless we added it.
            // Actually, does payload.refund.entity contain order_id? No, but maybe payload.payment.entity.order_id?
            // Yes, refund event contains payment entity too!
            // payload.payment.entity.order_id
            String gatewayOrderId = rootNode.path("payload").path("payment").path("entity").path("order_id").asText();
            
            // Fix the amount to be passed to delegate since Razorpay uses paise.
            // But delegate expects standard amount. 
            // wait, delegate expects the original payload and extracts amount_refunded/amount.
            // wait, delegate expects the original payload and extracts amount_refunded/amount.
            
            java.math.BigDecimal amountInPaise = rootNode.path("payload").path("refund").path("entity").path("amount").decimalValue();
            com.fasterxml.jackson.databind.node.ObjectNode syntheticPayload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            syntheticPayload.put("amount_refunded", amountInPaise.divide(new java.math.BigDecimal("100")));
            
            if (gatewayOrderId != null && !gatewayOrderId.isEmpty()) {
                delegate.handleRefundSuccess(gatewayOrderId, refundId, syntheticPayload);
            } else {
                log.warn("RAZORPAY_REFUND_EVENT_INVALID refundId={} reason=missing-gateway-order-id", refundId);
            }
        } else if (EVENT_REFUND_FAILED.equals(eventType)) {
            String gatewayOrderId = rootNode.path("payload").path("payment").path("entity").path("order_id").asText(null);
            if (gatewayOrderId != null) {
                String refundId = rootNode.path("payload").path("refund").path("entity").path("id").asText();
                String reason = rootNode.path("payload").path("refund").path("entity").path("error_description").asText("Refund failed at Razorpay");
                delegate.handleRefundFailure(gatewayOrderId, refundId, reason);
            }
        } else {
            String gatewayOrderId = rootNode.path("payload").path("payment").path("entity").path("order_id").asText();
            if (gatewayOrderId != null && !gatewayOrderId.isEmpty()) {
                if (EVENT_ORDER_PAID.equals(eventType)) {
                    java.math.BigDecimal amountInPaise = rootNode.path("payload").path("payment").path("entity").path("amount").decimalValue();
                    java.math.BigDecimal paidAmount = amountInPaise.divide(new java.math.BigDecimal("100"));
                    delegate.handleSuccessfulPayment(gatewayOrderId, paidAmount);
                } else if (EVENT_PAYMENT_FAILED.equals(eventType)) {
                    delegate.handleFailedPayment(gatewayOrderId, "Razorpay payment failed");
                }
            }
        }
    }
}
