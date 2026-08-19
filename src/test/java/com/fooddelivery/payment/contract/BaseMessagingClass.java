package com.fooddelivery.payment.contract;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Bean;

@SpringBootTest(classes = {com.fooddelivery.payments.PaymentServiceApplication.class, BaseMessagingClass.TestConfig.class}, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.kafka.consumer.auto-offset-reset=earliest"
})
@org.springframework.test.context.ActiveProfiles("test")
@AutoConfigureMessageVerifier
@EmbeddedKafka(partitions = 1, topics = {"payment-events"})
public abstract class BaseMessagingClass {

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers", "localhost:9092"));
    }

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    private io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager lettuceProxyManager;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.payments.repository.IPaymentIntentRepository paymentIntentRepository;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        public KafkaMessageVerifier kafkaMessageVerifier() {
            return new KafkaMessageVerifier();
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    public void firePaymentSuccess() {
        String payload = """
{
  "eventId": "pay-111",
  "type": "PAYMENT_SUCCESS",
  "payload": {
    "orderId": 1001,
    "paymentId": "txn-999",
    "amount": 15.50
  }
}""";
        kafkaTemplate.send("payment-events", payload);
    }

}
