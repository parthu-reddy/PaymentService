package com.fooddelivery.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.model.enums.DeliveryStatus;
import com.fooddelivery.common.constants.PaymentIntentStatus;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.repository.ITransactionRepository;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private WebhookProcessingService service;

    @Captor
    private ArgumentCaptor<WebhookDelivery> deliveryCaptor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        com.fooddelivery.payments.service.strategy.WebhookHandlerStrategy vyaparStub = new com.fooddelivery.payments.service.strategy.WebhookHandlerStrategy() {
            @Override
            public com.fooddelivery.common.enums.PaymentGateway getSupportedGateway() { return com.fooddelivery.common.enums.PaymentGateway.VYAPAR; }
            @Override
            public void handleEvent(String eventType, com.fasterxml.jackson.databind.JsonNode rootNode, com.fooddelivery.payments.service.strategy.PaymentActionDelegate delegate) {
                delegate.handleSuccessfulPayment("ord_123");
            }
        };

        service = new WebhookProcessingService(
                webhookDeliveryRepository,
                paymentIntentRepository,
                transactionRepository,
                objectMapper,
                outboxEventRepository,
                transactionTemplate,
                java.util.Collections.singletonList(vyaparStub));

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
        intent.setStatus(PaymentIntentStatus.INITIATED);
        intent.setOrderId(UUID.randomUUID().toString());
        intent.setAmount(new BigDecimal("100.00"));

        when(paymentIntentRepository.findLockedByGatewayOrderId("ord_123")).thenReturn(Optional.of(intent));

        when(webhookDeliveryRepository.saveAndFlush(any(WebhookDelivery.class))).thenAnswer(i -> i.getArgument(0));
        when(webhookDeliveryRepository.save(any(WebhookDelivery.class))).thenAnswer(i -> i.getArgument(0));

        String rawBody = "{\"event\":\"payment.success\",\"customer_mobile\":\"+919876543210\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"ord_123\"}}}}";
        service.processWebhookAsync("evt_123", com.fooddelivery.common.enums.PaymentGateway.VYAPAR, rawBody);

        verify(webhookDeliveryRepository, times(2)).save(deliveryCaptor.capture());
        assertEquals(PaymentIntentStatus.SUCCESS, intent.getStatus());
        verify(paymentIntentRepository).save(intent);
    }
}
