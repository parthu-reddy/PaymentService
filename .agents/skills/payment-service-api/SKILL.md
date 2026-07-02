---
name: payment-service-api
description: Complete API reference and integration guide for the Payment Gateway Integration service. Use this when building frontends, other microservices, or agents that need to create payment orders, process refunds, or understand webhook flows for Razorpay, Cashfree, and Vyapar gateways.
---

# Payment Gateway Integration: API & Integration Guide

This service handles all payment processing — creating payment orders via gateway SDKs (Razorpay, Cashfree, Vyapar), securely verifying webhooks, processing refunds, and publishing payment status events to Kafka. It runs as an independent Spring Boot microservice on **port 8084** (default).

## Base URL
Default local environment: `http://localhost:8084`

## REST API Endpoints

### Payment Operations

#### 1. Create Payment Order
**Endpoint**: `POST /api/v1/payments/create-order?gateway={gateway}`
**Purpose**: Create a payment order on the specified gateway. Returns a gateway-specific order ID or UPI intent string.
**Query Parameters**:
- `gateway` (String, required): `razorpay`, `cashfree`, or `vyapar`

**Body (JSON)**:
```json
{
  "internalOrderId": "uuid-of-order",
  "amountInInr": 599.00,
  "customerPhone": "9876543210"
}
```
**Validation**:
- `internalOrderId` must be a valid UUID format.
- `amountInInr` must be positive and non-null.
- `customerPhone` is required for Cashfree.

**Response (200)**: Returns the `gatewayOrderId` string (e.g., `order_XyZ123` for Razorpay or a UPI intent for Vyapar).

**Side Effects**: Saves a `PaymentIntent` record (status: `INITIATED`) to the database.

#### 2. Initiate Refund
**Endpoint**: `POST /api/v1/payments/refund?gateway={gateway}`
**Purpose**: Process a refund for a previously completed payment.
**Query Parameters**:
- `gateway` (String, required): `razorpay`, `cashfree`, or `vyapar`

**Body (JSON)**:
```json
{
  "gatewayOrderId": "order_XyZ123",
  "amountInInr": 599.00,
  "reason": "Customer requested cancellation"
}
```
**Response (200)**: `"Refund initiated successfully"`
**Response (400)**: `"Refund failed"`

---

### Webhook Endpoints (Called by Payment Gateways)

These endpoints are **NOT called by other microservices**. They are callback URLs registered with the payment gateways.

#### 3. Razorpay Webhook
**Endpoint**: `POST /api/v1/webhooks/razorpay`
**Security**: HMAC-SHA256 signature verification via `X-Razorpay-Signature` header.
**Events Handled**: `payment.captured`, `payment.failed`, `refund.processed`

#### 4. Cashfree Webhook
**Endpoint**: `POST /api/v1/webhooks/cashfree`
**Security**: Cashfree SDK signature verification with timestamp validation.
**Events Handled**: `PAYMENT_SUCCESS_WEBHOOK`, `PAYMENT_FAILED_WEBHOOK`

#### 5. Vyapar Webhook
**Endpoint**: `POST /api/v1/webhooks/vyapar`
**Security**: Constant-time HMAC-SHA256 verification via `X-Vyapar-Signature` header.
**Events Handled**: `order.paid`, `payment_failed`

---

## Kafka Integration

### Published Events (to `payment-events`)
| Event | When Published |
|---|---|
| `PaymentCompletedEvent` (aka `PAYMENT_COMPLETED`) | Webhook confirms successful payment |
| `PaymentFailedEvent` (aka `PAYMENT_FAILED`) | Webhook confirms failed payment |
| `PaymentRefundedEvent` (aka `PAYMENT_REFUNDED`) | Refund is processed successfully |

Events are published via the **Transactional Outbox Pattern** — webhook processing saves the event to `outbox_events` atomically with the status update, and the `OutboxEventPoller` publishes to Kafka.

### Event Payloads
**PaymentCompletedEvent**:
```json
{
  "orderId": "uuid",
  "paymentIntentId": "uuid",
  "amount": 599.00,
  "gateway": "razorpay"
}
```

---

## How Other Services Should Integrate

### Creating a Payment (from CustomerApplication)
1. Customer places an order → `CustomerApplication` calls `POST /api/v1/payments/create-order`.
2. The returned `gatewayOrderId` is sent to the frontend to initialize the gateway SDK.
3. **Do NOT trust client-side payment confirmations.** Wait for the `PaymentCompletedEvent` on `payment-events` Kafka topic.

### Processing a Refund (from CustomerApplication)
When an order is cancelled/rejected, `CustomerApplication` calls `POST /api/v1/payments/refund` to initiate the refund through the original gateway.

### Idempotency
All POST requests must include an `Idempotency-Key` (UUID) HTTP header. The `IdempotencyFilter` uses Redis to prevent duplicate operations.

---

## Background Jobs

### Reconciliation Cron
- Runs every **10 minutes**.
- Syncs `INITIATED` payment intents older than 10 minutes by querying the gateway API.

### DLQ Retry Cron
- Auto-retries `FAILED` webhook deliveries.
- Transitions permanently failed webhooks to `DEAD_LETTER` status after exhausting retries.

## Database
- **PostgreSQL** database: `payment_db`
- **Flyway migrations**: `src/main/resources/db/migration/`
- Key tables: `payment_intents`, `transactions`, `refunds`, `webhook_deliveries`, `outbox_events`
- Financial amounts stored as `DECIMAL(15,2)`. Check constraints ensure refunds ≤ captured amounts.

## Gateway SDKs
- **Razorpay**: `razorpay-java:1.4.9`
- **Cashfree**: `cashfree_pg:6.0.2`
- **Vyapar**: Custom REST integration with HMAC verification
