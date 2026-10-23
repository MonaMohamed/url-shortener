package com.urlshortener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new CacheService(redisTemplate, 24);
    }

    @Test
    void cacheUrl_storesWithCorrectKeyAndTtl() {
        cacheService.cacheUrl("abc1234", "https://example.com");

        verify(valueOperations).set("url:abc1234", "https://example.com", Duration.ofHours(24));
    }

    @Test
    void getCachedUrl_returnsValueWhenPresent() {
        when(valueOperations.get("url:abc1234")).thenReturn("https://example.com");

        Optional<String> result = cacheService.getCachedUrl("abc1234");

        assertTrue(result.isPresent());
        assertEquals("https://example.com", result.get());
    }

    @Test
    void getCachedUrl_returnsEmptyWhenNotFound() {
        when(valueOperations.get("url:missing")).thenReturn(null);

        Optional<String> result = cacheService.getCachedUrl("missing");

        assertTrue(result.isEmpty());
    }

    @Test
    void evict_deletesBothUrlAndStatsKeys() {
        cacheService.evict("abc1234");

        verify(redisTemplate).delete("url:abc1234");
        verify(redisTemplate).delete("stats:abc1234");
    }

    @Test
    void cacheUrlStats_storesWithStatsPrefix() {
        cacheService.cacheUrlStats("abc1234", "{\"clickCount\":5}");

        verify(valueOperations).set(eq("stats:abc1234"), eq("{\"clickCount\":5}"), eq(Duration.ofMinutes(5)));
    }

    @Test
    void getCachedStats_returnsValueWhenPresent() {
        when(valueOperations.get("stats:abc1234")).thenReturn("{\"clickCount\":5}");

        Optional<String> result = cacheService.getCachedStats("abc1234");

        assertTrue(result.isPresent());
        assertEquals("{\"clickCount\":5}", result.get());
    }

    @Test
    void cacheUrl_gracefulDegradation_onRedisFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        assertDoesNotThrow(() -> cacheService.cacheUrl("abc1234", "https://example.com"));
    }

    @Test
    void getCachedUrl_gracefulDegradation_onRedisFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        Optional<String> result = cacheService.getCachedUrl("abc1234");

        assertTrue(result.isEmpty());
    }

    @Test
    void evict_gracefulDegradation_onRedisFailure() {
        when(redisTemplate.delete("url:abc1234")).thenThrow(new RedisConnectionFailureException("Connection refused"));

        assertDoesNotThrow(() -> cacheService.evict("abc1234"));
    }
}
