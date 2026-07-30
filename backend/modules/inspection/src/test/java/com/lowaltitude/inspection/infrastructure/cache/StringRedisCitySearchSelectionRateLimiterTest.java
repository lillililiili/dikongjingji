package com.lowaltitude.inspection.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.lowaltitude.inspection.application.city.CitySearchSelectionRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class StringRedisCitySearchSelectionRateLimiterTest {

    private static final RedisIdempotencyProperties NAMESPACES =
            new RedisIdempotencyProperties("low-altitude", "inspection", Duration.ofHours(24));
    private static final CitySearchSelectionRateLimitProperties RATE_LIMIT =
            new CitySearchSelectionRateLimitProperties(20, Duration.ofSeconds(60));

    @Test
    void allowsSelectionsWithinTheFixedWindowLimit() {
        StubStringRedisTemplate redisTemplate = new StubStringRedisTemplate(0L);
        StringRedisCitySearchSelectionRateLimiter limiter =
                new StringRedisCitySearchSelectionRateLimiter(redisTemplate, NAMESPACES, RATE_LIMIT);

        CitySearchSelectionRateLimiter.Decision decision = limiter.check("203.0.113.7");

        assertThat(decision).isEqualTo(CitySearchSelectionRateLimiter.Decision.allowed());
        assertThat(redisTemplate.lastKeys).containsExactly(
                "low-altitude:inspection:rate-limit:platform-city-search-selection:203.0.113.7");
        assertThat(redisTemplate.lastArguments).containsExactly("60000", "20");
        assertThat(redisTemplate.lastScript)
                .contains("INCR", "PEXPIRE", "PTTL");
    }

    @Test
    void failsClosedWhenRedisReportsAWindowWithoutAnExpiry() {
        StringRedisCitySearchSelectionRateLimiter limiter =
                new StringRedisCitySearchSelectionRateLimiter(
                        new StubStringRedisTemplate(-1L), NAMESPACES, RATE_LIMIT);

        assertThat(limiter.check("203.0.113.7"))
                .isEqualTo(CitySearchSelectionRateLimiter.Decision.unavailable());
    }

    @Test
    void groupsMissingContainerAddressesIntoAControlledUnknownBucket() {
        StubStringRedisTemplate blankAddressRedis = new StubStringRedisTemplate(0L);
        StubStringRedisTemplate nullAddressRedis = new StubStringRedisTemplate(0L);

        assertThat(new StringRedisCitySearchSelectionRateLimiter(
                blankAddressRedis, NAMESPACES, RATE_LIMIT).check("  "))
                .isEqualTo(CitySearchSelectionRateLimiter.Decision.allowed());
        assertThat(new StringRedisCitySearchSelectionRateLimiter(
                nullAddressRedis, NAMESPACES, RATE_LIMIT).check(null))
                .isEqualTo(CitySearchSelectionRateLimiter.Decision.allowed());
        assertThat(blankAddressRedis.lastKeys).containsExactly(
                "low-altitude:inspection:rate-limit:platform-city-search-selection:unknown");
        assertThat(nullAddressRedis.lastKeys).containsExactly(
                "low-altitude:inspection:rate-limit:platform-city-search-selection:unknown");
    }

    @Test
    void returnsTheRemainingWindowTtlWhenTheLimitIsExceeded() {
        StubStringRedisTemplate redisTemplate = new StubStringRedisTemplate(2345L);
        StringRedisCitySearchSelectionRateLimiter limiter =
                new StringRedisCitySearchSelectionRateLimiter(redisTemplate, NAMESPACES, RATE_LIMIT);

        CitySearchSelectionRateLimiter.Decision decision = limiter.check("2001:db8::1");

        assertThat(decision).isEqualTo(CitySearchSelectionRateLimiter.Decision.rateLimited(
                Duration.ofMillis(2345)));
    }

    @Test
    void failsClosedWhenRedisReturnsNoScriptResult() {
        StringRedisCitySearchSelectionRateLimiter limiter =
                new StringRedisCitySearchSelectionRateLimiter(
                        new StubStringRedisTemplate(null), NAMESPACES, RATE_LIMIT);

        assertThat(limiter.check("203.0.113.7"))
                .isEqualTo(CitySearchSelectionRateLimiter.Decision.unavailable());
    }

    @Test
    void failsClosedWhenRedisThrows() {
        StringRedisCitySearchSelectionRateLimiter limiter =
                new StringRedisCitySearchSelectionRateLimiter(
                        new StubStringRedisTemplate(new IllegalStateException("redis unavailable")),
                        NAMESPACES,
                        RATE_LIMIT);

        assertThat(limiter.check("203.0.113.7"))
                .isEqualTo(CitySearchSelectionRateLimiter.Decision.unavailable());
    }

    private static final class StubStringRedisTemplate extends StringRedisTemplate {

        private final Object result;
        private List<String> lastKeys;
        private List<Object> lastArguments;
        private String lastScript;

        private StubStringRedisTemplate(Object result) {
            this.result = result;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            if (result instanceof RuntimeException exception) {
                throw exception;
            }
            lastScript = script.getScriptAsString();
            lastKeys = keys;
            lastArguments = List.of(args);
            return (T) result;
        }
    }
}
