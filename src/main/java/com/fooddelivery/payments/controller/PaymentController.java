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
@RequestMapping("/api/v1/internal/payments")
@CrossOrigin(origins = "${cors.allowed-origins:*}") // Allows cross-origin requests from configured domains
@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
@lombok.extern.slf4j.Slf4j
public class PaymentController {

    private final PaymentGatewayOrchestrator orchestrator;
    private final com.fooddelivery.payments.repository.IPaymentIntentRepository paymentIntentRepository;
    private final com.fooddelivery.payments.config.PaymentRoutingConfig routingConfig;
    private final com.fooddelivery.payments.service.PaymentCompletionScheduler paymentCompletionScheduler;

    @Autowired
    public PaymentController(
            PaymentGatewayOrchestrator orchestrator,
            com.fooddelivery.payments.repository.IPaymentIntentRepository paymentIntentRepository,
            com.fooddelivery.payments.config.PaymentRoutingConfig routingConfig,
            com.fooddelivery.payments.service.PaymentCompletionScheduler paymentCompletionScheduler) {
        this.orchestrator = orchestrator;
        this.paymentIntentRepository = paymentIntentRepository;
        this.routingConfig = routingConfig;
        this.paymentCompletionScheduler = paymentCompletionScheduler;
    }

    public static class CreateOrderRequest {
        @NotNull(message = "internalOrderId cannot be null")
        @Pattern(regexp = "^([a-zA-Z0-9_-]+_)?([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$", message = "internalOrderId must be a valid UUID, optionally prefixed with a string like WALLET_")
        public String internalOrderId;

        @NotNull(message = "amountInInr cannot be null")
        @Positive(message = "amountInInr must be greater than zero")
        public BigDecimal amountInInr;

        public String customerPhone;

        @NotNull(message = "paymentMethod cannot be null")
        public com.fooddelivery.common.enums.PaymentMethod paymentMethod;
    }

    @PostMapping("/create-order")
    public ResponseEntity<?> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        try {
            String effectiveIdempotencyKey = idempotencyKey != null ? idempotencyKey : request.internalOrderId;

            PaymentGateway gateway = routingConfig.getGatewayForMethod(request.paymentMethod);
            if (gateway == null) {
                log.warn("PAYMENT_METHOD_REJECTED internalOrderId={} paymentMethod={}", request.internalOrderId, request.paymentMethod);
                return ResponseEntity.badRequest().body("Unsupported payment method: " + request.paymentMethod);
            }

            log.info("PAYMENT_CREATE_REQUEST internalOrderId={} paymentMethod={} gateway={} amount={}",
                    request.internalOrderId, request.paymentMethod, gateway, request.amountInInr);

            java.util.Optional<com.fooddelivery.payments.model.PaymentIntent> existingIntent = paymentIntentRepository.findByIdempotencyKey(effectiveIdempotencyKey);
            if (existingIntent.isPresent()) {
                com.fooddelivery.payments.model.PaymentIntent intent = existingIntent.get();
                if (!intent.getGatewayName().equals(gateway)) {
                    return ResponseEntity.badRequest().body("Order already initiated with different gateway: " + intent.getGatewayName());
                }
                log.info("PAYMENT_CREATE_IDEMPOTENT_HIT internalOrderId={} gateway={} gatewayOrderId={}",
                        request.internalOrderId, gateway, intent.getGatewayOrderId());
                if (intent.getStatus() == com.fooddelivery.common.constants.PaymentIntentStatus.INITIATED) {
                    paymentCompletionScheduler.afterIntentPersisted(
                            intent.getOrderId(), intent.getGatewayOrderId(), intent.getAmount());
                }
                return ResponseEntity.ok(new com.fooddelivery.common.dto.payment.CreatePaymentResponse(
                        intent.getGatewayOrderId(), intent.getGatewayName()));
            }

            PaymentRequestContext context = PaymentRequestContext.builder()
                .internalOrderId(request.internalOrderId)
                .amountInInr(request.amountInInr)
                .customerPhone(request.customerPhone)
                .paymentMethod(request.paymentMethod)
                .build();
            
            String intentOrOrderId = orchestrator.createOrder(gateway, context);

            com.fooddelivery.payments.model.PaymentIntent intent = new com.fooddelivery.payments.model.PaymentIntent();
            intent.setOrderId(request.internalOrderId);
            intent.setGatewayName(gateway);
            intent.setGatewayOrderId(intentOrOrderId);
            intent.setAmount(request.amountInInr);
            intent.setIdempotencyKey(effectiveIdempotencyKey);
            paymentIntentRepository.save(intent);
            paymentCompletionScheduler.afterIntentPersisted(
                    request.internalOrderId, intentOrOrderId, request.amountInInr);

            log.info("PAYMENT_CREATE_ACCEPTED internalOrderId={} gateway={} gatewayOrderId={}",
                    request.internalOrderId, gateway, intentOrOrderId);
            return ResponseEntity.ok(new com.fooddelivery.common.dto.payment.CreatePaymentResponse(intentOrOrderId, gateway));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid request: " + e.getMessage());
        } catch (Exception e) {
            log.error("PAYMENT_CREATE_FAILED internalOrderId={} paymentMethod={} errorType={} error={}",
                    request.internalOrderId, request.paymentMethod, e.getClass().getSimpleName(), e.getMessage(), e);
            return ResponseEntity.status(500).body("Internal server error: " + e.getMessage());
        }
    }

    public static class RefundRequest {
        @NotNull(message = "gatewayOrderId cannot be null")
        public String gatewayOrderId;

        @NotNull(message = "refundId cannot be null")
        public String refundId;

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
                    request.refundId,
                    request.amountInInr,
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
