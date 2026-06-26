# **Architectural Blueprint for Payment Gateway Integration: Food Delivery Platform in Bengaluru**

The architecture of a payment processing system for a high-volume food delivery application in Bengaluru demands rigorous attention to fault tolerance, idempotency, and transactional integrity. Bengaluru presents a unique market environment characterized by ultra-high Unified Payments Interface (UPI) penetration, frequent micro-transactions, peak-hour concurrency spikes, and mobile-first consumer behavior. The deployment of a robust payment gateway integration is not merely a financial operational requirement; it is a core component of the user experience and restaurant partner retention strategy.  
This comprehensive technical report provides the exhaustive blueprint required to transition from conceptualization to a production-ready payment ecosystem. Designed for immediate consumption by advanced engineering teams, such as the antigravity deployment unit, this document outlines the optimal payment gateway strategy, establishes a highly normalized PostgreSQL database schema, details the step-by-step Java and Spring Boot implementation required for integration, and establishes the deployment protocols necessary for a resilient financial infrastructure.

## **Strategic Payment Gateway Selection and Orchestration**

The Indian payment landscape in 2026 is governed by a strict regulatory framework, including Reserve Bank of India mandates on tokenization and zero Merchant Discount Rates (MDR) for UPI transactions. For a food delivery application operating in Bengaluru, the payment infrastructure must optimize for high UPI success rates, low latency during the checkout experience, and rapid settlement cycles to maintain restaurant liquidity. Relying on a single payment gateway exposes the platform to single points of failure during bank downtimes or gateway-specific outages.  
An analysis of the top payment gateways reveals distinct advantages depending on transaction volume and specific operational requirements1. A multi-gateway orchestration strategy is highly recommended for an enterprise-grade food delivery platform. This involves utilizing a primary gateway for consumer collections, a secondary gateway for automated vendor payouts, and potentially a payment orchestration layer to route traffic dynamically based on real-time success rates.

| Payment Service Provider | Primary Strengths and Focus Areas | Total Cost of Ownership and Fees | Standard Settlement Speed | Optimal Role in Food Delivery Architecture |
| :---- | :---- | :---- | :---- | :---- |
| **Razorpay** | Developer-friendly API, native UPI intent flows, highest UPI success rate (\~95%), comprehensive analytic dashboard. | 2% MDR standard; highly cost-effective when factoring in zero setup fees and high conversion rates. | T+2 (Standard) / T+1 (Paid) | **Primary Collection Gateway**. Best for frontend checkout conversion and mobile experience. |
| **Cashfree Payments** | Lowest headline MDR, superior Payouts API, automated refunds, and instant settlement products. | 1.6% \- 1.75% MDR standard; highly cost-effective at enterprise scale. | T+1 (Standard) / Same-day available | **Secondary/Payouts Gateway**. Best for routing funds to restaurants and delivery fleets. |
| **PhonePe Payment Gateway** | Dominant consumer brand familiarity, massive UPI transaction network processing 48%+ of Indian volume. | Custom pricing; relies heavily on UPI volume. | T+1 to T+2 | **UPI Optimization Layer**. Ideal for routing high-volume UPI transactions natively. |
| **PayU** | Enterprise stability, deep legacy banking network, international payment capabilities. | 1.9% \- 2.5% MDR (Custom negotiated at scale). | T+1 to T+2 | **Enterprise Fallback**. Suitable for massive scale but less optimal for agile startup deployments. |
| **Juspay** | Payment orchestration layer, smart routing across multiple gateways to maximize success rates. | Custom enterprise pricing. | N/A (Relies on underlying gateway) | **Top-Level Orchestrator**. Recommended only when processing tens of thousands of orders daily. |

For a Bengaluru-centric deployment initiating operations, a dual-gateway setup combining Razorpay and Cashfree provides the highest return on engineering investment1. Razorpay serves as the optimal primary gateway due to its robust developer tooling, minimal onboarding friction, and superior handling of UPI intent flows that seamlessly transition users to applications like Google Pay or PhonePe1. Cashfree must be integrated in parallel to handle the complex, multi-party payout requirements of a food delivery marketplace, utilizing its industry-leading payout infrastructure to disburse funds to restaurant partners and delivery drivers on a T+1 or same-day schedule1.

## **Transactional Integrity and Relational Database Schema Design**

Financial systems require schemas optimized for immutability, auditability, and absolute data integrity. Updates to financial records must be strictly avoided; instead, all changes should be recorded as new ledger entries7. Furthermore, monetary values must never be stored as floating-point numbers due to inevitable binary precision loss; they must be stored as DECIMAL(15,4) or as integers representing the smallest currency subunit, which is paise for the Indian Rupee7.  
The database architecture must support multi-tenant billing, track real-time ledger balances, manage refunds, and handle payment methods8. The following PostgreSQL schema establishes a robust foundation for the food delivery payment engine.

### **Entity and Merchant Provisioning**

The foundation of the schema involves tracking the entities involved in the transaction. Customer profiles and merchant accounts must be decoupled from the transactional data to ensure normalization8.

SQL  
CREATE TABLE merchants (  
    id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    name VARCHAR(255) NOT NULL,  
    email VARCHAR(255) UNIQUE NOT NULL,  
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',  
    settlement\_account\_id VARCHAR(255),  
    created\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP,  
    updated\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP  
);

