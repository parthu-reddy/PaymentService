package com.fooddelivery.payments.repository;

import com.fooddelivery.payments.model.PaymentIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface IPaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {
    Optional<PaymentIntent> findByGatewayOrderId(String gatewayOrderId);
    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);
}
