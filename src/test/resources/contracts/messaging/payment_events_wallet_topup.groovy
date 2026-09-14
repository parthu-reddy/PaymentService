package contracts.messaging

/*
 * payment-events / AD_WALLET_TOPUP_COMPLETED, from WebhookProcessingService:
 *   boolean isWalletTopup = intent.getOrderId().startsWith("WALLET_");
 *   eventType = isWalletTopup ? AD_WALLET_TOPUP_COMPLETED : PAYMENT_COMPLETED;
 *
 * Same flat PaymentSucceededEvent shape as payment_events, but the orderId carries the
 * WALLET_<advertiserId> form that WalletService.TopupEventConsumer splits to recover the entity.
 * A plain PAYMENT_COMPLETED contract cannot exercise that consumer at all.
 */
org.springframework.cloud.contract.spec.Contract.make {
    description("Should publish AD_WALLET_TOPUP_COMPLETED to payment-events")
    label("payment_events_wallet_topup")
    input { triggeredBy('fireWalletTopupCompleted()') }
    outputMessage {
        sentTo('payment-events')
        headers { header('eventType', 'AD_WALLET_TOPUP_COMPLETED') }
        body([
            orderId: $(producer(regex('WALLET_[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'))),
            gatewayOrderId: $(producer(regex('.+'))),
            amount: 250.00,
            gatewayName: "RAZORPAY",
            paymentMethod: "CARD",
            // Instant.now() at publish time, so it cannot be pinned to a literal. Same form as
            // payment_events.groovy, whose paidAt has always been a regex for this reason.
            // Producer side: Instant.now() at publish time, so it cannot be a literal.
            // Consumer side: a FIXED, parseable value. A producer-only regex makes Spring Cloud
            // Contract generate a random matching string for the stub -- and a string matching
            // this pattern need not be a valid Instant (0000-00-00T00:00:00Z does), so binding
            // PaymentSucceededEvent threw and TopupEventConsumer never credited the wallet.
            paidAt: $(producer(regex('\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z')),
                      consumer('2023-01-01T12:00:00Z')),
            eventType: "AD_WALLET_TOPUP_COMPLETED"
        ])
    }
}
