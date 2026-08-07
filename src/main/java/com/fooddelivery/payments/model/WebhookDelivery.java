package com.fooddelivery.payments.model;

import jakarta.persistence.*;
import com.fooddelivery.payments.model.enums.DeliveryStatus;
import com.fooddelivery.common.enums.PaymentGateway;

@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway_name", nullable = false)
    private PaymentGateway gatewayName;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload; // Store JSON as string, postgres maps it to jsonb if mapped right or we can just use string

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private DeliveryStatus processingStatus = DeliveryStatus.PENDING;

    @Column(name = "error_log", columnDefinition = "TEXT")
    private String errorLog;

    public PaymentGateway getGatewayName() {
        return this.gatewayName;
    }

    public String getEventId() {
        return this.eventId;
    }

    public String getEventType() {
        return this.eventType;
    }

    public String getPayload() {
        return this.payload;
    }

    public DeliveryStatus getProcessingStatus() {
        return this.processingStatus;
    }

    public String getErrorLog() {
        return this.errorLog;
    }

    public void setGatewayName(PaymentGateway gatewayName) {
        this.gatewayName = gatewayName;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public void setProcessingStatus(DeliveryStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public void setErrorLog(String errorLog) {
        this.errorLog = errorLog;
    }

}
