package com.fooddelivery.payments.repository;

import com.fooddelivery.payments.model.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Optional;

@Repository
public interface IWebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {
    Optional<WebhookDelivery> findByEventId(String eventId);
    java.util.List<WebhookDelivery> findTop100ByProcessingStatusAndCreatedAtBefore(com.fooddelivery.payments.model.enums.DeliveryStatus status, java.time.Instant createdAt);
    org.springframework.data.domain.Page<WebhookDelivery> findByProcessingStatus(com.fooddelivery.payments.model.enums.DeliveryStatus status, org.springframework.data.domain.Pageable pageable);
}
