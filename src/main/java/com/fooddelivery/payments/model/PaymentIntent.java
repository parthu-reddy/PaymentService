package com.fooddelivery.payments.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import com.fooddelivery.common.enums.PaymentGateway;
import com.fooddelivery.common.constants.PaymentIntentStatus;

@Entity
@Table(name = "payment_intents")@lombok.AllArgsConstructor
@lombok.NoArgsConstructor
@lombok.Data

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

    public String getOrderId() {
        return this.orderId;
    }

    public PaymentGateway getGatewayName() {
        return this.gatewayName;
    }

    public String getGatewayOrderId() {
        return this.gatewayOrderId;
    }

    public BigDecimal getAmount() {
        return this.amount;
    }

    public BigDecimal getAmountRefunded() {
        return this.amountRefunded;
    }

    public PaymentIntentStatus getStatus() {
        return this.status;
    }

    public String getIdempotencyKey() {
        return this.idempotencyKey;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public void setGatewayName(PaymentGateway gatewayName) {
        this.gatewayName = gatewayName;
    }

    public void setGatewayOrderId(String gatewayOrderId) {
        this.gatewayOrderId = gatewayOrderId;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public void setAmountRefunded(BigDecimal amountRefunded) {
        this.amountRefunded = amountRefunded;
    }

    public void setStatus(PaymentIntentStatus status) {
        this.status = status;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

}
