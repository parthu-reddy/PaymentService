package com.fooddelivery.payments.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import com.fooddelivery.common.enums.TransactionStatus;

@Entity
@Table(name = "transactions")@lombok.AllArgsConstructor
@lombok.NoArgsConstructor
@lombok.Data

public class Transaction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_intent_id", nullable = false)
    private PaymentIntent paymentIntent;

    @Column(name = "gateway_payment_id", nullable = false, unique = true)
    private String gatewayPaymentId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "payment_network")
    private String paymentNetwork;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionStatus status;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "amount_refunded", nullable = false)
    private BigDecimal amountRefunded = BigDecimal.ZERO;

    public PaymentIntent getPaymentIntent() {
        return this.paymentIntent;
    }

    public String getGatewayPaymentId() {
        return this.gatewayPaymentId;
    }

    public BigDecimal getAmount() {
        return this.amount;
    }

    public String getPaymentMethod() {
        return this.paymentMethod;
    }

    public String getPaymentNetwork() {
        return this.paymentNetwork;
    }

    public TransactionStatus getStatus() {
        return this.status;
    }

    public String getErrorCode() {
        return this.errorCode;
    }

    public String getErrorMessage() {
        return this.errorMessage;
    }

    public Instant getCapturedAt() {
        return this.capturedAt;
    }

    public BigDecimal getAmountRefunded() {
        return this.amountRefunded;
    }

    public void setPaymentIntent(PaymentIntent paymentIntent) {
        this.paymentIntent = paymentIntent;
    }

    public void setGatewayPaymentId(String gatewayPaymentId) {
        this.gatewayPaymentId = gatewayPaymentId;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public void setPaymentNetwork(String paymentNetwork) {
        this.paymentNetwork = paymentNetwork;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    public void setAmountRefunded(BigDecimal amountRefunded) {
        this.amountRefunded = amountRefunded;
    }

}
