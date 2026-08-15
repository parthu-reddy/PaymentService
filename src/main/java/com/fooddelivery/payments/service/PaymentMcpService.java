package com.fooddelivery.payments.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.enums.PaymentGateway;
import com.fooddelivery.payments.controller.PaymentController;
import com.fooddelivery.payments.controller.WebhookController;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

@Service
@lombok.extern.slf4j.Slf4j
public class PaymentMcpService {

    private final PaymentController paymentController;
    private final WebhookController webhookController;
    private final ObjectMapper objectMapper;

    public PaymentMcpService(PaymentController paymentController,
                             WebhookController webhookController,
                             ObjectMapper objectMapper) {
        this.paymentController = paymentController;
        this.webhookController = webhookController;
        this.objectMapper = objectMapper;
    }

    private HttpServletRequest createMockRequest(String body) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getName().equals("getInputStream")) {
                            byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
                            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                            return new ServletInputStream() {
                                @Override
                                public boolean isFinished() { return bais.available() == 0; }
                                @Override
                                public boolean isReady() { return true; }
                                @Override
                                public void setReadListener(ReadListener readListener) {}
                                @Override
                                public int read() throws IOException { return bais.read(); }
                                @Override
                                public int read(byte[] b, int off, int len) throws IOException { return bais.read(b, off, len); }
                            };
                        }
                        if (method.getName().equals("getCharacterEncoding")) {
                            return "UTF-8";
                        }
                        return null;
                    }
                }
        );
    }

    @Tool(description = "Handle Razorpay Webhook. Provide raw json body, signature, eventId (optional).")
    public String handleRazorpayWebhook(String rawBody, String signature, String eventId) {
        try {
            return objectMapper.writeValueAsString(webhookController.handleRazorpayWebhook(createMockRequest(rawBody), signature, eventId).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Handle Cashfree Webhook. Provide raw json body, signature, timestamp, eventId (optional).")
    public String handleCashfreeWebhook(String rawBody, String signature, String timestamp, String eventId) {
        try {
            return objectMapper.writeValueAsString(webhookController.handleCashfreeWebhook(createMockRequest(rawBody), signature, timestamp, eventId).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Handle Vyapar Webhook. Provide raw json body, signature, eventId (optional).")
    public String handleVyaparWebhook(String rawBody, String signature, String eventId) {
        try {
            return objectMapper.writeValueAsString(webhookController.handleVyaparWebhook(createMockRequest(rawBody), signature, eventId).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Create a payment order. Provide gateway name (e.g. RAZORPAY) and JSON string of CreateOrderRequest (internalOrderId, amountInInr, customerPhone).")
    public String createOrder(String gateway, String requestJson) {
        try {
            PaymentController.CreateOrderRequest req = objectMapper.readValue(requestJson, PaymentController.CreateOrderRequest.class);
            return objectMapper.writeValueAsString(paymentController.createOrder(PaymentGateway.valueOf(gateway.toUpperCase()), req).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Refund a payment order. Provide gateway name and JSON string of RefundRequest (gatewayOrderId, amountInInr, reason).")
    public String refundOrder(String gateway, String requestJson) {
        try {
            PaymentController.RefundRequest req = objectMapper.readValue(requestJson, PaymentController.RefundRequest.class);
            return objectMapper.writeValueAsString(paymentController.refundOrder(PaymentGateway.valueOf(gateway.toUpperCase()), req).getBody());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
