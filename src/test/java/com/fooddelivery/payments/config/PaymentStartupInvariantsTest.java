package com.fooddelivery.payments.config;

import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import com.fooddelivery.payments.service.gateway.MockCashfreeStrategy;
import com.fooddelivery.payments.service.gateway.MockRazorpayStrategy;
import com.fooddelivery.payments.service.gateway.MockVyaparGatewayStrategy;
import com.fooddelivery.payments.service.gateway.CashfreeStrategy;
import com.fooddelivery.payments.service.gateway.RazorpayStrategy;
import com.fooddelivery.payments.service.gateway.VyaparGatewayStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mock gateways mark every order paid without taking money, and the deployment shipped them for
 * months because a mock behaves exactly like a real gateway that always succeeds.
 *
 * <p>They now require an explicit second statement of intent. This is fatal in every profile, not
 * only in {@code prod}: the failure being guarded against is a non-prod profile string reaching a
 * real deployment.
 */
class PaymentStartupInvariantsTest {

    /**
     * Mockito mocks of the <em>real</em> shipped classes rather than hand-written stubs.
     *
     * <p>A stub named {@code MockFakeStrategy} would prove the rule recognises a name the test
     * itself invented. Mocking {@code MockRazorpayStrategy} proves it recognises the class that
     * actually ships — and a Mockito subclass keeps the simple-name prefix, which is what the rule
     * reads, so this also pins the behaviour under proxying.
     */
    private IPaymentGatewayStrategy aMock() {
        return Mockito.mock(MockRazorpayStrategy.class);
    }

    private IPaymentGatewayStrategy aRealGateway() {
        return Mockito.mock(RazorpayStrategy.class);
    }

    private PaymentStartupInvariants invariants(List<IPaymentGatewayStrategy> strategies,
                                                boolean allow, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        PaymentStartupInvariants i = new PaymentStartupInvariants(strategies, env);
        ReflectionTestUtils.setField(i, "allowMockGateways", allow);
        return i;
    }

    @Test
    void aMockGatewayWithNoExplicitPermissionRefusesToStart() {
        PaymentStartupInvariants i = invariants(List.of(aMock()), false, "dev", "debug");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                i::assertNoUnannouncedMockGateways);
        assertTrue(e.getMessage().contains("MockRazorpayStrategy"), e.getMessage());
        assertTrue(e.getMessage().contains("allow-mock-gateways"), e.getMessage());
        assertTrue(e.getMessage().contains("dev,debug"),
                "the message must name the profiles that pulled the mocks in: " + e.getMessage());
    }

    @Test
    void itIsFatalOutsideProdToo() {
        // The whole point: dev,debug is what shipped, and prod-only enforcement would have let it.
        assertThrows(IllegalStateException.class, () ->
                invariants(List.of(aMock()), false, "dev").assertNoUnannouncedMockGateways());
        assertThrows(IllegalStateException.class, () ->
                invariants(List.of(aMock()), false).assertNoUnannouncedMockGateways());
    }

    @Test
    void anExplicitOptInIsAllowed() {
        PaymentStartupInvariants i = invariants(List.of(aMock()), true, "test");

        assertDoesNotThrow(i::assertNoUnannouncedMockGateways);
    }

    @Test
    void realGatewaysStartWithoutAnyOptIn() {
        PaymentStartupInvariants i = invariants(List.of(aRealGateway()), false, "prod");

        assertDoesNotThrow(i::assertNoUnannouncedMockGateways);
    }

    @Test
    void theRuleClassifiesEveryStrategyThisModuleActuallyShips() {
        // Applies the production predicate to the real classes rather than reimplementing it. A rule
        // written against a naming convention these classes do not follow would let every other test
        // here pass while protecting nothing.
        for (Class<?> mock : List.of(MockRazorpayStrategy.class, MockCashfreeStrategy.class,
                MockVyaparGatewayStrategy.class)) {
            assertTrue(PaymentStartupInvariants.isMock(mock),
                    mock.getSimpleName() + " must be recognised as a mock");
        }
        for (Class<?> real : List.of(RazorpayStrategy.class, CashfreeStrategy.class,
                VyaparGatewayStrategy.class)) {
            assertFalse(PaymentStartupInvariants.isMock(real),
                    real.getSimpleName() + " must not be recognised as a mock");
        }
    }

    @Test
    void allThreeMocksAreNamedWhenAllThreeAreRegistered() {
        PaymentStartupInvariants i = invariants(List.of(aMock(), aRealGateway()),
                false, "local");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                i::assertNoUnannouncedMockGateways);
        assertTrue(e.getMessage().contains("MockRazorpayStrategy"));
        assertFalse(e.getMessage().contains("CashfreeStrategy"),
                "a real strategy must not be reported as a mock: " + e.getMessage());
    }
}
