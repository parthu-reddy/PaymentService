package com.fooddelivery.payments.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import com.fooddelivery.payments.model.enums.IntentStatus;

@Entity
@Table(name = "payment_intents")
@Getter
@Setter
public class PaymentIntent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "gateway_name", nullable = false)
    private String gatewayName;

    @Column(name = "gateway_order_id", nullable = false, unique = true)
    private String gatewayOrderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "amount_refunded", nullable = false)
    private BigDecimal amountRefunded = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IntentStatus status = IntentStatus.INITIATED;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;
}
