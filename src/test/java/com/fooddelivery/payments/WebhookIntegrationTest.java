package com.fooddelivery.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.WebhookDelivery;
import com.fooddelivery.payments.model.enums.DeliveryStatus;
import com.fooddelivery.payments.model.enums.IntentStatus;
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
public class WebhookIntegrationTest {

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
    }

    @Test
    void testEndToEndVyaparWebhookProcessing() throws Exception {
        PaymentIntent intent = new PaymentIntent();
        intent.setGatewayOrderId("vyapar_test_123");
        intent.setGatewayName("VYAPAR");
        intent.setAmount(new BigDecimal("100.00"));
        intent.setStatus(IntentStatus.INITIATED);
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
        assertEquals(IntentStatus.SUCCESS, updatedIntent.getStatus());
    }
}
