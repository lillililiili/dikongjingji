package com.lowaltitude.inspection.infrastructure.cache;

import java.time.Duration;
import java.util.List;

import com.lowaltitude.inspection.application.city.CitySearchSelectionRateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class StringRedisCitySearchSelectionRateLimiter
        implements CitySearchSelectionRateLimiter {

    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if current <= tonumber(ARGV[2]) then
              return -3
            end
            return redis.call('PTTL', KEYS[1])
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisIdempotencyProperties namespaces;
    private final CitySearchSelectionRateLimitProperties properties;

    public StringRedisCitySearchSelectionRateLimiter(
            StringRedisTemplate redisTemplate,
            RedisIdempotencyProperties namespaces,
            CitySearchSelectionRateLimitProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.namespaces = namespaces;
        this.properties = properties;
    }

    @Override
    public Decision check(String clientAddress) {
        try {
            String clientKey = clientAddress == null || clientAddress.isBlank()
                    ? "unknown"
                    : clientAddress;
            String key = String.join(
                    ":",
                    namespaces.applicationNamespace(),
                    namespaces.businessNamespace(),
                    "rate-limit",
                    "platform-city-search-selection",
                    clientKey);
            Long result = redisTemplate.execute(
                    FIXED_WINDOW_SCRIPT,
                    List.of(key),
                    Long.toString(properties.window().toMillis()),
                    Integer.toString(properties.limit()));
            if (result == null) {
                return Decision.unavailable();
            }
            if (result == -3L) {
                return Decision.allowed();
            }
            return result >= 0L
                    ? Decision.rateLimited(Duration.ofMillis(result))
                    : Decision.unavailable();
        } catch (RuntimeException exception) {
            return Decision.unavailable();
        }
    }
}
