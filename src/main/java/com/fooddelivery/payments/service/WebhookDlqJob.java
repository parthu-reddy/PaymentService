package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.model.enums.DeliveryStatus;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WebhookDlqJob {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDlqJob.class);

    private final IWebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookProcessingService webhookProcessingService;

    public WebhookDlqJob(
            IWebhookDeliveryRepository webhookDeliveryRepository,
            WebhookProcessingService webhookProcessingService) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.webhookProcessingService = webhookProcessingService;
    }

    // Run every 10 minutes
    @Scheduled(fixedRate = 600000)
    public void processFailedWebhooks() {
        logger.info("Starting DLQ processing job for failed webhooks");
        
        // Find webhooks that failed and are older than 5 minutes (to allow DB to settle)
        List<WebhookDelivery> failedDeliveries = webhookDeliveryRepository.findTop100ByProcessingStatusAndCreatedAtBefore(
                DeliveryStatus.FAILED,
                java.time.ZonedDateTime.now().minusMinutes(10)
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
    }
}
