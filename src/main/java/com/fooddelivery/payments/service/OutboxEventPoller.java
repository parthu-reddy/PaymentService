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
        List<OutboxEventEntity> unprocessedEvents = outboxEventRepository.findUnprocessedEventsAndLock(List.of(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_UNPROCESSED, com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_FAILED));
        
        for (OutboxEventEntity event : unprocessedEvents) {
            try {
                kafkaTemplate.send(KafkaConstants.TOPIC_PAYMENT_EVENTS, event.getAggregateId(), event.getPayload()).get(3, java.util.concurrent.TimeUnit.SECONDS);
                
                event.setStatus(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_PROCESSED);
                event.setProcessedAt(LocalDateTime.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event ID: {}", event.getId(), e);
                event.setStatus(com.fooddelivery.common.constants.AppConstants.OUTBOX_STATUS_FAILED);
                event.setErrorMessage(e.getMessage());
                outboxEventRepository.save(event);
            }
        }
    }
}
