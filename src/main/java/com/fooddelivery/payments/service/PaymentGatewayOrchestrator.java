package com.fooddelivery.payments.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import com.fooddelivery.payments.service.gateway.PaymentRequestContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fooddelivery.common.enums.PaymentGateway;

@Service
public class PaymentGatewayOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayOrchestrator.class);

    private final Map<PaymentGateway, IPaymentGatewayStrategy> strategies;

    public PaymentGatewayOrchestrator(List<IPaymentGatewayStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(IPaymentGatewayStrategy::getGatewayName, Function.identity()));
    }

    public IPaymentGatewayStrategy getStrategy(PaymentGateway gatewayName) {
        if (gatewayName == null) {
            throw new IllegalArgumentException("Gateway name cannot be null");
        }
        IPaymentGatewayStrategy strategy = strategies.get(gatewayName);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported gateway: " + gatewayName);
        }
        return strategy;
    }

    public String createOrder(PaymentGateway gatewayName, PaymentRequestContext context) {
        log.info("Creating order using gateway: {}", gatewayName);
        try {
            String orderId = getStrategy(gatewayName).createOrder(context);
            log.info("Successfully created order {} using gateway: {}", orderId, gatewayName);
            return orderId;
        } catch (Exception e) {
            log.error("Failed to create order using gateway: {}", gatewayName, e);
            throw e;
        }
    }

    public boolean initiateRefund(PaymentGateway gatewayName, String gatewayOrderId, double amount, String reason) {
        log.info("Initiating refund for order: {} using gateway: {}", gatewayOrderId, gatewayName);
        try {
            boolean success = getStrategy(gatewayName).initiateRefund(gatewayOrderId, amount, reason);
            if (success) {
                log.info("Successfully initiated refund for order: {} using gateway: {}", gatewayOrderId, gatewayName);
            } else {
                log.warn("Failed to initiate refund for order: {} using gateway: {}", gatewayOrderId, gatewayName);
            }
            return success;
        } catch (Exception e) {
            log.error("Error while initiating refund for order: {} using gateway: {}", gatewayOrderId, gatewayName, e);
            throw e;
        }
    }
}
