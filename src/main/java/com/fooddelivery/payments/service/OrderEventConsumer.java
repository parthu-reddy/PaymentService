package com.fooddelivery.payments.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.retry.annotation.Backoff;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import com.fooddelivery.common.enums.PaymentGateway;

@Service
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    
    private final ObjectMapper objectMapper;
    private final PaymentGatewayOrchestrator orchestrator;

    public OrderEventConsumer(ObjectMapper objectMapper, PaymentGatewayOrchestrator orchestrator) {
        this.objectMapper = objectMapper;
        this.orchestrator = orchestrator;
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 10000)
    )
    @KafkaListener(topics = com.fooddelivery.common.constants.KafkaConstants.TOPIC_ORDER_EVENTS, groupId = com.fooddelivery.common.constants.KafkaConstants.GROUP_PAYMENT_SERVICE)
    public void consumeOrderEvents(String payload) {
        try {
            JsonNode rootNode = objectMapper.readTree(payload);
            String eventType = rootNode.path("eventType").asText();
            if (com.fooddelivery.common.constants.EventType.PAYMENT_REFUND_REQUESTED.name().equals(eventType)) {
                log.info("Received PAYMENT_REFUND_REQUESTED event");
                JsonNode payloadNode = rootNode;
                if (rootNode.has("payload")) {
                    String innerPayloadStr = rootNode.get("payload").asText();
                    if (innerPayloadStr != null && !innerPayloadStr.trim().isEmpty()) {
                         try {
                              payloadNode = objectMapper.readTree(innerPayloadStr);
                         } catch (Exception e) {
                              // fallback
                         }
                    }
                }
                
                String gatewayOrderId = payloadNode.path("gatewayOrderId").asText(null);
                double amountInInr = payloadNode.path("amountInInr").asDouble(0);
                String gatewayNameStr = payloadNode.path("gatewayName").asText(null);
                
                if (gatewayOrderId != null && amountInInr > 0 && gatewayNameStr != null) {
                    try {
                        PaymentGateway gateway = PaymentGateway.valueOf(gatewayNameStr.toUpperCase());
                        log.info("Initiating refund for gatewayOrderId: {}, gateway: {}", gatewayOrderId, gateway);
                        boolean success = orchestrator.initiateRefund(gateway, gatewayOrderId, amountInInr, "Order cancelled or rejected");
                        if (success) {
                            log.info("Refund initiated successfully for gatewayOrderId: {}", gatewayOrderId);
                        } else {
                            log.error("Failed to initiate refund for gatewayOrderId: {}", gatewayOrderId);
                            throw new RuntimeException("Refund failed for gatewayOrderId: " + gatewayOrderId);
                        }
                    } catch (IllegalArgumentException e) {
                        log.error("Invalid gateway name in refund requested event: {}", gatewayNameStr);
                    }
                } else {
                    log.error("Missing required fields in PAYMENT_REFUND_REQUESTED event payload: {}", payload);
                }
            }
        } catch (RuntimeException e) {
            log.error("Transient error processing order event in PaymentGatewayIntegration, triggering retry", e);
            throw e; // Let Kafka retry mechanism handle it (or DLQ if exhausted)
        } catch (Exception e) {
            log.error("Unrecoverable error processing order event in PaymentGatewayIntegration", e);
        }
    }

    @DltHandler
    public void processDeadLetterTopic(@Payload(required = false) String payload, @org.springframework.messaging.handler.annotation.Header(name = org.springframework.kafka.support.KafkaHeaders.EXCEPTION_MESSAGE, required = false) String exceptionMessage) {
        log.error("Terminal failure for event in PaymentGateway. Payload: {}. Moving to manual intervention queue. Exception: {}", payload, exceptionMessage);
    }
}
