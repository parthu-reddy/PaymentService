package com.fooddelivery.payments.controller;

import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.service.WebhookProcessingService;
import com.fooddelivery.payments.service.gateway.PaymentGatewayStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.ContentCachingRequestWrapper;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);
    
    private final WebhookProcessingService webhookProcessingService;
    private final PaymentGatewayOrchestrator orchestrator;

    public WebhookController(WebhookProcessingService webhookProcessingService, PaymentGatewayOrchestrator orchestrator) {
        this.webhookProcessingService = webhookProcessingService;
        this.orchestrator = orchestrator;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<String> handleRazorpayWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "x-razorpay-signature", required = false) String signature,
            @RequestHeader(value = "x-razorpay-event-id", required = false) String eventId) {

        return processWebhook(request, "RAZORPAY", signature, null, eventId);
    }

    @PostMapping("/cashfree")
    public ResponseEntity<String> handleCashfreeWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature,
            @RequestHeader(value = "x-webhook-timestamp", required = false) String timestamp) {

        // Cashfree webhooks need a unique identifier. Sometimes they provide an event ID in payload or header.
        // We'll generate a random UUID if not explicitly passed as a header to ensure uniqueness in our DB for this example.
        String eventId = request.getHeader("x-webhook-event-id");
        if (eventId == null) {
            eventId = java.util.UUID.randomUUID().toString();
        }
        
        return processWebhook(request, "CASHFREE", signature, timestamp, eventId);
    }

    @PostMapping("/vyapar")
    public ResponseEntity<String> handleVyaparWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "X-VyaparGateway-Signature", required = false) String signature) {

        String eventId = request.getHeader("X-VyaparGateway-Event-Id");
        if (eventId == null) {
            eventId = java.util.UUID.randomUUID().toString();
        }
        
        return processWebhook(request, "VYAPAR", signature, null, eventId);
    }

    private ResponseEntity<String> processWebhook(HttpServletRequest request, String gateway, String signature, String timestamp, String eventId) {
        try {
            if (signature == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing Signature");
            }

            ContentCachingRequestWrapper wrapper = (ContentCachingRequestWrapper) request;
            byte[] rawBodyBytes = wrapper.getContentAsByteArray();
            String rawBody = new String(rawBodyBytes, StandardCharsets.UTF_8);

            PaymentGatewayStrategy strategy = orchestrator.getStrategy(gateway);
            boolean isValid = strategy.verifyWebhookSignature(rawBody, signature, timestamp);
            
            if (!isValid) {
                logger.error("Cryptographic verification failed for {} event", gateway);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Signature Validation Failed");
            }

            webhookProcessingService.processWebhookAsync(eventId, gateway, rawBody);
            return ResponseEntity.ok("Webhook Received and Verified");

        } catch (Exception e) {
            logger.error("Critical failure during webhook ingestion", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
