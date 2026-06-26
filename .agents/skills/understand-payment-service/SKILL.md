---
name: understand-payment-service
description: Comprehensive architectural overview and troubleshooting guide for the Payment Gateway Integration service. Use this skill when asked to fix bugs, modify core logic, or understand how the payment service works internally.
---

# Payment Service Architecture and Internals

This service integrates Razorpay and Cashfree into a food delivery platform. 
It uses Java 17, Spring Boot 3, PostgreSQL, Redis, and Flyway.

## Directory Structure
- `src/main/java/com/fooddelivery/payments/`
  - `model/`: JPA Entities mapping to strictly normalized tables (`Merchant`, `Customer`, `Order`, `PaymentIntent`, `Transaction`, `Refund`, `WebhookDelivery`).
  - `model/enums/`: Type-safe strict enumerations (`OrderStatus`, `IntentStatus`, `DeliveryStatus`, `PaymentGateway`).
  - `repository/`: Spring Data JPA repositories.
  - `service/`: Contains `WebhookProcessingService` (handles PII masking and event processing) and `PaymentGatewayOrchestrator`.
  - `service/gateway/`: The Strategy Pattern implementation. `IPaymentGatewayStrategy` interface with `RazorpayStrategy`, `CashfreeStrategy`, and `VyaparGatewayStrategy`.
  - `filter/`: Contains `RequestCachingFilter` (for HMAC verification) and `IdempotencyFilter` (Redis-based distributed locking).
  - `controller/`: REST endpoints like `WebhookController`.

## Key Technical Decisions
1. **Idempotency & Replay Attack Prevention**: All incoming requests that modify state must include an `Idempotency-Key` header. The `IdempotencyFilter` checks Redis via `SETNX` for a lock. For webhooks, the `WebhookController` checks `WebhookProcessingService.isEventProcessed(eventId)`. If the webhook `Event-ID` header is missing, the controller generates a deterministic SHA-256 hash of the raw payload to prevent replay attacks.
2. **Webhook Verification & PII Masking**: Gateways send HMAC SHA-256 signed webhooks. We intercept the stream with `RequestCachingFilter`. The controller extracts the raw byte array to verify the signature *before* JSON parsing. Cashfree checks timestamps. Before saving the payload to the database audit log (`WebhookDelivery`), `WebhookProcessingService` actively masks PII (phones, emails) to comply with data privacy laws.
3. **JSON Parsing & Dependency Injection**: We universally inject Spring's Jackson `ObjectMapper` (even for Vyapar REST calls) for standardized serialization, avoiding manual `org.json` instantiation where possible (except Razorpay which strictly requires it).
4. **Database Constraints**: Financial records are strictly immutable. Amounts are stored as decimals (`DECIMAL(15,2)`) or in integer subunits. Check constraints ensure refunds cannot exceed captured amounts. Flyway manages all DDL in `src/main/resources/db/migration/`.

## Common Troubleshooting Scenarios
- **Signature Verification Failing**: If webhooks fail validation, ensure `RequestCachingFilter` is registered as the highest precedence filter. Any filter that reads the body before it will corrupt the payload.
- **Idempotency Blocking**: If valid requests are returning 409 Conflict, verify the Redis connection and ensure clients are generating *unique* `Idempotency-Key` UUIDs for distinct retries.
- **Missing SDKs/Dependencies**: Razorpay (`razorpay-java:1.4.9`) and Cashfree (`cashfree_pg:6.0.2`) are hardcoded in `pom.xml`.

Always refer to the original `Java Payment Gateway Integration.md` for context on the exact business requirements behind these implementations.
