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
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
@EmbeddedKafka(adminTimeout = 60, partitions = 1, topics = {"payment-events"})
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

        com.fooddelivery.payments.config.PaymentRoutingConfig routingConfig = Mockito.mock(com.fooddelivery.payments.config.PaymentRoutingConfig.class);
        when(routingConfig.getGatewayForMethod(any())).thenReturn(com.fooddelivery.common.enums.PaymentGateway.RAZORPAY);
        PaymentController paymentController = new PaymentController(orchestrator, repository, routingConfig,
                Mockito.mock(com.fooddelivery.payments.service.PaymentCompletionScheduler.class));
        // Serialize as production does: see PlatformJson (contract-harness Jackson drift).
        com.fooddelivery.common.contract.PlatformJson.standaloneSetup(paymentController);
    }
}