CREATE TABLE customers (  
    id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    phone\_number VARCHAR(15) UNIQUE NOT NULL,  
    email VARCHAR(255),  
    full\_name VARCHAR(255),  
    created\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP  
);

### **Order and Payment Intent Decoupling**

The architectural design must strictly decouple the business-level order from the payment attempt. The orders table tracks the user's cart and delivery agreement, while the payment\_intents table tracks the lifecycle of the attempt to collect funds for that order. This structural separation allows a single order to experience multiple failed payment attempts without corrupting the core order state8.

SQL  
CREATE TABLE orders (  
    id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    customer\_id UUID NOT NULL REFERENCES customers(id),  
    merchant\_id UUID NOT NULL REFERENCES merchants(id),  
    total\_amount DECIMAL(15,2) NOT NULL CHECK (total\_amount \> 0),  
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',  
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',   
    receipt\_reference VARCHAR(255),  
    created\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP,  
    updated\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP  
);

CREATE TABLE payment\_intents (  
    id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    order\_id UUID NOT NULL REFERENCES orders(id),  
    gateway\_name VARCHAR(50) NOT NULL,   
    gateway\_order\_id VARCHAR(255) UNIQUE NOT NULL,   
    amount DECIMAL(15,2) NOT NULL,  
    status VARCHAR(50) NOT NULL DEFAULT 'INITIATED',   
    idempotency\_key VARCHAR(255) UNIQUE NOT NULL,  
    created\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP,  
    updated\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP  
);

### **Immutable Ledgers, Transactions, and Refunds**

The actual movement of funds is recorded in the transactions table, which serves as the immutable ledger. When a payment gateway confirms a successful charge via a webhook, a record is inserted here capturing the specific payment method and network details8. If a customer requests a cancellation, the original transaction is never modified; instead, a new record is generated in the refunds table, creating a complete and compliant audit trail7.

SQL  
CREATE TABLE transactions (  
    id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    payment\_intent\_id UUID NOT NULL REFERENCES payment\_intents(id),  
    gateway\_payment\_id VARCHAR(255) UNIQUE NOT NULL,   
    amount DECIMAL(15,2) NOT NULL,  
    payment\_method VARCHAR(50),   
    payment\_network VARCHAR(50),   
    status VARCHAR(50) NOT NULL,  
    error\_code VARCHAR(100),  
    error\_message TEXT,  
    captured\_at TIMESTAMP WITH TIME ZONE,  
    created\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP  
);

CREATE TABLE refunds (  
    id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    transaction\_id UUID NOT NULL REFERENCES transactions(id),  
    gateway\_refund\_id VARCHAR(255) UNIQUE,  
    amount DECIMAL(15,2) NOT NULL CHECK (amount \> 0),  
    reason VARCHAR(255) NOT NULL,  
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',  
    created\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP  
);

### **Asynchronous Event Logging**

To ensure asynchronous events from Razorpay or Cashfree are processed accurately and to assist with forensic debugging during disputes, all incoming webhooks must be logged before any business logic is executed. This design pattern ensures that if the application server crashes during processing, the raw webhook data is safely persisted for manual or automated retry mechanisms8.

SQL  
CREATE TABLE webhook\_deliveries (  
    id UUID PRIMARY KEY DEFAULT gen\_random\_uuid(),  
    gateway\_name VARCHAR(50) NOT NULL,  
    event\_id VARCHAR(255) UNIQUE NOT NULL,   
    event\_type VARCHAR(100) NOT NULL,   
    payload JSONB NOT NULL,  
    processing\_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',   
    error\_log TEXT,  
    created\_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT\_TIMESTAMP  
);

## **Step-by-Step Integration Lifecycle and State Transitions**

