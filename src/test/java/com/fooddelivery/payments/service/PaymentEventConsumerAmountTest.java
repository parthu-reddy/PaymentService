package com.fooddelivery.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.enums.PaymentGateway;
import com.fooddelivery.common.repository.IIdempotencyKeyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The refund amount must reach the gateway exactly as it appeared on the wire.
 *
 * <p>Until 2026-08-27 it was read as {@code BigDecimal.valueOf(node.asDouble(0))}. The fix is NOT at
 * this call site: on a {@code DoubleNode} that expression and {@code new BigDecimal(asText())} give
 * byte-identical results, because the number is already through binary floating point by the time
 * the tree exists. The fix is {@code USE_BIG_DECIMAL_FOR_FLOATS} on the platform ObjectMapper, which
 * makes the node a {@code DecimalNode}.
 *
 * <p>The test therefore uses a value where the two genuinely differ. Scale is deliberately NOT
 * asserted: Jackson strips trailing zeros, so {@code 15.50} reads back as {@code 15.5} whatever the
 * configuration. Scale is not a property JSON preserves.
 */
class PaymentEventConsumerAmountTest {

    /** Mirrors the platform mapper: see JacksonConfig in CommonLibrary. */
    private static ObjectMapper platformMapper() {
        return new ObjectMapper().enable(
                com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Test
    void refundAmountKeepsItsWireScale() throws Exception {
        PaymentGatewayOrchestrator orchestrator = mock(PaymentGatewayOrchestrator.class);
        when(orchestrator.initiateRefund(any(), anyString(), any(), anyString())).thenReturn(true);
        IIdempotencyKeyRepository keys = mock(IIdempotencyKeyRepository.class);
        when(keys.existsById(anyString())).thenReturn(false);

        PaymentEventConsumer consumer = new PaymentEventConsumer(
                platformMapper(), orchestrator, new SimpleMeterRegistry(),
                mock(WebhookProcessingService.class), keys);

        // A value a double cannot hold: as a DoubleNode this becomes 12345678901234568.
        String exact = "12345678901234567.89";
        String payload = "{\"eventType\":\"PAYMENT_REFUND_REQUESTED\",\"gatewayOrderId\":\"pay_1\","
                + "\"amountInInr\":" + exact + ",\"gatewayName\":\"RAZORPAY\",\"refundDestination\":\"GATEWAY\"}";
        consumer.consumeOrderEvents(payload, Map.of());

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(orchestrator).initiateRefund(eq(PaymentGateway.RAZORPAY), eq("pay_1"), amount.capture(), anyString());

        // compareTo for value -- BigDecimal.equals compares scale and would pass for the wrong reason
        assertThat(amount.getValue()).usingComparator(BigDecimal::compareTo)
                .as("every digit on the wire must reach the gateway")
                .isEqualTo(new BigDecimal(exact));
    }

    @Test
    void anEventThisConsumerIgnoresDoesNotClaimAnIdempotencyKey() throws Exception {
        IIdempotencyKeyRepository keys = mock(IIdempotencyKeyRepository.class);
        when(keys.existsById(anyString())).thenReturn(false);

        PaymentEventConsumer consumer = new PaymentEventConsumer(
                platformMapper(), mock(PaymentGatewayOrchestrator.class), new SimpleMeterRegistry(),
                mock(WebhookProcessingService.class), keys);

        // PAYMENT_COMPLETED is published on payment-events by this service's own webhook processor
        consumer.consumeOrderEvents("{\"eventType\":\"PAYMENT_COMPLETED\",\"orderId\":\"o1\"}", Map.of());

        verify(keys, never()).save(any());
    }
}
