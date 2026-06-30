package com.fooddelivery.payments.service;

import com.fooddelivery.common.event.PaymentSucceededEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class PaymentEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(PaymentEventPublisher.class);
    private static final String TOPIC = "payment-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishPaymentSuccess(PaymentSucceededEvent event) {
        try {
            logger.info("Publishing payment success event for order: {}", event.orderId());
            kafkaTemplate.send(TOPIC, event.orderId(), event);
        } catch (Exception e) {
            // We log the error but don't fail the transaction, as Kafka might be down
            // In a robust system, we would save to an 'outbox' table first.
            logger.error("Failed to publish payment success event to Kafka for order: {}", event.orderId(), e);
        }
    }

    public void publishPaymentFailure(com.fooddelivery.common.event.PaymentFailedEvent event) {
        try {
            logger.info("Publishing payment failure event for order: {}", event.orderId());
            kafkaTemplate.send(TOPIC, event.orderId().toString(), event);
        } catch (Exception e) {
            logger.error("Failed to publish payment failure event to Kafka for order: {}", event.orderId(), e);
        }
    }
}