Implementing a payment gateway is not a single API call but a distributed state machine involving the client device, the backend server, and the gateway's infrastructure12. The process requires a precise sequence of operations to prevent phantom orders, unauthorized captures, or orphaned funds.  
The first phase begins when the customer finalizes their cart and selects a payment method. The client application must request a payment session from the backend. The backend constructs a secure order payload, ensuring that the total amount is calculated server-side to prevent client-side manipulation13. The backend then makes a synchronous API call to the chosen gateway (e.g., Razorpay's /v1/orders endpoint) to generate a unique gateway\_order\_id15. This ID tightly couples the impending payment attempt to the specific cart amount. The backend persists this relationship in the payment\_intents table and returns the gateway\_order\_id to the client.  
The second phase involves the client-side checkout experience. The client application initializes the payment gateway's SDK (e.g., Razorpay Checkout or Cashfree Android SDK) using the provided gateway\_order\_id and the merchant's public API key15. The user inputs their payment details or selects a UPI application. The SDK securely transmits the sensitive payment data directly to the gateway's PCI-compliant servers, entirely bypassing the food delivery application's backend18. Upon successful authorization by the banking network, the gateway SDK returns a success callback to the client application, containing the gateway\_payment\_id and a cryptographic signature.  
The third and most critical phase is the server-side verification and fulfillment. The client application forwards the success callback data to the backend. The backend must never trust this client-side assertion implicitly. Instead, it must cryptographically verify the signature provided in the payload using its highly guarded secret key13. Concurrently, the payment gateway transmits a server-to-server webhook (e.g., payment.captured) to the backend's exposed endpoint20. The backend processes this webhook, verifies its origin via signature validation, updates the transactions ledger, marks the order as paid, and dispatches the food preparation mandate to the respective restaurant18.

| Internal Order State | Gateway Payment State | Triggering Mechanism | Required Business Action |
| :---- | :---- | :---- | :---- |
| CREATED | INITIATED | Customer clicks checkout. | Persist cart, generate gateway order ID. |
| PENDING\_PAYMENT | AUTHORIZED | Bank approves funds via SDK. | Await cryptographic verification or webhook. |
| PAID | CAPTURED | Webhook signature validated. | Dispatch order to kitchen; notify delivery fleet. |
| FAILED | FAILED | Gateway declines transaction. | Prompt customer to retry with an alternate method. |
| REFUNDING | REFUND\_PENDING | Order cancelled post-capture. | Initiate gateway refund API; monitor webhook. |

## **System Architecture and Dependency Management**

The implementation relies on a modern Java stack utilizing Spring Boot for RESTful API orchestration, Spring Data JPA for object-relational mapping, and Redis for high-speed, distributed caching required by idempotency filters. The build configuration requires the official SDKs provided by the payment gateways to ensure compatibility with their latest API versions.  
The Maven pom.xml must include the following explicit dependencies to support the cryptographic operations, database connections, and gateway communications required for the food delivery platform19.

XML  
\<dependencies\>  
    \<\!-- Core Spring Boot Web, Data JPA, and Validation \--\>  
    \<dependency\>  
        \<groupId\>org.springframework.boot\</groupId\>  
        \<artifactId\>spring-boot-starter-web\</artifactId\>  
    \</dependency\>  
    \<dependency\>  
        \<groupId\>org.springframework.boot\</groupId\>  
        \<artifactId\>spring-boot-starter-data-jpa\</artifactId\>  
    \</dependency\>  
      
    \<\!-- Distributed Caching and Idempotency \--\>  
    \<dependency\>  
        \<groupId\>org.springframework.boot\</groupId\>  
        \<artifactId\>spring-boot-starter-data-redis\</artifactId\>  
    \</dependency\>

    \<\!-- Payment Gateway SDKs \--\>  
    \<dependency\>  
        \<groupId\>com.razorpay\</groupId\>  
        \<artifactId\>razorpay-java\</artifactId\>  
        \<version\>1.4.9\</version\>  
    \</dependency\>  
    \<dependency\>  
        \<groupId\>com.cashfree.pg.java\</groupId\>  
        \<artifactId\>cashfree\_pg\</artifactId\>  
        \<version\>6.0.2\</version\>  
    \</dependency\>

    \<\!-- Persistence Layer \--\>  
    \<dependency\>  
        \<groupId\>org.postgresql\</groupId\>  
        \<artifactId\>postgresql\</artifactId\>  
        \<scope\>runtime\</scope\>  
    \</dependency\>  
\</dependencies\>

## **Gateway Order Creation and Server-Side Integration**

To initiate a transaction, the server must interact with the gateway APIs. Both Razorpay and Cashfree mandate that an order entity is created on their servers before any payment details are collected from the user13. This process prevents malicious users from altering the checkout amount directly in the browser's document object model.  
The implementation must manage connections to multiple gateways, abstracting the complexity behind a unified service interface. The following code demonstrates how to initialize the SDK clients securely using environment variables and how to construct the respective order requests13.

Java  
package com.fooddelivery.payments.service;

import com.razorpay.Order;  
import com.razorpay.RazorpayClient;  
import com.razorpay.RazorpayException;  
import com.cashfree.pg.Cashfree;  
import com.cashfree.pg.models.CreateOrderRequest;  
import com.cashfree.pg.models.CustomerDetails;  
import com.cashfree.pg.models.OrderEntity;  
import com.cashfree.pg.models.ApiResponse;  
import org.json.JSONObject;  
import org.springframework.beans.factory.annotation.Value;  
import org.springframework.stereotype.Service;  
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;  
import java.util.UUID;

@Service  
public class PaymentGatewayOrchestrator {

    private final RazorpayClient razorpayClient;  
      
    public PaymentGatewayOrchestrator(  
            @Value("${razorpay.key.id}") String rzpKeyId,  
            @Value("${razorpay.key.secret}") String rzpKeySecret,  
            @Value("${cashfree.client.id}") String cfClientId,  
            @Value("${cashfree.client.secret}") String cfClientSecret) throws RazorpayException {  
          
        // Initialize Razorpay Client  
        this.razorpayClient \= new RazorpayClient(rzpKeyId, rzpKeySecret);  
          
        // Initialize Cashfree Configuration  
        Cashfree.XClientId \= cfClientId;  
        Cashfree.XClientSecret \= cfClientSecret;  
        Cashfree.XEnvironment \= Cashfree.Environment.PRODUCTION;  
    }

    @Transactional  
    public String createRazorpayOrder(UUID internalOrderId, BigDecimal amountInInr, String receiptRef) {  
        try {  
            // Razorpay processes amounts in the smallest currency subunit (paise)  
            int amountInPaise \= amountInInr.multiply(new BigDecimal("100")).intValue();

            JSONObject orderRequest \= new JSONObject();  
            orderRequest.put("amount", amountInPaise);  
            orderRequest.put("currency", "INR");  
            orderRequest.put("receipt", receiptRef);  
              
            // Embedding internal references aids in manual reconciliation and support workflows  
            JSONObject notes \= new JSONObject();  
            notes.put("internal\_order\_id", internalOrderId.toString());  
            orderRequest.put("notes", notes);

            Order razorpayOrder \= razorpayClient.orders.create(orderRequest);  
            return razorpayOrder.get("id"); // Yields format: 'order\_xxx...'  
              
        } catch (RazorpayException e) {  
            throw new RuntimeException("Razorpay order creation failed: " \+ e.getMessage(), e);  
        }  
    }

    @Transactional  
    public String createCashfreeOrder(UUID internalOrderId, BigDecimal amountInInr, String customerPhone) {  
        try {  
            CustomerDetails customer \= new CustomerDetails();  
            customer.setCustomerId(UUID.randomUUID().toString());  
            customer.setCustomerPhone(customerPhone);  
              
            CreateOrderRequest request \= new CreateOrderRequest();  
            // Cashfree accepts amounts as floating point representations of the main currency  
            request.setOrderAmount(amountInInr.doubleValue());  
            request.setOrderCurrency("INR");  
            request.setCustomerDetails(customer);  
              
            // API Version must align with the documentation schema  
            String apiVersion \= "2023-08-01";  
            ApiResponse\<OrderEntity\> response \= Cashfree.PGCreateOrder(apiVersion, request, null, null, null);  
              
            return response.getData().getOrderId(); // Yields custom alphanumeric string  
              
        } catch (Exception e) {  
            throw new RuntimeException("Cashfree order creation failed: " \+ e.getMessage(), e);  
        }  
    }  
}

The application must map the returned gateway order identifier to the internal order representation within the PostgreSQL database. This mapping is vital for the subsequent webhook verification phase, where the gateway will communicate status updates referencing solely its proprietary identifier15.

## **Overcoming Servlet Constraints for Webhook Verification**

Webhooks form the asynchronous backbone of modern payment systems, notifying the application server when a transaction succeeds, fails, or is disputed20. To prevent malicious actors from forging payment success notifications, gateways sign their webhook payloads using an HMAC SHA-256 hash derived from the shared secret key and the raw request body28.  
A significant technical hurdle arises within the Java Servlet architecture. The HttpServletRequest.getInputStream() method reads the incoming HTTP payload as a stream of bytes. By design, an input stream can only be consumed once31. If the application reads the stream to validate the cryptographic signature, the framework's JSON deserialization mechanisms will subsequently encounter an empty body, resulting in processing failures31. Conversely, if the framework parses the JSON first, the exact byte arrangement of the original payload is lost, guaranteeing a signature verification failure due to discrepancies in whitespace or encoding28.  
To resolve this architectural limitation, the deployment requires a custom filter utilizing Spring's ContentCachingRequestWrapper. This wrapper intercepts the incoming request, consumes the input stream entirely, caches the bytes in memory, and exposes a mechanism to read the exact raw byte array multiple times without exhaustion31.

Java  
package com.fooddelivery.payments.filter;

import org.springframework.stereotype.Component;  
import org.springframework.web.filter.OncePerRequestFilter;  
import org.springframework.web.util.ContentCachingRequestWrapper;

import jakarta.servlet.FilterChain;  
import jakarta.servlet.ServletException;  
import jakarta.servlet.http.HttpServletRequest;  
import jakarta.servlet.http.HttpServletResponse;  
import java.io.IOException;

@Component  
public class RequestCachingFilter extends OncePerRequestFilter {

    @Override  
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)  
            throws ServletException, IOException {  
          
        // Wrap the standard servlet request to enable multiple reads of the payload body  
        ContentCachingRequestWrapper wrappedRequest \= new ContentCachingRequestWrapper(request);  
          
        // The wrapped request propagates down the security and interceptor chains  
        filterChain.doFilter(wrappedRequest, response);  
    }  
}

