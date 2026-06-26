package com.fooddelivery.payments.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import com.fooddelivery.payments.model.enums.DeliveryStatus;

@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
public class WebhookDelivery extends BaseEntity {

    @Column(name = "gateway_name", nullable = false)
    private String gatewayName;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(columnDefinition = "JSONB", nullable = false)
    private String payload; // Store JSON as string, postgres maps it to jsonb if mapped right or we can just use string

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private DeliveryStatus processingStatus = DeliveryStatus.PENDING;

    @Column(name = "error_log", columnDefinition = "TEXT")
    private String errorLog;
}
