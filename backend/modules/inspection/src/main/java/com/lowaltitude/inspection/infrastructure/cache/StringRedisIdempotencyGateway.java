package com.lowaltitude.inspection.infrastructure.cache;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;

public final class StringRedisIdempotencyGateway implements RedisIdempotencyGateway {

    private final StringRedisTemplate redisTemplate;

    public StringRedisIdempotencyGateway(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean putIfAbsent(String key, String requestDigest, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, requestDigest, ttl));
    }

    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }
}
