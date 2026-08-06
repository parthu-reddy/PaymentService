package com.fooddelivery.payments.service.gateway;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
public class PaymentRequestContext {
    private String internalOrderId;
    private BigDecimal amountInInr;
    private String receiptRef;
    private String customerPhone;
}
