package com.fooddelivery.payments.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import com.fooddelivery.common.enums.TransactionStatus;

@Entity
@Table(name = "transactions")
@Getter
@Setter
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
    private ZonedDateTime capturedAt;

    @Column(name = "amount_refunded", nullable = false)
    private BigDecimal amountRefunded = BigDecimal.ZERO;
}
