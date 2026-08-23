package contracts.messaging

/*
 * Real payload for payment-events, from WebhookProcessingService:
 * objectMapper.valueToTree(PaymentSucceededEvent) with an eventType field added, published via the
 * outbox (PAYMENT aggregate, key = intent.getOrderId()).
 *
 * PaymentSucceededEvent is a record of (orderId, gatewayOrderId, amount, gatewayName); amount is a
 * BigDecimal and therefore a JSON number here -- unlike wallet/ledger events, where it is a string.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("Should publish PAYMENT_COMPLETED to payment-events")
    label("payment_events")
    input { triggeredBy('firePaymentSuccess()') }
    outputMessage {
        sentTo('payment-events')
        headers {
            header('eventType', 'PAYMENT_COMPLETED')
            // OutboxProcessor publishes the outbox row id as eventId; consumers key their
            // idempotency on it and reject a message without it.
            header('eventId', $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}')), consumer('7a1d5e90-3c22-4b6f-8a11-9d4c2e77b501')))
        }
        body([
            orderId: $(producer(regex('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            gatewayOrderId: $(producer(regex('.+'))),
            amount: 250.00,
            gatewayName: "RAZORPAY",
            eventType: "PAYMENT_COMPLETED"
        ])
    }
}
