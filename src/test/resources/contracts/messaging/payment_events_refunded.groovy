package contracts.messaging

/*
 * payment-events: a gateway refund completing.
 *
 * Published by WebhookProcessingService and consumed by CustomerApplication's PaymentEventConsumer,
 * which closes out the refund on the order.
 *
 * Added 2026-09-12. payment-events carried contracts for PAYMENT_COMPLETED and
 * AD_WALLET_TOPUP_COMPLETED only -- none of the three events that consumer actually acts on.
 *
 * isSuccess is the field it branches on, and gatewayRefundId is what a customer disputing a refund
 * is asked for. Note that WalletService's RefundCreditConsumer also publishes PAYMENT_REFUNDED here
 * for a store-credit refund, carrying orderId and gatewayOrderId for the same consumer -- two
 * producers, one event type, which is why both keep those two fields.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("Should publish PAYMENT_REFUNDED to payment-events when a gateway refund completes")
    label("payment_events_refunded")
    input { triggeredBy('firePaymentRefunded()') }
    outputMessage {
        sentTo('payment-events')
        headers { header('eventType', 'PAYMENT_REFUNDED') }
        body([
            eventType: "PAYMENT_REFUNDED",
            orderId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            gatewayOrderId: $(producer(regex('.+'))),
            amountRefunded: 15.50,
            gatewayName: "RAZORPAY",
            refundDestination: "ORIGINAL_METHOD",
            refundId: $(producer(regex('.+'))),
            gatewayRefundId: $(producer(regex('.+'))),
            status: "COMPLETED",
            isSuccess: true
        ])
    }
}
