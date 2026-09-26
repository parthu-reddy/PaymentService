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
    public Map<String, BigDecimal> getDailyTotals(@RequestParam("from") java.time.Instant from, @RequestParam("to") java.time.Instant to, @RequestParam(value = "gatewayName", required = false) String gatewayName) {
        // [from, to) is the ledger's accounting day, computed once in LedgerService.AccountingCalendar
        // so that every service sums exactly the same instants. This service interprets no dates.
        BigDecimal capturedAmount;
        BigDecimal refundedAmount;
        if (gatewayName != null && !gatewayName.isEmpty()) {
            com.fooddelivery.common.enums.PaymentGateway gateway = com.fooddelivery.common.enums.PaymentGateway.valueOf(gatewayName);
            capturedAmount = transactionRepository.sumCapturedAmountInWindowByGateway(from, to, gateway);
            refundedAmount = refundRepository.sumRefundedAmountInWindowByGateway(from, to, gateway);
        } else {
            capturedAmount = transactionRepository.sumCapturedAmountInWindow(from, to);
            refundedAmount = refundRepository.sumRefundedAmountInWindow(from, to);
        }

        Map<String, BigDecimal> result = new HashMap<>();
        result.put("capturedAmount", capturedAmount);
        result.put("refundedAmount", refundedAmount);
        return result;
    }
}
