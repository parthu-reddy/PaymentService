package com.fooddelivery.payments.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import com.fooddelivery.common.enums.PaymentGateway;
import com.fooddelivery.common.constants.PaymentIntentStatus;

@Entity
@Table(name = "payment_intents")
@Getter
@Setter
public class PaymentIntent extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway_name", nullable = false)
    private PaymentGateway gatewayName;

    @Column(name = "gateway_order_id", nullable = false, unique = true)
    private String gatewayOrderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "amount_refunded", nullable = false)
    private BigDecimal amountRefunded = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PaymentIntentStatus status = PaymentIntentStatus.INITIATED;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;
}