By placing this filter at the top of the Spring Security chain, the application ensures that all downstream controllers, specifically the webhook endpoints, have unimpeded access to the pristine, unparsed payload necessary for cryptographic hashing31.

## **Idempotency Engineering and Concurrency Management**

In a distributed microservices environment, network unreliability is an inevitability. Mobile clients passing through zones of poor connectivity may drop HTTP responses, prompting automatic retries of the same checkout request37. Similarly, payment gateways employ aggressive retry policies for webhooks; if the application server fails to return a 200 OK status rapidly, the gateway will transmit the same event payload repeatedly21. Without protective mechanisms, these retries cause catastrophic double-charging of customers, duplicate food orders dispatched to kitchens, and corrupted ledger balances10.  
The solution requires the implementation of an idempotency layer37. Idempotency guarantees that executing the same operation multiple times yields the identical system state as executing it exactly once10. For operations resulting in financial or logistical side effects, the system must utilize an Idempotency-Key header supplied by the client application or derived from the webhook's unique event identifier10.  
The architecture leverages Redis as a highly performant, distributed lock and cache repository23. When an incoming request reaches the application, a custom IdempotencyFilter intercepts it. The filter queries Redis for the presence of the provided idempotency key. If the key exists, indicating a duplicate request, the filter halts downstream processing immediately and returns the cached HTTP response from the original successful execution23. If the key is absent, the filter utilizes a Redis SETNX (Set if Not Exists) command to acquire a distributed lock, ensuring exclusive execution even if concurrent duplicate requests arrive simultaneously23. The business logic executes, and the resulting response is serialized and stored in Redis against the key with a predefined Time-To-Live (TTL) that exceeds the gateway's maximum retry window10.  
While Spring Integration provides patterns like the IdempotentReceiverInterceptor, managing idempotency at the HTTP filter level using OncePerRequestFilter provides broader protection for RESTful APIs and integrates seamlessly with the ContentCachingResponseWrapper required to intercept and cache outbound responses23.

