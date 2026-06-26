package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.repository.WebhookDeliveryRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookProcessingService {

    private final WebhookDeliveryRepository webhookDeliveryRepository;

    public WebhookProcessingService(WebhookDeliveryRepository webhookDeliveryRepository) {
        this.webhookDeliveryRepository = webhookDeliveryRepository;
    }

    @Async
    @Transactional
    public void processWebhookAsync(String eventId, String gatewayName, String rawBody) {
        // Persist the webhook payload to the database
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEventId(eventId);
        delivery.setGatewayName(gatewayName);
        
        // Extracting event_type from JSON (simple fallback logic here for brevity)
        String eventType = "UNKNOWN";
        if (rawBody.contains("\"event\":\"order.paid\"")) eventType = "order.paid";
        else if (rawBody.contains("\"event\":\"payment.captured\"")) eventType = "payment.captured";
        else if (rawBody.contains("\"type\":\"PAYMENT_SUCCESS_WEBHOOK\"")) eventType = "PAYMENT_SUCCESS_WEBHOOK"; // Cashfree Example
        
        delivery.setEventType(eventType);
        delivery.setPayload(rawBody);
        delivery.setProcessingStatus("PENDING");
        
        try {
            webhookDeliveryRepository.save(delivery);
            
            // Core business logic to transition orders, payment intents, and transactions
            // would be implemented here based on the eventType.
            
            delivery.setProcessingStatus("COMPLETED");
            webhookDeliveryRepository.save(delivery);
        } catch (Exception e) {
            delivery.setProcessingStatus("FAILED");
            delivery.setErrorLog(e.getMessage());
            webhookDeliveryRepository.save(delivery);
        }
    }
}
