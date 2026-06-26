package com.fooddelivery.payments.service;

import com.fooddelivery.payments.model.PaymentSucceededEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
public class PaymentEventPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private PaymentEventPublisher publisher;

    @Test
    void testPublishPaymentSuccess() {
        PaymentSucceededEvent event = new PaymentSucceededEvent(
                "order-123",
                "gw-456",
                new BigDecimal("500.00"),
                "VYAPAR"
        );

        publisher.publishPaymentSuccess(event);

        verify(kafkaTemplate, times(1)).send(eq("payment-events"), eq("order-123"), any(PaymentSucceededEvent.class));
    }
}
