package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.model.enums.DeliveryStatus;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Service
@lombok.extern.slf4j.Slf4j
public class WebhookDlqJob {

    private final IWebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookProcessingService webhookProcessingService;
    private final StringRedisTemplate redisTemplate;

    public WebhookDlqJob(
            IWebhookDeliveryRepository webhookDeliveryRepository,
            WebhookProcessingService webhookProcessingService,
            StringRedisTemplate redisTemplate) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.webhookProcessingService = webhookProcessingService;
        this.redisTemplate = redisTemplate;
    }

    // Run every 10 minutes
    @Scheduled(fixedDelay = 600000)
    public void processFailedWebhooks() {
        com.fooddelivery.common.lock.RedisLock _redisLock = new com.fooddelivery.common.lock.RedisLock(redisTemplate);
        String _lockToken = java.util.UUID.randomUUID().toString();
        boolean locked = _redisLock.tryAcquire(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_PROCESS_WEBHOOK_DLQ, _lockToken, java.time.Duration.ofSeconds(500));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }

        log.info("Starting DLQ processing job for failed webhooks");
        
        try {
            // Find webhooks that failed and are older than 5 minutes (to allow DB to settle)
            List<WebhookDelivery> failedDeliveries = webhookDeliveryRepository.findTop100ByProcessingStatusAndCreatedAtBefore(
                    DeliveryStatus.FAILED,
                    java.time.ZonedDateTime.now().minusMinutes(5)
            );

            for (WebhookDelivery delivery : failedDeliveries) {
                try {
                    log.info("Attempting to reprocess failed webhook event: {}", delivery.getEventId());
                    webhookProcessingService.retryWebhook(delivery);
                } catch (Exception e) {
                    log.error("DLQ retry failed for event: {}, marking as DEAD_LETTER", delivery.getEventId(), e);
                    delivery.setProcessingStatus(DeliveryStatus.DEAD_LETTER);
                    webhookDeliveryRepository.save(delivery);
                }
            }
            
            log.info("Completed DLQ processing job");
        } finally {
            _redisLock.release(com.fooddelivery.common.constants.RedisKeyConstants.LOCK_PROCESS_WEBHOOK_DLQ, _lockToken);
        }
    }
}
