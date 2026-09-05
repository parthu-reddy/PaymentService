package com.fooddelivery.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.repository.IIdempotencyKeyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentEventConsumerRefundIdempotencyTest {

    private PaymentEventConsumer paymentEventConsumer;
    private ObjectMapper objectMapper;
    private PaymentGatewayOrchestrator orchestrator;
    private MeterRegistry meterRegistry;
    private WebhookProcessingService webhookProcessingService;
    private IIdempotencyKeyRepository idempotencyKeyRepository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        orchestrator = Mockito.mock(PaymentGatewayOrchestrator.class);
        meterRegistry = Mockito.mock(MeterRegistry.class);
        webhookProcessingService = Mockito.mock(WebhookProcessingService.class);
        idempotencyKeyRepository = Mockito.mock(IIdempotencyKeyRepository.class);

        paymentEventConsumer = new PaymentEventConsumer(
                objectMapper, orchestrator, meterRegistry, webhookProcessingService, idempotencyKeyRepository
        );
    }

    @Test
    void shouldIgnoreDuplicateRefundRequest() throws Exception {
        // Arrange
        String payload = """
                {
                    "eventType": "PAYMENT_REFUND_REQUESTED",
                    "payload": {
                        "gatewayOrderId": "gw_123",
                        "amountInInr": 100.0,
                        "gatewayName": "RAZORPAY",
                        "refundId": "refund_456"
                    }
                }
                """;

        String refundIdempotencyKey = "refund_req:refund_456";

        when(idempotencyKeyRepository.existsById("processed_event:event_789")).thenReturn(false);
        when(idempotencyKeyRepository.existsById(refundIdempotencyKey)).thenReturn(true);

        java.util.Map<String, Object> headers = new java.util.HashMap<>();
        headers.put("eventId", "event_789");

        // Act
        paymentEventConsumer.consumeOrderEvents(payload, headers);

        // Assert
        verify(orchestrator, never()).initiateRefund(any(), any(), any(), any(), any());
        verify(webhookProcessingService, never()).handleRefundFailure(any(), any(), any());
    }

    @Test
    void shouldProcessNewRefundRequest() throws Exception {
        // Arrange
        String payload = """
                {
                    "eventType": "PAYMENT_REFUND_REQUESTED",
                    "payload": {
                        "gatewayOrderId": "gw_123",
                        "amountInInr": 100.0,
                        "gatewayName": "RAZORPAY",
                        "refundId": "refund_456"
                    }
                }
                """;

        String refundIdempotencyKey = "refund_req:refund_456";

        when(idempotencyKeyRepository.existsById("processed_event:event_789")).thenReturn(false);
        when(idempotencyKeyRepository.existsById(refundIdempotencyKey)).thenReturn(false);
        when(orchestrator.initiateRefund(any(), anyString(), anyString(), any(), anyString())).thenReturn(true);
        io.micrometer.core.instrument.Counter counter = Mockito.mock(io.micrometer.core.instrument.Counter.class);
        when(meterRegistry.counter(anyString(), anyString(), anyString())).thenReturn(counter);

        java.util.Map<String, Object> headers = new java.util.HashMap<>();
        headers.put("eventId", "event_789");

        // Act
        paymentEventConsumer.consumeOrderEvents(payload, headers);

        // Assert
        verify(orchestrator).initiateRefund(
                com.fooddelivery.common.enums.PaymentGateway.RAZORPAY, "gw_123", "refund_456", new java.math.BigDecimal("100.0"), "Order cancelled or rejected"
        );
        verify(idempotencyKeyRepository).save(Mockito.argThat(key -> key.getIdempotencyKey().equals(refundIdempotencyKey)));
    }
}
