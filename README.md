# Food Delivery Payment Gateway Service

This is a robust, production-ready payment gateway integration service built using Java 17 and Spring Boot 3. 
It supports multiple payment gateways like **Vyapar**, **Razorpay**, and **Cashfree** through a Strategy Pattern, ensuring extensibility, fault tolerance, and security via HMAC signature validations and idempotency controls.

## Enterprise Architecture (Day 2 Features)
This service has been upgraded for massive scale and resilience:
- **Event-Driven (Kafka)**: Emits `PaymentSucceededEvent` to the `payment-events` topic so downstream services (like Kitchen/Delivery) can react instantly.
- **Circuit Breakers (Resilience4j)**: External API calls are wrapped in circuit breakers. If a gateway goes down, the system gracefully degrades to a 503 instead of tying up threads.
- **Automated Retry & DLQ**: Database lock exceptions trigger automated backoff retries. Failing webhooks are sent to a Dead Letter Queue (`DEAD_LETTER` status) via a background Cron job for manual review.
- **Reconciliation Cron**: A scheduled job ensures no payment is lost. It actively queries gateways for `INITIATED` intents older than 15 minutes to reconcile missed webhooks.
- **PII Scrubbing**: Automatically scrubs raw mobile numbers and emails from stored webhook payloads for data privacy.

## Checkout User Experience
We provide a seamless drop-in checkout experience across all gateways.

### Vyapar UPI Checkout
Minimalist UPI QR code generation.
![Vyapar QR Checkout](/Users/parthureddy/.gemini/antigravity-ide/brain/c6033113-b4fb-4043-a5dc-76048e44e9d8/vyapar_qr_checkout_1782489255418.png)

### Razorpay Modal
Sleek credit card checkout directly embedded over the app.
![Razorpay Modal](/Users/parthureddy/.gemini/antigravity-ide/brain/c6033113-b4fb-4043-a5dc-76048e44e9d8/razorpay_checkout_flow_1782489266773.png)

### Cashfree Redirect
Premium loading state for gateway redirects.
![Cashfree Redirect](/Users/parthureddy/.gemini/antigravity-ide/brain/c6033113-b4fb-4043-a5dc-76048e44e9d8/cashfree_checkout_flow_1782489280146.png)

## Prerequisites
- Java 17+
- Maven 3.8+
- Docker & Docker Compose (for Postgres, Redis, and Kafka)

## Setup and Installation

### 1. Run Local Infrastructure
To spin up PostgreSQL, Redis, and Kafka locally:
```bash
docker-compose up -d
```

### 2. Configure Environment Variables
In production, ensure you supply the following environment variables:
- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `KAFKA_BOOTSTRAP_SERVERS`
- Gateway keys (e.g. `VYAPAR_API_KEY`, `RAZORPAY_KEY_ID`, `CASHFREE_CLIENT_ID`)

### 3. Build and Run
```bash
./mvnw clean package -DskipTests
java -jar target/payment-service-0.0.1-SNAPSHOT.jar
```

## Security Considerations
- **No Floating Point for Currency**: Core database types utilize `DECIMAL(15,2)` or integer-based subunits (Paise for INR).
- **Constant-Time Verification**: HMAC verification uses constant-time equals to prevent timing attacks.
- **Replay Protection**: If `Event-ID` is missing from headers, a deterministic SHA-256 hash of the payload is used to trigger idempotency locks.
