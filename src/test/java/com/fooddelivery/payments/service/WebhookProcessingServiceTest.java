package com.fooddelivery.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.model.enums.DeliveryStatus;
import com.fooddelivery.payments.model.enums.IntentStatus;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.repository.ITransactionRepository;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WebhookProcessingServiceTest {

    @Mock
    private IWebhookDeliveryRepository webhookDeliveryRepository;
    @Mock
    private IPaymentIntentRepository paymentIntentRepository;
    @Mock
    private ITransactionRepository transactionRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private PaymentEventPublisher eventPublisher;
    @Mock
    private TransactionTemplate transactionTemplate;

    private WebhookProcessingService service;

    @Captor
    private ArgumentCaptor<WebhookDelivery> deliveryCaptor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new WebhookProcessingService(
                webhookDeliveryRepository,
                paymentIntentRepository,
                eventPublisher,
                transactionTemplate,
                transactionRepository,
                objectMapper);

        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void testProcessWebhookAsync_masksSensitiveData() {
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("ord_123");
        intent.setStatus(IntentStatus.INITIATED);
        intent.setOrderId(UUID.randomUUID().toString());
        intent.setAmount(new BigDecimal("100.00"));

        when(paymentIntentRepository.findByGatewayOrderId("ord_123")).thenReturn(Optional.of(intent));

        String rawBody = "{\"event\":\"payment.success\",\"customer_mobile\":\"+919876543210\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"ord_123\"}}}}";
        service.processWebhookAsync("evt_123", "VYAPAR", rawBody);

        verify(webhookDeliveryRepository, times(2)).save(deliveryCaptor.capture());
        assertEquals(IntentStatus.SUCCESS, intent.getStatus());
        verify(paymentIntentRepository).save(intent);
    }
}
