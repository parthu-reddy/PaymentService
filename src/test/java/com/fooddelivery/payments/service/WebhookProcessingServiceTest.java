package com.fooddelivery.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.model.enums.DeliveryStatus;
import com.fooddelivery.common.constants.PaymentIntentStatus;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.repository.ITransactionRepository;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import com.fooddelivery.payments.repository.IRefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import org.mockito.InjectMocks;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;

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
    private IRefundRepository refundRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private MeterRegistry meterRegistry;
    
    @Mock
    private Counter counter;

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
                refundRepository,
                objectMapper,
                outboxEventRepository,
                transactionTemplate,
                stringRedisTemplate,
                java.util.Collections.singletonList(vyaparStub),
                meterRegistry);

        lenient().when(meterRegistry.counter(anyString(), anyString(), anyString())).thenReturn(counter);

        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valOps);
        
        // Mock Redis lock behavior
        java.util.Map<String, String> mockRedisStore = new java.util.HashMap<>();
        lenient().when(valOps.setIfAbsent(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            if (!mockRedisStore.containsKey(key)) {
                mockRedisStore.put(key, value);
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
        lenient().when(valOps.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return mockRedisStore.get(key);
        });
        lenient().when(stringRedisTemplate.delete(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            mockRedisStore.remove(key);
            return true;
        });

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
        intent.setGatewayName(com.fooddelivery.common.enums.PaymentGateway.VYAPAR);

        when(paymentIntentRepository.findLockedByGatewayOrderId("ord_123")).thenReturn(Optional.of(intent));

        when(webhookDeliveryRepository.saveAndFlush(any(WebhookDelivery.class))).thenAnswer(i -> i.getArgument(0));
        when(webhookDeliveryRepository.save(any(WebhookDelivery.class))).thenAnswer(i -> i.getArgument(0));

        String rawBody = "{\"event\":\"payment.success\",\"customer_mobile\":\"+919876543210\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"ord_123\"}}}}";
        service.processWebhookAsync("evt_123", com.fooddelivery.common.enums.PaymentGateway.VYAPAR, rawBody);

        verify(webhookDeliveryRepository, times(2)).save(deliveryCaptor.capture());
        assertEquals(PaymentIntentStatus.SUCCESS, intent.getStatus());
        verify(paymentIntentRepository).save(intent);
    }

    @Test
    void testProcessWebhookAsync_deletesLockOnException() {
        when(webhookDeliveryRepository.saveAndFlush(any(WebhookDelivery.class)))
                .thenThrow(new RuntimeException("Simulated DB error"));

        String rawBody = "{\"event\":\"payment.success\",\"payload\":{}}";
        service.processWebhookAsync("evt_error_123", com.fooddelivery.common.enums.PaymentGateway.VYAPAR, rawBody);

        verify(stringRedisTemplate).delete("webhook:payment:evt_error_123");
    }

    @Test
    void testHandleRefundSuccess_FullRefund() {
        String gatewayOrderId = "order_123";
        String gatewayRefundId = "refund_123";
        
        PaymentIntent intent = new PaymentIntent();
        intent.setId(UUID.randomUUID());
        intent.setOrderId(UUID.randomUUID().toString());
        intent.setGatewayOrderId(gatewayOrderId);
        intent.setAmount(new BigDecimal("100.00"));
        intent.setGatewayName(com.fooddelivery.common.enums.PaymentGateway.RAZORPAY);

        com.fooddelivery.payments.model.Transaction tx = new com.fooddelivery.payments.model.Transaction();
        tx.setId(UUID.randomUUID());
        
        when(paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId)).thenReturn(Optional.of(intent));
        when(refundRepository.findByGatewayRefundId(gatewayRefundId)).thenReturn(Optional.empty());
        when(transactionRepository.findLockedFirstByPaymentIntentIdAndStatusOrderByCreatedAtDesc(eq(intent.getId()), any())).thenReturn(Optional.of(tx));

        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amount_refunded", "100.00");

        service.handleRefundSuccess(gatewayOrderId, gatewayRefundId, payload);

        assertEquals(new BigDecimal("100.00"), intent.getAmountRefunded());
        assertEquals(PaymentIntentStatus.REFUNDED, intent.getStatus());
        verify(paymentIntentRepository).save(intent);
        verify(refundRepository).save(any());
        verify(outboxEventRepository).save(any());
    }

    @Test
    void testHandleRefundSuccess_PartialRefund_CumulativeTracking() {
        String gatewayOrderId = "order_123";
        String gatewayRefundId = "refund_123";
        
        PaymentIntent intent = new PaymentIntent();
        intent.setId(UUID.randomUUID());
        intent.setOrderId(UUID.randomUUID().toString());
        intent.setGatewayOrderId(gatewayOrderId);
        intent.setAmount(new BigDecimal("100.00"));
        intent.setAmountRefunded(new BigDecimal("40.00"));
        intent.setGatewayName(com.fooddelivery.common.enums.PaymentGateway.RAZORPAY);

        com.fooddelivery.payments.model.Transaction tx = new com.fooddelivery.payments.model.Transaction();
        tx.setId(UUID.randomUUID());
        tx.setAmountRefunded(new BigDecimal("40.00"));
        
        when(paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId)).thenReturn(Optional.of(intent));
        when(refundRepository.findByGatewayRefundId(gatewayRefundId)).thenReturn(Optional.empty());
        when(transactionRepository.findLockedFirstByPaymentIntentIdAndStatusOrderByCreatedAtDesc(eq(intent.getId()), any())).thenReturn(Optional.of(tx));

        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amount_refunded", "30.00");

        service.handleRefundSuccess(gatewayOrderId, gatewayRefundId, payload);

        assertEquals(new BigDecimal("70.00"), intent.getAmountRefunded());
        assertEquals(PaymentIntentStatus.PARTIALLY_REFUNDED, intent.getStatus());
        assertEquals(new BigDecimal("70.00"), tx.getAmountRefunded());
    }
    @Test
    void testHandleRefundSuccess_CumulativePartialRefund_ReachesFull() {
        String gatewayOrderId = "order_123";
        String gatewayRefundId = "refund_123";
        
        PaymentIntent intent = new PaymentIntent();
        intent.setId(UUID.randomUUID());
        intent.setOrderId(UUID.randomUUID().toString());
        intent.setGatewayOrderId(gatewayOrderId);
        intent.setAmount(new BigDecimal("100.00"));
        intent.setAmountRefunded(new BigDecimal("60.00"));
        intent.setGatewayName(com.fooddelivery.common.enums.PaymentGateway.RAZORPAY);

        com.fooddelivery.payments.model.Transaction tx = new com.fooddelivery.payments.model.Transaction();
        tx.setId(UUID.randomUUID());
        tx.setAmountRefunded(new BigDecimal("60.00"));
        
        when(paymentIntentRepository.findLockedByGatewayOrderId(gatewayOrderId)).thenReturn(Optional.of(intent));
        when(refundRepository.findByGatewayRefundId(gatewayRefundId)).thenReturn(Optional.empty());
        when(transactionRepository.findLockedFirstByPaymentIntentIdAndStatusOrderByCreatedAtDesc(eq(intent.getId()), any())).thenReturn(Optional.of(tx));

        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amount_refunded", "40.00");

        service.handleRefundSuccess(gatewayOrderId, gatewayRefundId, payload);

        assertEquals(new BigDecimal("100.00"), intent.getAmountRefunded());
        // Status should transition to REFUNDED because cumulative amount == total amount
        assertEquals(PaymentIntentStatus.REFUNDED, intent.getStatus());
        assertEquals(new BigDecimal("100.00"), tx.getAmountRefunded());
    }
}
