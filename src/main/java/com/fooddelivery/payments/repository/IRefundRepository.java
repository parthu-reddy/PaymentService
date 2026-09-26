package com.fooddelivery.payments.repository;

import com.fooddelivery.payments.model.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface IRefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByGatewayRefundId(String gatewayRefundId);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.status = 'COMPLETED' AND r.createdAt >= :from AND r.createdAt < :to")
    java.math.BigDecimal sumRefundedAmountInWindow(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.status = 'COMPLETED' AND r.createdAt >= :from AND r.createdAt < :to AND r.transaction.paymentIntent.gatewayName = :gatewayName")
    java.math.BigDecimal sumRefundedAmountInWindowByGateway(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to, @org.springframework.data.repository.query.Param("gatewayName") com.fooddelivery.common.enums.PaymentGateway gatewayName);
}
