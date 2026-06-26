package com.fooddelivery.payments.model;

import java.math.BigDecimal;

public record PaymentSucceededEvent(
    String orderId,
    String gatewayOrderId,
    BigDecimal amount,
    String gatewayName
) {}
