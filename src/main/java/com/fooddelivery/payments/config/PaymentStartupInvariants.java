package com.fooddelivery.payments.config;

import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Refuses to serve traffic when the mock gateways are wired in and nobody said so out loud.
 *
 * <p>The mock strategies mark every order paid without taking money. They are selected by a
 * {@code @Profile} expression that includes {@code debug}, {@code dev}, {@code default},
 * {@code test} and {@code local} — five ways to get it wrong — and the deployment script shipped
 * {@code SPRING_PROFILES_ACTIVE=dev,debug} for months. Nothing failed, because a mock gateway
 * behaves exactly like a real one that always succeeds.
 *
 * <p>A profile string is too easy to get wrong to be the only thing standing between the platform
 * and free food, so the mocks now require a second, explicit statement of intent:
 * {@code app.payments.allow-mock-gateways=true}. Unlike {@code MoneyStartupInvariants} in
 * LedgerService, this is fatal in every profile — the failure it guards against is precisely a
 * non-prod profile reaching a real deployment.
 */
@Component
@Slf4j
public class PaymentStartupInvariants {

    static final String ALLOW_MOCKS_PROPERTY = "app.payments.allow-mock-gateways";

    private final List<IPaymentGatewayStrategy> strategies;
    private final Environment environment;

    @Value("${" + ALLOW_MOCKS_PROPERTY + ":false}")
    private boolean allowMockGateways;

    public PaymentStartupInvariants(List<IPaymentGatewayStrategy> strategies, Environment environment) {
        this.strategies = strategies;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void assertNoUnannouncedMockGateways() {
        List<String> mocks = mockStrategyNames(strategies);

        if (mocks.isEmpty()) {
            log.info("Payment startup invariants hold: {} real gateway strategies registered, no mocks.",
                    strategies.size());
            return;
        }
        if (allowMockGateways) {
            log.warn("MOCK PAYMENT GATEWAYS ARE ACTIVE ({}). Every order will be marked paid without "
                            + "taking money. Allowed because {}=true. Active profiles: {}",
                    String.join(", ", mocks), ALLOW_MOCKS_PROPERTY,
                    String.join(",", environment.getActiveProfiles()));
            return;
        }
        throw new IllegalStateException(String.format(
                "Mock payment gateways are registered (%s) but %s is not true. These strategies mark "
                        + "every order paid without taking money. Active profiles: [%s] — the mocks are "
                        + "selected by @Profile(\"!prod & (debug | dev | default | test | local)\"), so "
                        + "either remove the offending profile or set %s=true deliberately.",
                String.join(", ", mocks), ALLOW_MOCKS_PROPERTY,
                String.join(",", environment.getActiveProfiles()), ALLOW_MOCKS_PROPERTY));
    }

    /**
     * Whether a strategy class is one of the mocks.
     *
     * <p>Exposed so the test can apply the real rule to the real shipped classes without
     * instantiating them -- a test that reimplements the predicate would pass while the rule itself
     * was written against a naming convention the classes do not follow.
     */
    static boolean isMock(Class<?> type) {
        // getSimpleName() rather than getName(): a CGLIB proxy keeps the simple name prefix.
        return type.getSimpleName().startsWith("Mock");
    }

    /** Package-private so the test can exercise the rule without standing up a context per case. */
    static List<String> mockStrategyNames(List<IPaymentGatewayStrategy> strategies) {
        return strategies.stream()
                .map(Object::getClass)
                .filter(PaymentStartupInvariants::isMock)
                .map(Class::getSimpleName)
                .sorted()
                .collect(Collectors.toList());
    }
}
