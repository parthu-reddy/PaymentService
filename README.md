# Food Delivery Payment Gateway Service

This is a robust, production-ready payment gateway integration service built using Java 17 and Spring Boot 3. 
It supports multiple payment gateways like **Razorpay** and **Cashfree** through a Strategy Pattern, ensuring extensibility, fault tolerance, and security via HMAC signature validations and idempotency controls.

## Prerequisites
- Java 17+
- Maven 3.8+
- Docker & Docker Compose (for local database & Redis)

## Setup and Installation

### 1. Run Local Infrastructure
To spin up PostgreSQL and Redis locally:
```bash
docker-compose up -d
```

### 2. Configure Environment Variables
In production, ensure you supply the following environment variables:

- `DB_URL` (default: `jdbc:postgresql://localhost:5432/payment_db`)
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `RAZORPAY_KEY_ID`
- `RAZORPAY_KEY_SECRET`
- `RAZORPAY_WEBHOOK_SECRET`
- `CASHFREE_CLIENT_ID`
- `CASHFREE_CLIENT_SECRET`

### 3. Build the Application
```bash
mvn clean package -DskipTests
```

### 4. Run the Application
```bash
java -jar target/payment-service-0.0.1-SNAPSHOT.jar
```

## Core Features
1. **Multi-Gateway Support**: Managed via `PaymentGatewayOrchestrator` and `PaymentGatewayStrategy`.
2. **Idempotency**: Utilizes Redis to block duplicate concurrent requests and retry floods from gateways.
3. **Webhook Verification**: Uses a custom `ContentCachingRequestWrapper` filter to validate HMAC SHA-256 signatures of webhook payloads before deserialization.
4. **Resilient Database Schema**: Built with strict immutability in mind, mapping to `merchants`, `customers`, `orders`, `transactions`, `refunds`, and `payment_intents`. Database schema migrations are strictly managed via Flyway.

## Security Considerations
- **No Floating Point for Currency**: Core database types utilize `DECIMAL(15,2)` or integer-based subunits (Paise for INR).
- **Temporal Webhook Validation**: Enforces time window checks (5-minute TTL) for incoming webhook payloads to mitigate replay attacks (specifically implemented for Cashfree).

## Extending to New Gateways
To add a new gateway, simply:
1. Create a class implementing `PaymentGatewayStrategy`.
2. Implement `getGatewayName()`, `createOrder()`, and `verifyWebhookSignature()`.
3. Spring Boot will automatically inject it into the `PaymentGatewayOrchestrator`.
