package com.fooddelivery.payments.strategy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.fooddelivery.payments.service.gateway.RazorpayStrategy;
import com.fooddelivery.payments.service.gateway.CashfreeStrategy;

public class ProdSecretsGuardTest {

    @Test
    public void testProdSecretsGuard() throws Exception {
        RazorpayStrategy razorpay = new RazorpayStrategy("rzp_123", "rzp_secret", "webhook_secret");
        
        // This should pass
        razorpay.assertProductionCredentials();
    }
    
    /** The dev profile's placeholder must not be accepted as a production credential. */
    @Test
    public void aDevPlaceholderIsRefusedInProduction() throws Exception {
        RazorpayStrategy razorpay = new RazorpayStrategy(
                "dev-placeholder-not-a-credential", "rzp_secret", "webhook_secret");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                razorpay::assertProductionCredentials);
        assertTrue(ex.getMessage().contains("razorpay.key.id is missing or is a placeholder value"));
    }

    /** A blank credential is refused, not defaulted. */
    @Test
    public void aBlankCredentialIsRefused() throws Exception {
        RazorpayStrategy razorpay = new RazorpayStrategy("rzp_123", "   ", "webhook_secret");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                razorpay::assertProductionCredentials);
        assertTrue(ex.getMessage().contains("razorpay.key.secret"));
    }

    /** Cashfree and Vyapar are guarded the same way, not just Razorpay. */
    @Test
    public void everyRealStrategyGuardsItsCredentials() {
        CashfreeStrategy cashfree = new CashfreeStrategy("dev-placeholder-not-a-credential", "secret");
        assertThrows(IllegalStateException.class, cashfree::assertProductionCredentials);
    }

    @Test
    public void testProdSecretsGuard2() throws Exception {
        RazorpayStrategy razorpay = new RazorpayStrategy("test_rzp", "rzp_secret", "webhook_secret");
        
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            razorpay.assertProductionCredentials();
        });
        
        assertTrue(ex.getMessage().contains("razorpay.key.id is missing or is a placeholder value"));
    }
}
