package com.fooddelivery.payments.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import com.fooddelivery.common.enums.RefundStatus;

@Entity
@Table(name = "refunds")
@Getter
@Setter
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
    private RefundStatus status = RefundStatus.PENDING;
}
