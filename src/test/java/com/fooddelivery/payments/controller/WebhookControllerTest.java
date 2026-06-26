package com.fooddelivery.payments.controller;

import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.service.WebhookProcessingService;
import com.fooddelivery.payments.service.gateway.IPaymentGatewayStrategy;
import com.fooddelivery.payments.filter.RequestCachingFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookController.class)
public class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WebhookProcessingService webhookProcessingService;

    @MockBean
    private PaymentGatewayOrchestrator orchestrator;

    @MockBean
    private IPaymentGatewayStrategy vyaparStrategy;

    @Autowired
    private WebApplicationContext context;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(new RequestCachingFilter())
                .build();
    }

    @Test
    void testMissingSignature_Returns401() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/vyapar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"payment.success\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Missing Signature"));
    }

    @Test
    void testValidWebhook_WithoutEventId_GeneratesDeterministicHash() throws Exception {
        String payload = "{\"event\":\"payment.success\"}";
        String signature = "valid_signature";
        
        // Expected SHA-256 hash of the payload
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        String expectedHash = hexString.toString();

        when(webhookProcessingService.isEventProcessed(expectedHash)).thenReturn(false);
        when(orchestrator.getStrategy("VYAPAR")).thenReturn(vyaparStrategy);
        when(vyaparStrategy.verifyWebhookSignature(eq(payload), eq(signature), isNull())).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhooks/vyapar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-VyaparGateway-Signature", signature)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("Webhook Received and Verified"));

        verify(webhookProcessingService).processWebhookAsync(expectedHash, "VYAPAR", payload);
    }
}
