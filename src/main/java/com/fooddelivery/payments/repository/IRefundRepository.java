package com.fooddelivery.payments.repository;

import com.fooddelivery.payments.model.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface IRefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByGatewayRefundId(String gatewayRefundId);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.status = 'COMPLETED' AND CAST(r.createdAt AS date) = :date")
    java.math.BigDecimal sumRefundedAmountByDate(@org.springframework.data.repository.query.Param("date") java.time.LocalDate date);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.status = 'COMPLETED' AND CAST(r.createdAt AS date) = :date AND r.transaction.paymentIntent.gatewayName = :gatewayName")
    java.math.BigDecimal sumRefundedAmountByDateAndGateway(@org.springframework.data.repository.query.Param("date") java.time.LocalDate date, @org.springframework.data.repository.query.Param("gatewayName") com.fooddelivery.common.enums.PaymentGateway gatewayName);
}
