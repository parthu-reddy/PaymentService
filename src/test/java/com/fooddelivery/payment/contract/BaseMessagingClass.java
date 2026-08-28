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

@SpringBootTest(classes = BaseMessagingClass.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        // Exclusions live in `properties`, never on the class as @EnableAutoConfiguration(exclude=...):
        // every service component-scans the whole com.fooddelivery tree, so a scanned configuration
        // carrying exclusions applies them to OTHER tests' contexts -- which surfaced as unrelated
        // tests failing with "No bean named 'entityManagerFactory'".
        //
        // The datasource and JPA exclusions were dropped on 2026-08-26 in favour of ddl-auto=none,
        // which left this context building a DataSource and EntityManagerFactory that nothing in a
        // messaging contract test uses. Restored: a contract test should build the least that
        // exercises the contract.
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "spring.kafka.consumer.auto-offset-reset=earliest"
})
@org.springframework.test.context.ActiveProfiles("contract-test")
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

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class TestConfig {
        @Bean
        public KafkaMessageVerifier kafkaMessageVerifier() {
            return new KafkaMessageVerifier();
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** Mirrors WebhookProcessingService: real PaymentSucceededEvent tree + eventType field. */
    public void firePaymentSuccess() throws Exception {
        String orderId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
        com.fooddelivery.common.event.PaymentSucceededEvent event =
                new com.fooddelivery.common.event.PaymentSucceededEvent(
                        orderId, "order_RZP123456", new java.math.BigDecimal("250.00"), "RAZORPAY");
        com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.valueToTree(event);
        payloadNode.put("eventType",
                com.fooddelivery.common.constants.EventType.PAYMENT_COMPLETED.name());
        publishViaOutbox(com.fooddelivery.common.constants.AggregateType.PAYMENT, orderId,
                com.fooddelivery.common.constants.EventType.PAYMENT_COMPLETED, payloadNode);
    }


    /** Drives the real OutboxProcessor: real topic routing, real key, real eventType header. */
    protected void publishViaOutbox(com.fooddelivery.common.constants.AggregateType aggregateType,
                                    String aggregateId,
                                    com.fooddelivery.common.constants.EventType eventType,
                                    Object payloadObject) throws Exception {
        com.fooddelivery.common.outbox.entity.OutboxEventEntity outboxEvent =
                com.fooddelivery.common.outbox.entity.OutboxEventEntity.builder()
                        .id(java.util.UUID.randomUUID())
                        .aggregateType(aggregateType)
                        .aggregateId(aggregateId)
                        .eventType(eventType)
                        .payload(payloadObject instanceof String
                                ? (String) payloadObject
                                : objectMapper.writeValueAsString(payloadObject))
                        .createdAt(java.time.LocalDateTime.now())
                        .build();
        com.fooddelivery.common.outbox.repository.OutboxEventRepository repo =
                org.mockito.Mockito.mock(com.fooddelivery.common.outbox.repository.OutboxEventRepository.class);
        org.mockito.Mockito.when(repo.findTop100ByStatusInOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new java.util.ArrayList<>(java.util.List.of(outboxEvent)));
        new com.fooddelivery.common.outbox.service.OutboxProcessor(repo, kafkaTemplate, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).processOutboxEvents();
    }

    /** Mirrors WebhookProcessingService's wallet-topup branch (orderId starts with WALLET_). */
    public void fireWalletTopupCompleted() throws Exception {
        String orderId = "WALLET_3e14926d-0c98-5840-abcd-37ec439ddc25";
        com.fooddelivery.common.event.PaymentSucceededEvent event =
                new com.fooddelivery.common.event.PaymentSucceededEvent(
                        orderId, "order_RZP654321", new java.math.BigDecimal("250.00"), "RAZORPAY");
        com.fasterxml.jackson.databind.node.ObjectNode payloadNode = objectMapper.valueToTree(event);
        payloadNode.put("eventType",
                com.fooddelivery.common.constants.EventType.AD_WALLET_TOPUP_COMPLETED.name());
        publishViaOutbox(com.fooddelivery.common.constants.AggregateType.PAYMENT, orderId,
                com.fooddelivery.common.constants.EventType.AD_WALLET_TOPUP_COMPLETED, payloadNode);
    }

}
