package com.fooddelivery.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.payments.model.Order;
import com.fooddelivery.payments.model.PaymentIntent;
import com.fooddelivery.payments.model.enums.IntentStatus;
import com.fooddelivery.payments.model.enums.OrderStatus;
import com.fooddelivery.payments.model.Merchant;
import com.fooddelivery.payments.model.Customer;
import com.fooddelivery.payments.repository.IOrderRepository;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import com.fooddelivery.payments.repository.IWebhookDeliveryRepository;
import com.fooddelivery.payments.repository.MerchantRepository;
import com.fooddelivery.payments.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.redis.testcontainers.RedisContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public class WebhookIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IOrderRepository orderRepository;

    @Autowired
    private IPaymentIntentRepository paymentIntentRepository;

    @Autowired
    private IWebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private PaymentIntent testIntent;

    @BeforeEach
    void setUp() {
        webhookDeliveryRepository.deleteAll();
        paymentIntentRepository.deleteAll();
        orderRepository.deleteAll();
        merchantRepository.deleteAll();
        customerRepository.deleteAll();

        Merchant merchant = new Merchant();
        merchant.setName("Test Merchant");
        merchant.setEmail("test@merchant.com");
        merchant = merchantRepository.save(merchant);

        Customer customer = new Customer();
        customer.setFullName("Test Customer");
        customer.setPhoneNumber("9999999999");
        customer = customerRepository.save(customer);

        Order order = new Order();
        order.setMerchant(merchant);
        order.setCustomer(customer);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setStatus(OrderStatus.CREATED);
        order = orderRepository.save(order);

        testIntent = new PaymentIntent();
        testIntent.setOrder(order);
        testIntent.setGatewayName("VYAPAR");
        testIntent.setGatewayOrderId("test_gateway_order_123");
        testIntent.setAmount(new BigDecimal("100.00"));
        testIntent.setStatus(IntentStatus.INITIATED);
        testIntent.setIdempotencyKey(UUID.randomUUID().toString());
        testIntent = paymentIntentRepository.save(testIntent);
    }

    @Test
    void testFullWebhookFlow_WithIdempotency() throws Exception {
        String payload = "{\"event\":\"payment.success\",\"payload\":{\"payment\":{\"entity\":{\"order_id\":\"test_gateway_order_123\"}}}}";
        String secret = "TEST_VYAPAR_SECRET";
        
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(secret.getBytes(), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(payload.getBytes());
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        String signature = hexString.toString();

        // First Request - Should Succeed
        mockMvc.perform(post("/api/v1/webhooks/vyapar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-VyaparGateway-Signature", signature)
                        .header("X-VyaparGateway-Event-Id", "test_event_999")
                        .content(payload))
                .andExpect(status().isOk());

        // Wait a brief moment for @Async to complete
        Thread.sleep(1000);

        // Verify DB updates
        PaymentIntent updatedIntent = paymentIntentRepository.findById(testIntent.getId()).get();
        assertEquals(IntentStatus.SUCCESS, updatedIntent.getStatus());
        
        Order updatedOrder = orderRepository.findById(updatedIntent.getOrder().getId()).get();
        assertEquals(OrderStatus.PAID, updatedOrder.getStatus());
        
        assertEquals(1, webhookDeliveryRepository.count());

        // Second Request (Duplicate) - Should return 200 OK (Already Processed logic)
        mockMvc.perform(post("/api/v1/webhooks/vyapar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-VyaparGateway-Signature", signature)
                        .header("X-VyaparGateway-Event-Id", "test_event_999")
                        .content(payload))
                .andExpect(status().isOk());

        // Verify no duplicate processing occurred
        assertEquals(1, webhookDeliveryRepository.count());
    }
}
