package com.fooddelivery.payments.filter;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;

@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    public IdempotencyFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER);

        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String cacheKey = "idempotency:" + idempotencyKey;

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(cacheKey + ":lock", "PROCESSING", Duration.ofMinutes(5));
        if (Boolean.FALSE.equals(acquired)) {
            response.setStatus(200); // OK
            response.getWriter().write("Request is already being processed or has been processed");
            return;
        }

        try {
            ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
            
            filterChain.doFilter(request, responseWrapper);
            
            // If we needed to cache the response body, we would read it from responseWrapper
            // and save to redis `cacheKey:response`.
            
            responseWrapper.copyBodyToResponse();
            
        } finally {
            // Keep the lock with a TTL to prevent retries from going through immediately
            // In a robust implementation, you update the key to store the success state
        }
    }
}
