package com.fooddelivery.payments.contract;

import com.fooddelivery.common.contract.KafkaStubMessageSender;
import com.fooddelivery.payments.service.PaymentEventConsumer;
import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.service.WebhookProcessingService;
import com.fooddelivery.common.repository.IIdempotencyKeyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.contract.stubrunner.StubTrigger;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.cloud.contract.verifier.messaging.MessageVerifierSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.TimeUnit;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = PaymentEventConsumerContractTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
@ActiveProfiles("contract-test")
@AutoConfigureStubRunner(ids = "com.fooddelivery:food-delivery-backend:+:stubs",
        stubsMode = StubRunnerProperties.StubsMode.LOCAL)
@EmbeddedKafka(partitions = 1, topics = {"payment-events"})
class PaymentEventConsumerContractTest {

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @Import(PaymentEventConsumer.class)
    static class TestConfig {
        @Bean
        public MessageVerifierSender<Message<?>> kafkaStubMessageSender(KafkaTemplate<String, String> t) {
            return new KafkaStubMessageSender(t);
        }

        @Bean
        public TransactionTemplate transactionTemplate() {
            PlatformTransactionManager tm = Mockito.mock(PlatformTransactionManager.class);
            Mockito.when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            return new TransactionTemplate(tm);
        }
        
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @MockBean private IIdempotencyKeyRepository idempotencyKeyRepository;
    @MockBean private PaymentGatewayOrchestrator orchestrator;
    @MockBean private WebhookProcessingService webhookProcessingService;

    @Autowired
    private StubTrigger stubTrigger;

    @Test
    void acceptsTheProducerPayloadAndInitiatesRefund() {
        // We mock orchestrator returning true
        when(orchestrator.initiateRefund(any(), anyString(), anyString(), any(), anyString())).thenReturn(true);

        // Trigger the stub for order_payment_refund_requested
        stubTrigger.trigger("order_payment_refund_requested");

        // Verify that the consumer intercepts it, extracts the gatewayOrderId and calls the orchestrator
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                verify(orchestrator).initiateRefund(any(), eq("pay_12345"), anyString(), any(), anyString()));
    }
}
