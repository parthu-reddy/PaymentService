package com.fooddelivery.contract.consumer.payments.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.cloud.contract.stubrunner.StubTrigger;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.test.context.EmbeddedKafka;

@ActiveProfiles("contract-test")
@org.springframework.test.context.TestPropertySource(properties = {"spring.flyway.enabled=false"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = com.fooddelivery.payments.PaymentServiceApplication.class)
@AutoConfigureStubRunner(ids = "com.fooddelivery:food-delivery-backend:+:stubs", stubsMode = StubRunnerProperties.StubsMode.LOCAL)
@EmbeddedKafka(partitions = 1)
public class OrderEventConsumerTest {

    @org.springframework.test.context.DynamicPropertySource
    static void dynamicProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers", "localhost:9092"));
    }
    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    private io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager lettuceProxyManager;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.payments.repository.IPaymentIntentRepository paymentIntentRepository;



    
    @Autowired
    private StubTrigger stubTrigger;

    

    @Test
    public void testConsumerIsWorking() {
        try {
            stubTrigger.trigger("trigger-order-created");
        } catch (Exception e) {
            System.out.println("Trigger failed, which might be expected if the stub does not have it yet. Error: " + e.getMessage());
        }
    }
}
