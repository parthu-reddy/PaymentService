# Payment Gateway Integration

The Payment Service handles all interactions with external payment providers (e.g., Stripe, Razorpay, Vyapar). It provides a generic interface that abstracts away gateway-specific implementation details.

## Responsibilities

1. **Payment Intent Creation**: Generates payment URLs or client secrets for the frontend.
2. **Webhook Processing**: Securely receives async webhooks from external gateways when payments succeed, fail, or refund.
3. **Idempotency & Security**: Uses `IdempotencyFilter` to prevent double-charging and `RequestCachingFilter` (from CommonLibrary) to allow HMAC signature verification on incoming webhook payloads.

## System Flow & Webhooks

```mermaid
sequenceDiagram
    participant API as PaymentGatewayController
    participant Webhook as WebhookController
    participant Strategy as Gateway Strategy
    participant DB as Payment DB
    participant K as Kafka (payment-events)

    API->>Strategy: Create Payment Intent
    Strategy-->>API: Gateway URL
    
    note over Webhook,Strategy: User pays on external gateway
    
    Strategy->>Webhook: Async Webhook Delivery
    Webhook->>Webhook: Verify HMAC Signature (CachedBody)
    Webhook->>DB: Save WebhookDelivery (Idempotent)
    Webhook->>DB: Update Transaction Status (SUCCESS)
    Webhook->>K: Publish PAYMENT_SUCCESS
```

## Setup & Configuration

1. Connects to `payment_db`.
2. Requires `CommonLibrary` for the custom request wrapping filters and standard event serialization.
3. Start using `mvn spring-boot:run`. Flyway initializes the `payment_intents`, `transactions`, `refunds`, and `webhook_deliveries` tables.
