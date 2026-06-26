package com.fooddelivery.payments.repository;

import com.fooddelivery.payments.model.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByGatewayRefundId(String gatewayRefundId);
}