## **Cryptographic Webhook Verification and Event Processing**

The webhook controller serves as the primary ingress point for asynchronous financial state changes. Upon receiving a POST request from the gateway, the controller extracts the cached raw byte array from the ContentCachingRequestWrapper31. It then applies the specific cryptographic verification algorithm mandated by the respective gateway.  
For Razorpay, the signature is validated using the Utils.verifyWebhookSignature method provided by their Java SDK, which executes an HMAC SHA-256 hash using the raw payload and the merchant's webhook secret41.

Java  
package com.fooddelivery.payments.controller;

import com.fooddelivery.payments.service.WebhookProcessingService;  
import com.razorpay.Utils;  
import org.slf4j.Logger;  
import org.slf4j.LoggerFactory;  
import org.springframework.beans.factory.annotation.Value;  
import org.springframework.http.HttpStatus;  
import org.springframework.http.ResponseEntity;  
import org.springframework.web.bind.annotation.\*;  
import org.springframework.web.util.ContentCachingRequestWrapper;

import jakarta.servlet.http.HttpServletRequest;  
import java.nio.charset.StandardCharsets;

@RestController  
@RequestMapping("/api/v1/webhooks")  
public class WebhookController {

    private static final Logger logger \= LoggerFactory.getLogger(WebhookController.class);  
    private final String razorpayWebhookSecret;  
    private final WebhookProcessingService webhookProcessingService;

    public WebhookController(  
            @Value("${razorpay.webhook.secret}") String razorpayWebhookSecret,  
            WebhookProcessingService webhookProcessingService) {  
        this.razorpayWebhookSecret \= razorpayWebhookSecret;  
        this.webhookProcessingService \= webhookProcessingService;  
    }

    @PostMapping("/razorpay")  
    public ResponseEntity\<String\> handleRazorpayWebhook(  
            HttpServletRequest request,  
            @RequestHeader("x-razorpay-signature") String signature,  
            @RequestHeader("x-razorpay-event-id") String eventId) {

        try {  
            // Retrieve the unadulterated byte stream from the caching wrapper  
            ContentCachingRequestWrapper wrapper \= (ContentCachingRequestWrapper) request;  
            byte\[\] rawBodyBytes \= wrapper.getContentAsByteArray();  
            String rawBody \= new String(rawBodyBytes, StandardCharsets.UTF\_8);

            // Execute cryptographic verification against spoofing attempts  
            boolean isValid \= Utils.verifyWebhookSignature(rawBody, signature, razorpayWebhookSecret);  
              
            if (\!isValid) {  
                logger.error("Cryptographic verification failed for Razorpay event ID: {}", eventId);  
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Signature Validation Failed");  
            }

            // Offload business logic to an asynchronous service utilizing the eventId as an idempotency key  
            webhookProcessingService.processWebhookAsync(eventId, "RAZORPAY", rawBody);

            // Immediately acknowledge receipt to terminate the gateway's retry loop  
            return ResponseEntity.ok("Webhook Received and Verified");

        } catch (Exception e) {  
            logger.error("Critical failure during webhook ingestion", e);  
            // Returning a 500 status instructs the gateway to enact exponential backoff retries  
            return ResponseEntity.status(HttpStatus.INTERNAL\_SERVER\_ERROR).build();  
        }  
    }  
}

For Cashfree webhooks, the verification process introduces an additional layer of temporal security. The gateway transmits an x-webhook-timestamp alongside the x-webhook-signature28. The validation algorithm concatenates the timestamp string with the raw request body before applying the HMAC SHA-256 hash28. Furthermore, the application must evaluate the timestamp to reject payloads older than a defined threshold, typically five minutes. This temporal validation neutralizes replay attacks, where a malicious actor intercepts a valid webhook and repeatedly transmits it to the server at a later date28.

| Webhook Provider | Required Headers | Cryptographic Payload Target | Verification Mechanism |
| :---- | :---- | :---- | :---- |
| **Razorpay** | x-razorpay-signature | Raw JSON Body | Utils.verifyWebhookSignature() or HMAC SHA-256 |
| **Cashfree** | x-webhook-signature, x-webhook-timestamp | Timestamp \+ Raw JSON Body | HMAC SHA-256 & Base64 Encode; Time window validation |

Once the origin of the webhook is irrefutably established, the raw payload is persisted to the webhook\_deliveries table. A dedicated service then parses the JSON and evaluates the event\_type. In a food delivery context, receiving an order.paid or payment.captured event acts as the definitive trigger to transition the order status, reserve inventory, and dispatch instructions to the restaurant management portal12.

