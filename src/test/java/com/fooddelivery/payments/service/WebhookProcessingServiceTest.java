package com.fooddelivery.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.Order;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.model.enums.DeliveryStatus;
import com.fooddelivery.payments.model.enums.IntentStatus;
import com.fooddelivery.payments.model.enums.OrderStatus;
import com.fooddelivery.payments.repository.IOrderRepository;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.TransactionStatus;
import com.fooddelivery.payments.model.PaymentSucceededEvent;

import java.math.BigDecimal;
import java.util.Optional;

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
    private IOrderRepository orderRepository;
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
                orderRepository,
                objectMapper,
                eventPublisher,
                transactionTemplate);

        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void testProcessWebhookAsync_masksSensitiveData() {
        String eventId = "evt_123";
        String gateway = "VYAPAR";
        String rawBody = "{\"event\":\"payment.success\",\"customer_mobile\":\"+919876543210\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"ord_123\"}}}}";

        Order order = new Order();
        order.setStatus(OrderStatus.CREATED);
        order.setId(java.util.UUID.randomUUID());
        order.setTotalAmount(new BigDecimal("100.00"));
        
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("ord_123");
        intent.setStatus(IntentStatus.INITIATED);
        intent.setOrder(order);

        when(paymentIntentRepository.findByGatewayOrderId("ord_123")).thenReturn(Optional.of(intent));

        service.processWebhookAsync(eventId, gateway, rawBody);

        verify(webhookDeliveryRepository, times(2)).save(deliveryCaptor.capture());
        
        WebhookDelivery savedDelivery = deliveryCaptor.getAllValues().get(1);
        assertEquals("payment.success", savedDelivery.getEventType());
        assertEquals(DeliveryStatus.COMPLETED, savedDelivery.getProcessingStatus());
        
        // Assert PII is masked
        String savedPayload = savedDelivery.getPayload();
        assertTrue(savedPayload.contains("****3210"));
        assertFalse(savedPayload.contains("+919876543210"));
        
        // Assert business logic execution
        assertEquals(IntentStatus.SUCCESS, intent.getStatus());
        assertEquals(OrderStatus.PAID, order.getStatus());
        
        verify(paymentIntentRepository).save(intent);
        verify(orderRepository).save(order);
    }

    @Test
    void testProcessWebhookAsync_refundLogic() {
        String eventId = "evt_456";
        String rawBody = "{\"event\":\"refund.success\",\"amount_refunded\":\"50.00\",\"order_id\":\"ord_999\"}";

        Order order = new Order();
        order.setStatus(OrderStatus.PAID);
        
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("ord_999");
        intent.setAmount(new BigDecimal("100.00"));
        intent.setAmountRefunded(BigDecimal.ZERO);
        intent.setStatus(IntentStatus.SUCCESS);
        intent.setOrder(order);

        when(paymentIntentRepository.findByGatewayOrderId("ord_999")).thenReturn(Optional.of(intent));

        service.processWebhookAsync(eventId, "VYAPAR", rawBody);
        
        assertEquals(new BigDecimal("50.00"), intent.getAmountRefunded());
        assertEquals(IntentStatus.PARTIALLY_REFUNDED, intent.getStatus());
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, order.getStatus());
    }

    @Test
    void testProcessWebhookAsync_Razorpay() {
        String eventId = "evt_rzp_123";
        String rawBody = "{\"event\":\"order.paid\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"ord_rzp_123\"}}}}";

        Order order = new Order();
        order.setId(java.util.UUID.randomUUID());
        order.setStatus(OrderStatus.CREATED);
        
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("ord_rzp_123");
        intent.setStatus(IntentStatus.INITIATED);
        intent.setOrder(order);

        when(paymentIntentRepository.findByGatewayOrderId("ord_rzp_123")).thenReturn(Optional.of(intent));

        service.processWebhookAsync(eventId, "RAZORPAY", rawBody);
        
        assertEquals(IntentStatus.SUCCESS, intent.getStatus());
        assertEquals(OrderStatus.PAID, order.getStatus());
        verify(paymentIntentRepository).save(intent);
    }

    @Test
    void testProcessWebhookAsync_Cashfree() {
        String eventId = "evt_cf_123";
        String rawBody = "{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":{\"order_id\":\"ord_cf_123\"}}}";

        Order order = new Order();
        order.setId(java.util.UUID.randomUUID());
        order.setStatus(OrderStatus.CREATED);
        
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("ord_cf_123");
        intent.setStatus(IntentStatus.INITIATED);
        intent.setOrder(order);

        when(paymentIntentRepository.findByGatewayOrderId("ord_cf_123")).thenReturn(Optional.of(intent));

        service.processWebhookAsync(eventId, "CASHFREE", rawBody);
        
        assertEquals(IntentStatus.SUCCESS, intent.getStatus());
        assertEquals(OrderStatus.PAID, order.getStatus());
        verify(paymentIntentRepository).save(intent);
    }

    @Test
    void testRetryWebhook_Success() {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEventId("evt_retry_1");
        delivery.setGatewayName("VYAPAR");
        delivery.setEventType("payment.success");
        delivery.setPayload("{\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"ord_123\"}}}}");
        
        Order order = new Order();
        order.setId(java.util.UUID.randomUUID());
        order.setStatus(OrderStatus.CREATED);
        
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("ord_123");
        intent.setStatus(IntentStatus.INITIATED);
        intent.setOrder(order);

        when(paymentIntentRepository.findByGatewayOrderId("ord_123")).thenReturn(Optional.of(intent));

        service.retryWebhook(delivery);

        assertEquals(IntentStatus.SUCCESS, intent.getStatus());
        assertEquals(DeliveryStatus.COMPLETED, delivery.getProcessingStatus());
        verify(webhookDeliveryRepository).save(delivery);
    }
}
