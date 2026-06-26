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
  - `repository/`: Spring Data JPA repositories.
  - `service/`: Contains `WebhookProcessingService` and `PaymentGatewayOrchestrator`.
  - `service/gateway/`: The Strategy Pattern implementation. `PaymentGatewayStrategy` interface with `RazorpayStrategy` and `CashfreeStrategy`.
  - `filter/`: Contains `RequestCachingFilter` (for HMAC verification) and `IdempotencyFilter` (Redis-based distributed locking).
  - `controller/`: REST endpoints like `WebhookController`.

## Key Technical Decisions
1. **Idempotency**: All incoming requests that modify state must include an `Idempotency-Key` header. The `IdempotencyFilter` checks Redis via `SETNX` for a lock with a 5-minute TTL. If it fails to acquire the lock, a 409 Conflict is returned to prevent double processing.
2. **Webhook Verification**: Payment gateways send HMAC SHA-256 signed webhooks. Because Spring consumes the `HttpServletRequest` input stream during JSON parsing, we intercept it with `RequestCachingFilter` (`ContentCachingRequestWrapper`). The controller extracts the raw byte array to verify the signature *before* the JSON payload is processed. Cashfree additionally checks timestamps to prevent replay attacks (5-min window).
3. **Database Constraints**: Financial records are strictly immutable. Amounts are stored as decimals (`DECIMAL(15,2)`) or in integer subunits (paise for Razorpay). Updates append new records (e.g., `refunds` table) rather than modifying successful transactions. Flyway manages all DDL in `src/main/resources/db/migration/V1__init_schema.sql`.

## Common Troubleshooting Scenarios
- **Signature Verification Failing**: If webhooks fail validation, ensure `RequestCachingFilter` is registered as the highest precedence filter. Any filter that reads the body before it will corrupt the payload.
- **Idempotency Blocking**: If valid requests are returning 409 Conflict, verify the Redis connection and ensure clients are generating *unique* `Idempotency-Key` UUIDs for distinct retries.
- **Missing SDKs/Dependencies**: Razorpay (`razorpay-java:1.4.9`) and Cashfree (`cashfree_pg:6.0.2`) are hardcoded in `pom.xml`.

Always refer to the original `Java Payment Gateway Integration.md` for context on the exact business requirements behind these implementations.
