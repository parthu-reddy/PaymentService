package com.fooddelivery.payments;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
    classes = PaymentServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.cloud.config.enabled=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    }
)
@org.springframework.test.context.ActiveProfiles("contract-test")
public class PaymentServiceApplicationStartupTest {

    @Autowired
    private ApplicationContext applicationContext;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.common.service.RateLimitingService rateLimitingService;
    @org.springframework.boot.test.mock.mockito.MockBean
    private io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager<String> proxyManager;
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.common.lock.RedisLock redisLock;
    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory;
    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.common.filter.IdempotencyFilter idempotencyFilter;
    @org.springframework.boot.test.mock.mockito.MockBean(name="IIdempotencyKeyRepository")
    private com.fooddelivery.common.repository.IIdempotencyKeyRepository idempotencyKeyRepository;
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.common.outbox.repository.OutboxEventRepository outboxEventRepository;
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.fooddelivery.common.security.SecurityContextFilter securityContextFilter;

    @Test
    void contextLoads() {
        assertNotNull(applicationContext, "Application context should load successfully");
    }

    /** The beans that take and return money must be present, not merely a context that started. */
    @Test
    void theBeansThatMoveMoneyArePresent() {
        for (Class<?> required : new Class<?>[]{
                com.fooddelivery.payments.service.PaymentGatewayOrchestrator.class,
                com.fooddelivery.payments.service.WebhookProcessingService.class}) {
            assertNotNull(applicationContext.getBean(required), required.getSimpleName() + " is not in the context");
        }
    }

    /**
     * Outside prod only the mock strategies register. Both a real and a mock strategy for one
     * gateway is P-20, and the orchestrator's merge function refuses to build the map at all.
     */
    @Test
    void exactlyOneStrategyPerGatewayIsRegistered() {
        java.util.Map<String, com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy> beans =
                applicationContext.getBeansOfType(com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy.class);
        java.util.Map<com.fooddelivery.common.enums.PaymentGateway, Long> perGateway = beans.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy::getGatewayName,
                        java.util.stream.Collectors.counting()));
        perGateway.forEach((gateway, count) ->
                org.junit.jupiter.api.Assertions.assertEquals(1L, count,
                        gateway + " has " + count + " strategies registered; a real and a mock for one "
                        + "gateway stops the service starting"));

        // Not asserted here: that at least one registers. The mock strategies activate on
        // "!prod & (debug | dev | default | test | local)" and this context runs under
        // "contract-test", so none of them do. PaymentGatewayStrategyUniquenessTest covers the
        // prod profile, where exactly one real strategy per gateway must be present.
        //
        // What this does prove is that PaymentGatewayOrchestrator was constructible: its merge
        // function throws when two strategies claim one gateway, so a context that started has a
        // map without duplicates.
        org.junit.jupiter.api.Assertions.assertNotNull(
                applicationContext.getBean(com.fooddelivery.payments.service.PaymentGatewayOrchestrator.class));
    }
    





}
