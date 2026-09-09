package com.fooddelivery.payments.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/payments")
public class InternalPaymentController {

    private final com.fooddelivery.payments.repository.ITransactionRepository transactionRepository;
    private final com.fooddelivery.payments.repository.IRefundRepository refundRepository;

    public InternalPaymentController(
            com.fooddelivery.payments.repository.ITransactionRepository transactionRepository,
            com.fooddelivery.payments.repository.IRefundRepository refundRepository) {
        this.transactionRepository = transactionRepository;
        this.refundRepository = refundRepository;
    }

    // Endpoint: /api/v1/internal/payments/daily-totals
    @GetMapping("/daily-totals")
    @PreAuthorize("hasRole('SERVICE')")
    public Map<String, BigDecimal> getDailyTotals(@RequestParam("date") LocalDate date, @RequestParam(value = "gatewayName", required = false) String gatewayName) {
        BigDecimal capturedAmount;
        BigDecimal refundedAmount;
        if (gatewayName != null && !gatewayName.isEmpty()) {
            com.fooddelivery.common.enums.PaymentGateway gateway = com.fooddelivery.common.enums.PaymentGateway.valueOf(gatewayName);
            capturedAmount = transactionRepository.sumCapturedAmountByDateAndGateway(date, gateway);
            refundedAmount = refundRepository.sumRefundedAmountByDateAndGateway(date, gateway);
        } else {
            capturedAmount = transactionRepository.sumCapturedAmountByDate(date);
            refundedAmount = refundRepository.sumRefundedAmountByDate(date);
        }

        Map<String, BigDecimal> result = new HashMap<>();
        result.put("capturedAmount", capturedAmount);
        result.put("refundedAmount", refundedAmount);
        return result;
    }
}