## **Refund Orchestration and Dispute Management**

A functional payment gateway integration must encompass the entire lifecycle of a transaction, which inevitably includes refunds and chargebacks. Food delivery applications experience high refund volumes due to unavailable menu items, delayed deliveries, or poor food quality. Programmatic refund management is essential to prevent operational bottlenecks.  
When initiating a refund, the system must not alter the original successful transaction record7. Instead, the backend invokes the gateway's refund API utilizing the gateway\_payment\_id and the specified refund amount9. The gateway enforces strict temporal constraints, generally limiting refunds to within 180 days of the original capture9. The backend records a new entry in the refunds table with a PENDING status. Subsequently, the gateway processes the refund through the banking network and eventually transmits a refund.processed webhook. Upon receipt and cryptographic verification of this webhook, the system updates the refunds table status to COMPLETED and notifies the customer39.  
Disputes, or chargebacks, occur when a customer bypasses the application's support channels and requests a reversal directly from their issuing bank, often citing fraud or non-delivery9. Gateways provide specific webhook events, such as Cashfree's DISPUTE\_CREATED and DISPUTE\_UPDATED, to alert merchants43. The system must ingest these webhooks, temporarily hold payouts to the associated restaurant partner, and alert the operations team to submit compelling evidence (e.g., GPS delivery coordinates, photographic proof of delivery) through the gateway's dispute resolution API to contest the chargeback successfully8.

## **Production Deployment Protocols for Antigravity**

To elevate the application from a development environment to a production state, the deployment operations managed by the antigravity team must adhere to stringent enterprise standards regarding security, testing, and fault tolerance.  
The management of cryptographic secrets is paramount. Payment API keys, webhook secrets, and database credentials must never be committed to source code repositories or hardcoded within application properties30. The infrastructure deployment must utilize a robust secret management solution, such as HashiCorp Vault or AWS Secrets Manager, to inject secrets dynamically into the Kubernetes pods as environment variables at runtime29. Furthermore, strict environment segregation must be enforced through CI/CD pipelines to guarantee that testing credentials (such as Razorpay keys prefixed with rzp\_test\_) are never accidentally deployed to the production cluster, which would result in catastrophic failures to capture real funds15.  
Testing webhook functionality during local development presents a significant challenge, as payment gateways require publicly accessible URLs to deliver asynchronous POST requests20. The deployment team must establish secure tunneling infrastructure using tools like zrok or ngrok, allowing developers to route live webhook payloads directly to their local localhost environments for rigorous debugging of the signature verification logic39.  
Network security configurations require meticulous implementation. While the webhook endpoints must be exposed over HTTPS, they should be protected by cloud security groups that strictly whitelist the incoming traffic to the published IP address ranges of Razorpay and Cashfree20. This architectural constraint ensures that malicious entities cannot bypass the gateway infrastructure and bombard the application's verification endpoints directly.  
Finally, the application must exhibit profound resilience against cascading failures. External HTTP calls to gateway APIs, particularly synchronous operations like order creation, must be enveloped within circuit breaker patterns using libraries such as Resilience4j. In the event of a widespread gateway outage or elevated error rates, the circuit breaker must trip, dynamically routing transaction requests to the secondary gateway to preserve the checkout experience5. Additionally, the database transactions processing the webhooks must be kept exceptionally short, avoiding synchronous calls to external email services or PDF generators within the transaction boundary, thereby preventing database lock exhaustion and maximizing throughput during peak ordering hours46.

#### **Works cited**

