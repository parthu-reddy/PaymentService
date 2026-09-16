package com.fooddelivery.payments.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.fooddelivery.payments.service.PaymentGatewayOrchestrator;
import com.fooddelivery.payments.repository.IPaymentIntentRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.context.annotation.Import;

@WebMvcTest(PaymentController.class)
@Import(com.fooddelivery.common.security.CommonSecurityConfig.class)
class PaymentControllerAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentGatewayOrchestrator orchestrator;

    @MockBean
    private IPaymentIntentRepository paymentIntentRepository;

    @MockBean
    private com.fooddelivery.payments.config.PaymentRoutingConfig paymentRoutingConfig;

    @MockBean
    private com.fooddelivery.payments.service.PaymentCompletionScheduler paymentCompletionScheduler;

    @MockBean
    private org.springframework.data.redis.core.RedisOperations<String, String> redisOperations;

    @MockBean
    private com.fooddelivery.common.security.IdentityTokenService identityTokenService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.when(paymentRoutingConfig.getGatewayForMethod(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.fooddelivery.common.enums.PaymentGateway.RAZORPAY);
        org.mockito.Mockito.when(orchestrator.createOrder(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("gateway-order-123");
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotCreateOrder() throws Exception {
        mockMvc.perform(post("/api/v1/internal/payments/create-order?gateway=RAZORPAY")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"internalOrderId\":\"54019a2f-1a98-4444-8888-000000000000\",\"amountInInr\":100,\"paymentMethod\":\"CARD\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotRefund() throws Exception {
        mockMvc.perform(post("/api/v1/internal/payments/refund?gateway=RAZORPAY")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gatewayOrderId\":\"order_123\",\"refundId\":\"ref_123\",\"amountInInr\":100}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SERVICE")
    void serviceCanCreateOrder() throws Exception {
        mockMvc.perform(post("/api/v1/internal/payments/create-order?gateway=RAZORPAY")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"internalOrderId\":\"54019a2f-1a98-4444-8888-000000000000\",\"amountInInr\":100,\"paymentMethod\":\"CARD\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateOrder() throws Exception {
        mockMvc.perform(post("/api/v1/internal/payments/create-order?gateway=RAZORPAY")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"internalOrderId\":\"54019a2f-1a98-4444-8888-000000000000\",\"amountInInr\":100,\"paymentMethod\":\"CARD\"}"))
                .andExpect(status().isOk());
    }
}
