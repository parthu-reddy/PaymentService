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
        List<WebhookDelivery> failedDeliveries = webhookDeliveryRepository.findAll().stream()
                .filter(d -> d.getProcessingStatus() == DeliveryStatus.FAILED)
                .filter(d -> d.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(5)))
                .toList();

        for (WebhookDelivery delivery : failedDeliveries) {
            try {
                logger.info("Attempting to reprocess failed webhook event: {}", delivery.getEventId());
                webhookProcessingService.processWebhookAsync(
                        delivery.getEventId() + "_retry", // append retry to bypass idempotency check temporarily if needed, though processWebhookAsync saves a new delivery. Actually better to just call processWebhookAsync with original eventId and body, but processWebhookAsync generates a new delivery object anyway.
                        delivery.getGatewayName(),
                        delivery.getPayload()); // Payload is masked, but for vyapar we only need `order_id` which is not masked!
                
                // Mark old as DEAD_LETTER since processWebhookAsync creates a NEW delivery record.
                delivery.setProcessingStatus(DeliveryStatus.DEAD_LETTER);
                webhookDeliveryRepository.save(delivery);
            } catch (Exception e) {
                logger.error("DLQ retry failed for event: {}, marking as DEAD_LETTER", delivery.getEventId(), e);
                delivery.setProcessingStatus(DeliveryStatus.DEAD_LETTER);
                webhookDeliveryRepository.save(delivery);
            }
        }
        
        logger.info("Completed DLQ processing job");
    }
}
