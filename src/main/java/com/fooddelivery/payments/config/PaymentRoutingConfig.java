package com.fooddelivery.payments.config;

import com.fooddelivery.common.enums.PaymentGateway;
import com.fooddelivery.common.enums.PaymentMethod;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "payments")
public class PaymentRoutingConfig {

    private Map<PaymentMethod, PaymentGateway> routing;

    public Map<PaymentMethod, PaymentGateway> getRouting() {
        return routing;
    }

    public void setRouting(Map<PaymentMethod, PaymentGateway> routing) {
        this.routing = routing;
    }

    public PaymentGateway getGatewayForMethod(PaymentMethod method) {
        if (routing == null || method == null) {
            return null; // Handle default or fallback logic upstream
        }
        return routing.get(method);
    }
}
