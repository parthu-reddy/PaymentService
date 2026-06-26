package com.fooddelivery.payments.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class IdempotencyFilterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private IdempotencyFilter filter;

    private StringWriter stringWriter;

    @BeforeEach
    void setUp() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(request.getMethod()).thenReturn("POST");
        lenient().when(request.getRequestURI()).thenReturn("/api/webhooks/vyapar");
        
        stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        lenient().when(response.getWriter()).thenReturn(printWriter);
    }

    @Test
    void testDoFilterInternal_AcquiresLock() throws Exception {
        when(request.getHeader("Idempotency-Key")).thenReturn("evt_123");
        when(valueOperations.setIfAbsent(eq("idempotency:evt_123:lock"), eq("PROCESSING"), any(Duration.class))).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternal_Conflict() throws Exception {
        when(request.getHeader("Idempotency-Key")).thenReturn("evt_123");
        when(valueOperations.setIfAbsent(eq("idempotency:evt_123:lock"), eq("PROCESSING"), any(Duration.class))).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(409);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternal_ReleasesLockOn5xx() throws Exception {
        when(request.getHeader("Idempotency-Key")).thenReturn("evt_123");
        when(valueOperations.setIfAbsent(eq("idempotency:evt_123:lock"), eq("PROCESSING"), any(Duration.class))).thenReturn(true);
        when(response.getStatus()).thenReturn(500);

        filter.doFilterInternal(request, response, filterChain);

        verify(redisTemplate).delete("idempotency:evt_123:lock");
    }

    @Test
    void testDoFilterInternal_ReleasesLockOnException() throws Exception {
        when(request.getHeader("Idempotency-Key")).thenReturn("evt_123");
        when(valueOperations.setIfAbsent(eq("idempotency:evt_123:lock"), eq("PROCESSING"), any(Duration.class))).thenReturn(true);
        
        doThrow(new RuntimeException("Test Exception")).when(filterChain).doFilter(any(), any());

        try {
            filter.doFilterInternal(request, response, filterChain);
        } catch (Exception e) {
            // expected
        }

        verify(redisTemplate).delete("idempotency:evt_123:lock");
    }
}
