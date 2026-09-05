package com.fooddelivery.payments.controller;

import com.fooddelivery.common.constants.KafkaConstants;
import com.fooddelivery.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.service.WebhookProcessingService;
import com.fooddelivery.payments.model.enums.DeliveryStatus;

import com.fooddelivery.common.outbox.entity.OutboxEventEntity;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.common.enums.OutboxStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/admin/payments/dlq")
@lombok.extern.slf4j.Slf4j
public class AdminDlqController {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxEventRepository outboxEventRepository;
    private final IWebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookProcessingService webhookProcessingService;

    public AdminDlqController(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, OutboxEventRepository outboxEventRepository, IWebhookDeliveryRepository webhookDeliveryRepository, WebhookProcessingService webhookProcessingService) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.outboxEventRepository = outboxEventRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.webhookProcessingService = webhookProcessingService;
    }

    /**
     * Allows an admin to manually retry a failed Kafka event by providing its payload.
     * This is useful for messages that ended up in the DLT (Dead Letter Topic)
     * and need to be re-processed after a bug fix or data correction.
     */
    @PostMapping("/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> retryDlqEvent(
            @RequestBody Map<String, Object> payload, 
            @RequestParam(required = false) String topic,
            @RequestHeader(value = "eventId", required = false) String eventId) {
        
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            String targetTopic = topic != null && !topic.isEmpty() ? topic : KafkaConstants.TOPIC_ORDER_EVENTS;
            
            log.info("Admin manually retrying DLQ event in PaymentService to topic {}: {}", targetTopic, jsonPayload);
            
            String partitionKey = null;
            if (payload.containsKey("aggregateId") && payload.get("aggregateId") != null) {
                partitionKey = payload.get("aggregateId").toString();
            } else if (payload.containsKey("payload") && payload.get("payload") instanceof Map) {
                Map<String, Object> innerPayload = (Map<String, Object>) payload.get("payload");
                if (innerPayload.containsKey("orderId") && innerPayload.get("orderId") != null) {
                    partitionKey = innerPayload.get("orderId").toString();
                } else if (innerPayload.containsKey("gatewayOrderId") && innerPayload.get("gatewayOrderId") != null) {
                    partitionKey = innerPayload.get("gatewayOrderId").toString();
                }
            }
            
            org.springframework.messaging.support.MessageBuilder<String> builder = org.springframework.messaging.support.MessageBuilder
                    .withPayload(jsonPayload)
                    .setHeader(org.springframework.kafka.support.KafkaHeaders.TOPIC, targetTopic);
            
            if (partitionKey != null) {
                builder.setHeader(org.springframework.kafka.support.KafkaHeaders.KEY, partitionKey);
            }
            if (eventId != null) {
                builder.setHeader("eventId", eventId);
            } else if (payload.containsKey("eventId") && payload.get("eventId") != null) {
                builder.setHeader("eventId", payload.get("eventId").toString());
            }

            kafkaTemplate.send(builder.build());
            
            return ResponseEntity.ok(ApiResponse.success("Event republished successfully to " + targetTopic, "Successfully queued for retry"));
        } catch (Exception e) {
            log.error("Failed to retry DLQ event in PaymentService", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to republish event: " + e.getMessage()));
        }
    }

    @GetMapping("/outbox")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<org.springframework.data.domain.Page<OutboxEventEntity>> getOutboxDlqEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        org.springframework.data.domain.Page<OutboxEventEntity> outboxPage = 
            outboxEventRepository.findByStatus(OutboxStatus.DLQ, pageable);
            
        return ResponseEntity.ok(outboxPage);
    }

    @PostMapping("/outbox/{eventId}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> retryOutboxDlqEvent(@PathVariable java.util.UUID eventId) {
        try {
            OutboxEventEntity event = outboxEventRepository.findById(eventId)
                    .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + eventId));
            
            if (event.getStatus() != OutboxStatus.DLQ) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Event can only be retried if status is DLQ. Current status: " + event.getStatus()));
            }
            
            log.info("Admin manually retrying outbox DLQ event: {}", eventId);
            
            event.setStatus(OutboxStatus.UNPROCESSED);
            event.setRetryCount(0);
            outboxEventRepository.save(event);
            
            return ResponseEntity.ok(ApiResponse.success("Outbox event queued for retry", "Successfully reset to UNPROCESSED"));
        } catch (Exception e) {
            log.error("Failed to retry outbox DLQ event", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to retry outbox event: " + e.getMessage()));
        }
    }

    @GetMapping("/webhooks")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<org.springframework.data.domain.Page<WebhookDelivery>> getFailedWebhooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        org.springframework.data.domain.Page<WebhookDelivery> failedWebhooks = 
            webhookDeliveryRepository.findByProcessingStatus(DeliveryStatus.FAILED, pageable);
            
        return ResponseEntity.ok(failedWebhooks);
    }

    @PostMapping("/webhooks/{eventId}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> retryWebhookEvent(@PathVariable String eventId) {
        try {
            WebhookDelivery event = webhookDeliveryRepository.findByEventId(eventId)
                    .orElseThrow(() -> new IllegalArgumentException("Webhook event not found: " + eventId));
            
            if (event.getProcessingStatus() != DeliveryStatus.FAILED) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Webhook can only be retried if status is FAILED. Current status: " + event.getProcessingStatus()));
            }
            
            log.info("Admin manually retrying webhook DLQ event: {}", eventId);
            webhookProcessingService.retryWebhook(event);
            
            return ResponseEntity.ok(ApiResponse.success("Webhook event retried successfully", "Webhook processed without errors"));
        } catch (Exception e) {
            log.error("Failed to retry webhook DLQ event", e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Failed to retry webhook event: " + e.getMessage()));
        }
    }
}
