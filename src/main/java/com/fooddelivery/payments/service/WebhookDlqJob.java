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
public class WebhookDlqJob {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDlqJob.class);

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
    @Scheduled(fixedRate = 600000)
    public void processFailedWebhooks() {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent("lock:processWebhookDlq", "1", java.time.Duration.ofSeconds(500));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }

        logger.info("Starting DLQ processing job for failed webhooks");
        
        try {
            // Find webhooks that failed and are older than 5 minutes (to allow DB to settle)
            List<WebhookDelivery> failedDeliveries = webhookDeliveryRepository.findTop100ByProcessingStatusAndCreatedAtBefore(
                    DeliveryStatus.FAILED,
                    java.time.ZonedDateTime.now().minusMinutes(5)
            );

            for (WebhookDelivery delivery : failedDeliveries) {
                try {
                    logger.info("Attempting to reprocess failed webhook event: {}", delivery.getEventId());
                    webhookProcessingService.retryWebhook(delivery);
                } catch (Exception e) {
                    logger.error("DLQ retry failed for event: {}, marking as DEAD_LETTER", delivery.getEventId(), e);
                    delivery.setProcessingStatus(DeliveryStatus.DEAD_LETTER);
                    webhookDeliveryRepository.save(delivery);
                }
            }
            
            logger.info("Completed DLQ processing job");
        } finally {
            redisTemplate.delete("lock:processWebhookDlq");
        }
    }
}
