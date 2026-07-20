package com.fooddelivery.payments.repository;

import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.common.constants.PaymentIntentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface IPaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {
    Optional<PaymentIntent> findByGatewayOrderId(String gatewayOrderId);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentIntent p WHERE p.gatewayOrderId = :gatewayOrderId")
    Optional<PaymentIntent> findLockedByGatewayOrderId(@Param("gatewayOrderId") String gatewayOrderId);
    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);
    java.util.List<PaymentIntent> findTop100ByStatusAndCreatedAtBefore(PaymentIntentStatus status, java.time.ZonedDateTime createdAt);
}
