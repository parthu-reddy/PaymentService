package com.fooddelivery.payments.service;

import com.fooddelivery.common.constants.KafkaConstants;
import com.fooddelivery.payments.entity.OutboxEventEntity;
import com.fooddelivery.payments.repository.IOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxEventPoller {

    private final IOutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollOutboxEvents() {
        List<OutboxEventEntity> unprocessedEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc("UNPROCESSED");
        
        for (OutboxEventEntity event : unprocessedEvents) {
            try {
                kafkaTemplate.send(KafkaConstants.TOPIC_PAYMENT_EVENTS, event.getAggregateId(), event.getPayload());
                
                event.setStatus("PROCESSED");
                event.setProcessedAt(LocalDateTime.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event ID: {}", event.getId(), e);
                event.setStatus("FAILED");
                event.setErrorMessage(e.getMessage());
                outboxEventRepository.save(event);
            }
        }
    }
}
