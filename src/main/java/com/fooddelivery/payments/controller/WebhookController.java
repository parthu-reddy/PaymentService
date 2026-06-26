package com.fooddelivery.payments.controller;

import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.service.WebhookProcessingService;
import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import com.fooddelivery.payments.model.enums.PaymentGateway;
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

        return processWebhook(request, PaymentGateway.RAZORPAY.name(), signature, null, eventId);
    }

    @PostMapping("/cashfree")
    public ResponseEntity<String> handleCashfreeWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature,
            @RequestHeader(value = "x-webhook-timestamp", required = false) String timestamp) {

        String eventId = getOrGenerateEventId(request, "x-webhook-event-id");
        return processWebhook(request, PaymentGateway.CASHFREE.name(), signature, timestamp, eventId);
    }

    @PostMapping("/vyapar")
    public ResponseEntity<String> handleVyaparWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "X-VyaparGateway-Signature", required = false) String signature) {

        String eventId = getOrGenerateEventId(request, "X-VyaparGateway-Event-Id");
        return processWebhook(request, PaymentGateway.VYAPAR.name(), signature, null, eventId);
    }

    private String getOrGenerateEventId(HttpServletRequest request, String headerName) {
        String eventId = request.getHeader(headerName);
        return eventId != null ? eventId : java.util.UUID.randomUUID().toString();
    }

    private ResponseEntity<String> processWebhook(HttpServletRequest request, String gateway, String signature, String timestamp, String eventId) {
        try {
            if (webhookProcessingService.isEventProcessed(eventId)) {
                logger.info("Webhook event {} already processed. Returning 200 OK.", eventId);
                return ResponseEntity.ok("Already Processed");
            }
            
            if (signature == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing Signature");
            }

            ContentCachingRequestWrapper wrapper = (ContentCachingRequestWrapper) request;
            byte[] rawBodyBytes = wrapper.getContentAsByteArray();
            if (rawBodyBytes.length == 0) {
                // If the stream hasn't been consumed by any DTO yet, we must consume it here
                rawBodyBytes = wrapper.getInputStream().readAllBytes();
            }
            String rawBody = new String(rawBodyBytes, StandardCharsets.UTF_8);

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
