package com.fooddelivery.payments.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.service.gateway.PaymentRequestContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;
import com.fooddelivery.common.enums.PaymentGateway;

import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/payments")
@CrossOrigin(origins = "${cors.allowed-origins:*}") // Allows cross-origin requests from configured domains
@PreAuthorize("isAuthenticated()")
@lombok.extern.slf4j.Slf4j
public class PaymentController {

    private final PaymentGatewayOrchestrator orchestrator;
    private final com.fooddelivery.payments.repository.IPaymentIntentRepository paymentIntentRepository;

    @Autowired
    public PaymentController(PaymentGatewayOrchestrator orchestrator, com.fooddelivery.payments.repository.IPaymentIntentRepository paymentIntentRepository) {
        this.orchestrator = orchestrator;
        this.paymentIntentRepository = paymentIntentRepository;
    }

    public static class CreateOrderRequest {
        @NotNull(message = "internalOrderId cannot be null")
        @Pattern(regexp = "^([a-zA-Z0-9_]+_)?([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$", message = "internalOrderId must be a valid UUID, optionally prefixed with a string like WALLET_")
        public String internalOrderId;

        @NotNull(message = "amountInInr cannot be null")
        @Positive(message = "amountInInr must be greater than zero")
        public BigDecimal amountInInr;

        public String customerPhone;
    }

    @PostMapping("/create-order")
    public ResponseEntity<String> createOrder(
            @RequestParam PaymentGateway gateway,
            @Valid @RequestBody CreateOrderRequest request) {
        try {
            PaymentRequestContext context = PaymentRequestContext.builder()
                .internalOrderId(request.internalOrderId)
                .amountInInr(request.amountInInr)
                .customerPhone(request.customerPhone)
                .build();
            
            String intentOrOrderId = orchestrator.createOrder(gateway, context);

            com.fooddelivery.payments.model.PaymentIntent intent = new com.fooddelivery.payments.model.PaymentIntent();
            intent.setOrderId(request.internalOrderId);
            intent.setGatewayName(gateway);
            intent.setGatewayOrderId(intentOrOrderId);
            intent.setAmount(request.amountInInr);
            intent.setIdempotencyKey(UUID.randomUUID().toString());
            paymentIntentRepository.save(intent);

            return ResponseEntity.ok(intentOrOrderId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid request: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Internal server error: " + e.getMessage());
        }
    }

    public static class RefundRequest {
        @NotNull(message = "gatewayOrderId cannot be null")
        public String gatewayOrderId;

        @NotNull(message = "amountInInr cannot be null")
        @Positive(message = "amountInInr must be greater than zero")
        public BigDecimal amountInInr;

        public String reason;
    }

    @PostMapping("/refund")
    public ResponseEntity<String> refundOrder(
            @RequestParam PaymentGateway gateway,
            @Valid @RequestBody RefundRequest request) {
        try {
            boolean success = orchestrator.initiateRefund(
                    gateway,
                    request.gatewayOrderId,
                    request.amountInInr.doubleValue(),
                    request.reason
            );
            if (success) {
                return ResponseEntity.ok("Refund initiated successfully");
            } else {
                return ResponseEntity.status(400).body("Refund failed");
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid request: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Internal server error: " + e.getMessage());
        }
    }
}
