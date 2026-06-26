package com.fooddelivery.payments.service;

import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import com.fooddelivery.payments.service.gateway.PaymentRequestContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaymentGatewayOrchestrator {

    private final Map<String, IPaymentGatewayStrategy> strategies;

    public PaymentGatewayOrchestrator(List<IPaymentGatewayStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(IPaymentGatewayStrategy::getGatewayName, Function.identity()));
    }

    public IPaymentGatewayStrategy getStrategy(String gatewayName) {
        IPaymentGatewayStrategy strategy = strategies.get(gatewayName.toUpperCase());
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported gateway: " + gatewayName);
        }
        return strategy;
    }

    public String createOrder(String gatewayName, PaymentRequestContext context) {
        return getStrategy(gatewayName).createOrder(context);
    }
}
