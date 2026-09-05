package com.fooddelivery.payments.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import com.fooddelivery.common.enums.RefundStatus;

@Entity
@Table(name = "refunds")
public class Refund extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Column(name = "gateway_refund_id", unique = true)
    private String gatewayRefundId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status = RefundStatus.REQUESTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_destination")
    private com.fooddelivery.common.enums.RefundDestination refundDestination;

    @Column(name = "internal_refund_id", unique = true)
    private java.util.UUID internalRefundId;

    public Transaction getTransaction() {
        return this.transaction;
    }

    public String getGatewayRefundId() {
        return this.gatewayRefundId;
    }

    public BigDecimal getAmount() {
        return this.amount;
    }

    public String getReason() {
        return this.reason;
    }

    public RefundStatus getStatus() {
        return this.status;
    }

    public com.fooddelivery.common.enums.RefundDestination getRefundDestination() {
        return this.refundDestination;
    }

    public void setTransaction(Transaction transaction) {
        this.transaction = transaction;
    }

    public void setGatewayRefundId(String gatewayRefundId) {
        this.gatewayRefundId = gatewayRefundId;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public void setStatus(RefundStatus status) {
        this.status = status;
    }

    public void setRefundDestination(com.fooddelivery.common.enums.RefundDestination refundDestination) {
        this.refundDestination = refundDestination;
    }

    public java.util.UUID getInternalRefundId() {
        return this.internalRefundId;
    }

    public void setInternalRefundId(java.util.UUID internalRefundId) {
        this.internalRefundId = internalRefundId;
    }

}