1. Best Payment Gateway India 2026 — Razorpay vs Cashfree vs PayU \- Shop2Host, [https://shop2host.com/best-payment-gateway-india](https://shop2host.com/best-payment-gateway-india)  
2. Best Payment Gateway & Fintech Tools for India 2026 | productgrowth.in, [https://productgrowth.in/tools/payments/](https://productgrowth.in/tools/payments/)  
3. 7 Best Payment Gateways in India (2026 Guide & Comparison), [https://www.photonpay.com/hk/blog/article/payment-gateway-in-india?lang=en](https://www.photonpay.com/hk/blog/article/payment-gateway-in-india?lang=en)  
4. Best Payment Gateways in India 2026: Top 11 Options Compared \- GoKwik, [https://www.gokwik.co/blog/best-payment-gateways-in-India](https://www.gokwik.co/blog/best-payment-gateways-in-India)  
5. Razorpay vs Cashfree vs PhonePe — Fees, COD, Speed \- Growww Tech, [https://growwwtech.com/blog/razorpay-vs-cashfree-vs-phonepe-business-india-2026](https://growwwtech.com/blog/razorpay-vs-cashfree-vs-phonepe-business-india-2026)  
6. Razorpay Alternatives in 2026: 6 Best Payment Gateways for Indian Businesses, [https://www.streetinsider.com/MarketMediaWire/Razorpay+Alternatives+in+2026%3A+6+Best+Payment+Gateways+for+Indian+Businesses/26682166.html](https://www.streetinsider.com/MarketMediaWire/Razorpay+Alternatives+in+2026%3A+6+Best+Payment+Gateways+for+Indian+Businesses/26682166.html)  
7. PostgreSQL Schema Design for Payment Systems \- Nii Darku, [https://www.niidarku.com/blog/postgresql-schema-design-payments](https://www.niidarku.com/blog/postgresql-schema-design-payments)  
8. Payment Gateway Integration Database Structure and Schema, [https://databasesample.com/database/payment-gateway-integration-database](https://databasesample.com/database/payment-gateway-integration-database)  
9. Implementing Refunds and Payment Disputes Programmatically | by Sohail x Codes, [https://medium.com/@sohail\_saifii/implementing-refunds-and-payment-disputes-programmatically-b409c9d82872](https://medium.com/@sohail_saifii/implementing-refunds-and-payment-disputes-programmatically-b409c9d82872)  
10. How to Implement Webhook Idempotency \- Hookdeck, [https://hookdeck.com/webhooks/guides/implement-webhook-idempotency](https://hookdeck.com/webhooks/guides/implement-webhook-idempotency)  
11. Payout Api Cashfree \- Download & Get Started Guide \- Apps on Google Play, [https://schooleducation.mahaonline.gov.in/mobile-how-to-verify-cashfree-payout-webhook-events-and-reconciliation.html](https://schooleducation.mahaonline.gov.in/mobile-how-to-verify-cashfree-payout-webhook-events-and-reconciliation.html)  
12. Food Delivery App Architecture Explained For Foodtech Leaders, [https://foodtech.folio3.com/blog/food-delivery-app-architecture-explained/](https://foodtech.folio3.com/blog/food-delivery-app-architecture-explained/)  
13. Integration Steps | Java SDK | Razorpay Docs, [https://razorpay.com/docs/payments/server-integration/java/integration-steps/](https://razorpay.com/docs/payments/server-integration/java/integration-steps/)  
14. Payment Gateway | Build Your Own \- Integration Steps | Razorpay Docs, [https://razorpay.com/docs/payments/payment-gateway/ecommerce-plugins/build-your-own/](https://razorpay.com/docs/payments/payment-gateway/ecommerce-plugins/build-your-own/)  
15. Quick Integration \- Steps | Razorpay Payment Gateway, [https://razorpay.com/docs/payments/payment-gateway/quick-integration/integration-steps/](https://razorpay.com/docs/payments/payment-gateway/quick-integration/integration-steps/)  
16. Standard Checkout \- Integration Steps | Razorpay Payment Gateway, [https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps/](https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps/)  
17. Android Payment SDK \- Cashfree Payments, [https://www.cashfree.com/docs/payments/online/mobile/android](https://www.cashfree.com/docs/payments/online/mobile/android)  
18. System Design : Payment System(Used as part of e-Commerce Platforms like shopping, online travel agents, cab hailing, food delivery etc) | by Ankit Vashishta | Medium, [https://medium.com/@ankit.vashishta/system-design-payment-system-used-as-part-of-ecommerce-platforms-like-shopping-online-travel-2ba6da5922a0](https://medium.com/@ankit.vashishta/system-design-payment-system-used-as-part-of-ecommerce-platforms-like-shopping-online-travel-2ba6da5922a0)  
19. Building a Payment System with Spring Boot, Stripe, Redis Idempotency & Webhooks (Complete Guide) | by Bharath Dayal | Medium, [https://medium.com/@bharathdayals/building-a-spring-boot-stripe-checkout-redis-idempotency-system-complete-guide-58f063dbb244](https://medium.com/@bharathdayals/building-a-spring-boot-stripe-checkout-redis-idempotency-system-complete-guide-58f063dbb244)  
20. About Webhooks | Razorpay Docs, [https://razorpay.com/docs/webhooks/](https://razorpay.com/docs/webhooks/)  
21. Payment Webhook Setup \- Cashfree Payments, [https://www.cashfree.com/docs/payments/webhooks](https://www.cashfree.com/docs/payments/webhooks)  
22. Orders Webhook Events | Razorpay Docs, [https://razorpay.com/docs/webhooks/orders/](https://razorpay.com/docs/webhooks/orders/)  
23. Spring Boot 3: build the efficient Idempotent API by Redis | by Noah Hsu | Level Up Coding, [https://levelup.gitconnected.com/spring-boot-3-build-the-efficient-idempotent-api-by-redis-8d8ef70d2574](https://levelup.gitconnected.com/spring-boot-3-build-the-efficient-idempotent-api-by-redis-8d8ef70d2574)  
24. Razorpay Java SDK \- GitHub, [https://github.com/razorpay/razorpay-java](https://github.com/razorpay/razorpay-java)  
25. Cashfree PG Java SDK \- GitHub, [https://github.com/cashfree/cashfree-pg-sdk-java](https://github.com/cashfree/cashfree-pg-sdk-java)  
26. Seamless Low Effort \- Cashfree Dev Studio, [https://www.cashfree.com/devstudio/preview/pg/seamless](https://www.cashfree.com/devstudio/preview/pg/seamless)  
27. cashfree-pg-sdk-java/README.md at main \- GitHub, [https://github.com/cashfree/cashfree-pg-sdk-java/blob/main/README.md](https://github.com/cashfree/cashfree-pg-sdk-java/blob/main/README.md)  
28. Webhooks for Beginners: Building a Secure Payment Webhook (Sandbox \+ Production) in Node.js with Cashfree | by Manik Tyagi | Jun, 2026 | Medium, [https://medium.com/@maniktyagi1000/webhooks-for-beginners-building-a-secure-payment-webhook-sandbox-production-in-node-js-ee6357879107](https://medium.com/@maniktyagi1000/webhooks-for-beginners-building-a-secure-payment-webhook-sandbox-production-in-node-js-ee6357879107)  
29. Subscription Webhook HMAC \- Cashfree Payments, [https://www.cashfree.com/docs/api-reference/payments/latest/subscription/webhook-signature](https://www.cashfree.com/docs/api-reference/payments/latest/subscription/webhook-signature)  
30. Verify Webhook Signatures \- Cashfree Payments, [https://www.cashfree.com/docs/payments/online/webhooks/signature-verification](https://www.cashfree.com/docs/payments/online/webhooks/signature-verification)  
31. How to cache Spring Boot request body to read the request content multiple time, [https://inspector.dev/how-to-cache-spring-boot-request-body-to-read-the-request-content-multiple-time/](https://inspector.dev/how-to-cache-spring-boot-request-body-to-read-the-request-content-multiple-time/)  
32. Modify Request Body Before Reaching Controller in Spring Boot \- GeeksforGeeks, [https://www.geeksforgeeks.org/advance-java/modify-request-body-before-reaching-controller-in-spring-boot/](https://www.geeksforgeeks.org/advance-java/modify-request-body-before-reaching-controller-in-spring-boot/)  
33. Fix webhook signature verification for payloads containing special characters \#351 \- GitHub, [https://github.com/razorpay/razorpay-java/issues/351](https://github.com/razorpay/razorpay-java/issues/351)  
34. ContentCachingRequestWrapper \- spring-framework, [https://docs.spring.io/spring-framework/docs/5.0.2.RELEASE/kdoc-api/spring-framework/org.springframework.web.util/-content-caching-request-wrapper/](https://docs.spring.io/spring-framework/docs/5.0.2.RELEASE/kdoc-api/spring-framework/org.springframework.web.util/-content-caching-request-wrapper/)  
35. Request Logging Redaction in Spring Boot Filters \- Medium, [https://medium.com/@AlexanderObregon/request-logging-redaction-in-spring-boot-filters-8e98205d4807](https://medium.com/@AlexanderObregon/request-logging-redaction-in-spring-boot-filters-8e98205d4807)  
36. Get the Response Body in Spring Boot Filter | Baeldung, [https://www.baeldung.com/spring-boot-filter-response-body](https://www.baeldung.com/spring-boot-filter-response-body)  
37. How to Implement Idempotency (Idempotency-Key) in Spring Boot REST APIs \- Preventing Double Charges and Double Clicks | Spring Boot 123, [https://springboot-123.mizucoffee.com/en/blog/spring-boot-rest-api-idempotency-key-guide/](https://springboot-123.mizucoffee.com/en/blog/spring-boot-rest-api-idempotency-key-guide/)  
38. Building an Idempotent Payment System with Spring Boot & React \- the nuclear geeks, [https://thenucleargeeks.com/2025/09/28/building-an-idempotent-payment-system-with-spring-boot-react/](https://thenucleargeeks.com/2025/09/28/building-an-idempotent-payment-system-with-spring-boot-react/)  
39. Cashfree Webhooks Overview, [https://www.cashfree.com/docs/payments/online/webhooks/overview](https://www.cashfree.com/docs/payments/online/webhooks/overview)  
40. Idempotent Receiver Enterprise Integration Pattern \- Spring, [https://docs.spring.io/spring-integration/reference/handler-advice/idempotent-receiver.html](https://docs.spring.io/spring-integration/reference/handler-advice/idempotent-receiver.html)  
41. Validate and Test Webhooks | Razorpay Docs, [https://razorpay.com/docs/webhooks/validate-test/](https://razorpay.com/docs/webhooks/validate-test/)  
42. razorpay-java/src/main/java/com/razorpay/Utils.java at master \- GitHub, [https://github.com/razorpay/razorpay-java/blob/master/src/main/java/com/razorpay/Utils.java](https://github.com/razorpay/razorpay-java/blob/master/src/main/java/com/razorpay/Utils.java)  
43. Dispute Event Webhooks | Cashfree Payments, [https://www.cashfree.com/docs/api-reference/payments/latest/disputes/dispute-webhooks](https://www.cashfree.com/docs/api-reference/payments/latest/disputes/dispute-webhooks)  
44. Webhook Signature Verification \- Cashfree Payments, [https://www.cashfree.com/docs/api-reference/vrs/webhook-signature-verification](https://www.cashfree.com/docs/api-reference/vrs/webhook-signature-verification)  
45. Razorpay Payment Verification Failed in MERN Stack \- DEV Community, [https://dev.to/vjygour/razorpay-payment-verification-failed-in-mern-stack-1386](https://dev.to/vjygour/razorpay-payment-verification-failed-in-mern-stack-1386)  
46. Efficient Database Transactions Using PostgreSQL: Best Practices and Optimization Techniques | by Miftahul Huda, [https://iniakunhuda.medium.com/efficient-database-transactions-using-postgresql-best-practices-and-optimization-techniques-9652d4ce53c0](https://iniakunhuda.medium.com/efficient-database-transactions-using-postgresql-best-practices-and-optimization-techniques-9652d4ce53c0)