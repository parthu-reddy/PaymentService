package com.fooddelivery.payments.repository;

import com.fooddelivery.payments.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface ITransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByGatewayPaymentId(String gatewayPaymentId);
    Optional<Transaction> findFirstByPaymentIntentIdAndStatusOrderByCreatedAtDesc(UUID paymentIntentId, String status);
}
