package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.model.enums.DeliveryStatus;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WebhookDlqJobTest {

    @Mock
    private IWebhookDeliveryRepository webhookDeliveryRepository;

    @Mock
    private WebhookProcessingService webhookProcessingService;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @InjectMocks
    private WebhookDlqJob job;

    @Test
    void testProcessFailedWebhooks_TransitionsToDeadLetter() {
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valOps);
        when(valOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEventId("evt_fail");
        delivery.setGatewayName(com.fooddelivery.common.enums.PaymentGateway.VYAPAR);
        delivery.setPayload("{}");
        delivery.setProcessingStatus(DeliveryStatus.FAILED);
        delivery.setCreatedAt(java.time.Instant.now());

        when(webhookDeliveryRepository.findTop100ByProcessingStatusAndCreatedAtBefore(eq(DeliveryStatus.FAILED), any())).thenReturn(List.of(delivery));
        doThrow(new RuntimeException("DB Lock")).when(webhookProcessingService).retryWebhook(any());

        job.processFailedWebhooks();

        assertEquals(DeliveryStatus.DEAD_LETTER, delivery.getProcessingStatus());
        verify(webhookDeliveryRepository).save(delivery);
    }
}
