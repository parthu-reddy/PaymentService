package com.fooddelivery.payments.controller;

import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.service.gateway.PaymentRequestContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@CrossOrigin(origins = "*") // Allows cross-origin requests from our web app integration
public class PaymentController {

    private final PaymentGatewayOrchestrator orchestrator;

    @Autowired
    public PaymentController(PaymentGatewayOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public static class CreateOrderRequest {
        public String internalOrderId;
        public BigDecimal amountInInr;
        public String customerPhone;
    }

    @PostMapping("/create-order")
    public ResponseEntity<String> createOrder(
            @RequestParam String gateway,
            @RequestBody CreateOrderRequest request) {
        try {
            PaymentRequestContext context = PaymentRequestContext.builder()
                .internalOrderId(UUID.fromString(request.internalOrderId))
                .amountInInr(request.amountInInr)
                .customerPhone(request.customerPhone)
                .build();
            
            String intentOrOrderId = orchestrator.createOrder(gateway, context);
            return ResponseEntity.ok(intentOrOrderId);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(e.getMessage());
        }
    }
}
