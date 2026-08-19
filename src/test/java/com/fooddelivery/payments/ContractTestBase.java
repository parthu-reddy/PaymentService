package com.fooddelivery.payments;

import com.fooddelivery.payments.controller.PaymentController;
import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(partitions = 1, topics = {"payment-events"})
@org.springframework.test.context.ActiveProfiles({"contract-test", "test"})
public abstract class ContractTestBase {

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    private io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager lettuceProxyManager;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.payments.repository.IPaymentIntentRepository paymentIntentRepository;



    @BeforeEach
    public void setup() {
        PaymentGatewayOrchestrator orchestrator = Mockito.mock(PaymentGatewayOrchestrator.class);
        IPaymentIntentRepository repository = Mockito.mock(IPaymentIntentRepository.class);

        when(orchestrator.createOrder(any(), any())).thenReturn("PAYMENT_LINK_URL");

        PaymentController paymentController = new PaymentController(orchestrator, repository);
        RestAssuredMockMvc.standaloneSetup(paymentController);
    }
}
