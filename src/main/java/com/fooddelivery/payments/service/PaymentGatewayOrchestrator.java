package com.fooddelivery.payments.service;

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
        return getStrategy(gatewayName).createOrder(context);
    }

    public boolean initiateRefund(PaymentGateway gatewayName, String gatewayOrderId, double amount, String reason) {
        return getStrategy(gatewayName).initiateRefund(gatewayOrderId, amount, reason);
    }
}
