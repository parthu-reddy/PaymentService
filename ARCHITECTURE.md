# Payment Gateway Integration Architecture

The Payment Service handles all interactions with external payment providers (e.g., Stripe, Razorpay, Vyapar). It provides a generic interface that abstracts away gateway-specific implementation details.

## Detailed Sequence Diagram

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
