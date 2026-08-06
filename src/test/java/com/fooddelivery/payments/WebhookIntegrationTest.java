package com.fooddelivery.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.model.enums.DeliveryStatus;
import com.fooddelivery.common.constants.PaymentIntentStatus;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.kafka.admin.auto-create=false",
    "cashfree.client.id=dummy",
    "cashfree.client.secret=dummy",
    "cashfree.environment=SANDBOX",
    "razorpay.key.id=dummy",
    "razorpay.key.secret=dummy",
    "razorpay.webhook.secret=dummy",
    "vyapar.api.key=dummy",
    "vyapar.api.secret=dummy",
    "vyapar.webhook.secret=c0e76bf082a4b56243a56a9e9a5c9ed1f038a5af1ab8179d2b6ebb11e9ac864f"
})
public class WebhookIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class SyncTaskExecutorConfig {
        @org.springframework.context.annotation.Bean(name = "taskExecutor")
        @org.springframework.context.annotation.Primary
        public org.springframework.core.task.TaskExecutor taskExecutor() {
            return new org.springframework.core.task.SyncTaskExecutor();
        }
    }
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private LettuceBasedProxyManager lettuceProxyManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IWebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private IPaymentIntentRepository paymentIntentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        webhookDeliveryRepository.deleteAll();
        paymentIntentRepository.deleteAll();

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valOps = mock(ValueOperations.class);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valOps);
        
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
    }

    @Test
    void testEndToEndVyaparWebhookProcessing() throws Exception {
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("vyapar_test_123");
        intent.setGatewayName(com.fooddelivery.common.enums.PaymentGateway.VYAPAR);
        intent.setAmount(new BigDecimal("100.00"));
        intent.setStatus(PaymentIntentStatus.INITIATED);
        intent.setOrderId(UUID.randomUUID().toString());
        intent.setIdempotencyKey("idemp_123");
        paymentIntentRepository.save(intent);

        String payload = """
                {
                  "event": "payment.success",
                  "payload": {
                    "payment": {
                      "entity": {
                        "order_id": "vyapar_test_123",
                        "status": "captured",
                        "amount": 10000
                      }
                    }
                  },
                  "customer_mobile": "+919876543210"
                }
                """;

        mockMvc.perform(post("/api/v1/webhooks/vyapar")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .header("X-VyaparGateway-Signature", "c0e76bf082a4b56243a56a9e9a5c9ed1f038a5af1ab8179d2b6ebb11e9ac864f"))
                .andExpect(status().isOk());

        long endTime = System.currentTimeMillis() + 5000;
        List<WebhookDelivery> deliveries = null;
        while (System.currentTimeMillis() < endTime) {
            deliveries = webhookDeliveryRepository.findAll();
            if (!deliveries.isEmpty() && deliveries.get(0).getProcessingStatus() == DeliveryStatus.COMPLETED) {
                break;
            }
            Thread.sleep(500);
        }

        assertEquals(1, deliveries.size());
        
        WebhookDelivery delivery = deliveries.get(0);
        assertEquals(DeliveryStatus.COMPLETED, delivery.getProcessingStatus());
        assertEquals("payment.success", delivery.getEventType());
        assertTrue(delivery.getPayload().contains("****3210"));

        PaymentIntent updatedIntent = paymentIntentRepository.findById(intent.getId()).get();
        assertEquals(PaymentIntentStatus.SUCCESS, updatedIntent.getStatus());
    }
}
