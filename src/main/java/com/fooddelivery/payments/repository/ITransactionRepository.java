package com.fooddelivery.payments.repository;

import com.fooddelivery.payments.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface ITransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByGatewayPaymentId(String gatewayPaymentId);
    Optional<Transaction> findFirstByPaymentIntentIdAndStatusOrderByCreatedAtDesc(UUID paymentIntentId, com.fooddelivery.common.enums.TransactionStatus status);
    
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT t FROM Transaction t WHERE t.paymentIntent.id = :paymentIntentId AND t.status = :status ORDER BY t.createdAt DESC LIMIT 1")
    Optional<Transaction> findLockedFirstByPaymentIntentIdAndStatusOrderByCreatedAtDesc(@org.springframework.data.repository.query.Param("paymentIntentId") UUID paymentIntentId, @org.springframework.data.repository.query.Param("status") com.fooddelivery.common.enums.TransactionStatus status);
    
    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.status = 'SUCCESS' AND t.createdAt >= :from AND t.createdAt < :to")
    java.math.BigDecimal sumCapturedAmountInWindow(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.status = 'SUCCESS' AND t.createdAt >= :from AND t.createdAt < :to AND t.paymentIntent.gatewayName = :gatewayName")
    java.math.BigDecimal sumCapturedAmountInWindowByGateway(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to, @org.springframework.data.repository.query.Param("gatewayName") com.fooddelivery.common.enums.PaymentGateway gatewayName);
}
