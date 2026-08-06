package com.fooddelivery.payments.controller;

import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.service.WebhookProcessingService;
import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import com.fooddelivery.common.enums.PaymentGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.ContentCachingRequestWrapper;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/webhooks")
@Slf4j
public class WebhookController {
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

        return processWebhook(request, PaymentGateway.RAZORPAY, signature, null, eventId);
    }

    @PostMapping("/cashfree")
    public ResponseEntity<String> handleCashfreeWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature,
            @RequestHeader(value = "x-webhook-timestamp", required = false) String timestamp,
            @RequestHeader(value = "x-webhook-event-id", required = false) String eventId) {

        return processWebhook(request, PaymentGateway.CASHFREE, signature, timestamp, eventId);
    }

    @PostMapping("/vyapar")
    public ResponseEntity<String> handleVyaparWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "X-VyaparGateway-Signature", required = false) String signature,
            @RequestHeader(value = "X-VyaparGateway-Event-Id", required = false) String eventId) {

        return processWebhook(request, PaymentGateway.VYAPAR, signature, null, eventId);
    }

    private ResponseEntity<String> processWebhook(HttpServletRequest request, PaymentGateway gateway, String signature, String timestamp, String headerEventId) {
        try {
            logger.info("Consumed webhook event from gateway: {}", gateway);
            if (signature == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing Signature");
            }

            byte[] rawBodyBytes;
            if (request instanceof com.fooddelivery.common.filter.RequestCachingFilter.CachedBodyHttpServletRequest) {
                rawBodyBytes = ((com.fooddelivery.common.filter.RequestCachingFilter.CachedBodyHttpServletRequest) request).getCachedBody();
            } else if (request instanceof ContentCachingRequestWrapper) {
                ContentCachingRequestWrapper wrapper = (ContentCachingRequestWrapper) request;
                rawBodyBytes = wrapper.getContentAsByteArray();
                if (rawBodyBytes.length == 0) {
                    rawBodyBytes = wrapper.getInputStream().readAllBytes();
                }
            } else {
                rawBodyBytes = request.getInputStream().readAllBytes();
            }
            String rawBody = new String(rawBodyBytes, StandardCharsets.UTF_8);

            String eventId = headerEventId;
            if (eventId == null || eventId.trim().isEmpty()) {
                // Generate deterministic hash of payload to prevent replay attacks
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(rawBodyBytes);
                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                eventId = hexString.toString();
            }

            if (webhookProcessingService.isEventProcessed(eventId)) {
                logger.info("Webhook event {} already processed. Returning 200 OK.", eventId);
                return ResponseEntity.ok("Already Processed");
            }

            IPaymentGatewayStrategy strategy = orchestrator.getStrategy(gateway);
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
