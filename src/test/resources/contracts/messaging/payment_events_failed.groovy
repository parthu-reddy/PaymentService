package contracts.messaging

/*
 * payment-events: a gateway declining a payment.
 *
 * Published by WebhookProcessingService from a real PaymentFailedEvent, and consumed by
 * CustomerApplication's PaymentEventConsumer, which fails the order.
 *
 * failureReason is the only field carrying WHY, and it is what the customer is eventually shown.
 * Dropping it would leave an order failed with no explanation anywhere in the system.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("Should publish PAYMENT_FAILED to payment-events when a gateway declines")
    label("payment_events_failed")
    input { triggeredBy('firePaymentFailed()') }
    outputMessage {
        sentTo('payment-events')
        headers { header('eventType', 'PAYMENT_FAILED') }
        body([
            eventType: "PAYMENT_FAILED",
            orderId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            gatewayOrderId: $(producer(regex('.+'))),
            gatewayName: "RAZORPAY",
            failureReason: $(producer(regex('.+')))
        ])
    }
}
