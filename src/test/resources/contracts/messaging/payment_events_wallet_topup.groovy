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
            eventType: "AD_WALLET_TOPUP_COMPLETED"
        ])
    }
}
