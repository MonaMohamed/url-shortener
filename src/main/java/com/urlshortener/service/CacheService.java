package com.urlshortener.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    private static final String URL_PREFIX = "url:";
    private static final String STATS_PREFIX = "stats:";
    private static final Duration STATS_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final Duration urlTtl;

    public CacheService(StringRedisTemplate redisTemplate,
                        @Value("${app.cache.ttl-hours:24}") int ttlHours) {
        this.redisTemplate = redisTemplate;
        this.urlTtl = Duration.ofHours(ttlHours);
    }

    public void cacheUrl(String shortCode, String originalUrl) {
        try {
            redisTemplate.opsForValue().set(URL_PREFIX + shortCode, originalUrl, urlTtl);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, skipping cache write for short code: {}", shortCode, e);
        }
    }

    public Optional<String> getCachedUrl(String shortCode) {
        try {
            String value = redisTemplate.opsForValue().get(URL_PREFIX + shortCode);
            return Optional.ofNullable(value);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, skipping cache read for short code: {}", shortCode, e);
            return Optional.empty();
        }
    }

    public void evict(String shortCode) {
        try {
            redisTemplate.delete(URL_PREFIX + shortCode);
            redisTemplate.delete(STATS_PREFIX + shortCode);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, skipping cache eviction for short code: {}", shortCode, e);
        }
    }

    public void cacheUrlStats(String shortCode, String statsJson) {
        try {
            redisTemplate.opsForValue().set(STATS_PREFIX + shortCode, statsJson, STATS_TTL);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, skipping stats cache write for short code: {}", shortCode, e);
        }
    }

    public Optional<String> getCachedStats(String shortCode) {
        try {
            String value = redisTemplate.opsForValue().get(STATS_PREFIX + shortCode);
            return Optional.ofNullable(value);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, skipping stats cache read for short code: {}", shortCode, e);
            return Optional.empty();
        }
    }
}