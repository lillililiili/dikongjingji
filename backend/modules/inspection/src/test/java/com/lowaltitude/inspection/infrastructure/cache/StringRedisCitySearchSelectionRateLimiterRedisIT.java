package com.lowaltitude.inspection.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.lowaltitude.inspection.application.city.CitySearchSelectionRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class StringRedisCitySearchSelectionRateLimiterRedisIT {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisIdempotencyProperties namespaces;
    private final List<String> testKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        int port = Integer.getInteger("city.rate-limit.redis.port");
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        namespaces = new RedisIdempotencyProperties(
                "city-rate-limit-it-" + UUID.randomUUID(),
                "inspection",
                Duration.ofHours(1));
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null && !testKeys.isEmpty()) {
            redisTemplate.delete(testKeys);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void allowsTwentySelectionsThenReturnsTheRemainingTtl() {
        String clientAddress = "203.0.113.20";
        StringRedisCitySearchSelectionRateLimiter limiter = limiter(20, Duration.ofSeconds(5));

        List<CitySearchSelectionRateLimiter.Decision> decisions = new ArrayList<>();
        for (int request = 0; request < 21; request++) {
            decisions.add(limiter.check(clientAddress));
        }

        assertThat(decisions.subList(0, 20))
                .allMatch(decision -> decision.status()
                        == CitySearchSelectionRateLimiter.Status.ALLOWED);
        assertThat(decisions.get(20).status())
                .isEqualTo(CitySearchSelectionRateLimiter.Status.RATE_LIMITED);
        assertThat(decisions.get(20).retryAfter()).isPositive();
    }

    @Test
    void resetsTheCounterAfterTheWindowExpires() throws InterruptedException {
        StringRedisCitySearchSelectionRateLimiter limiter = limiter(1, Duration.ofMillis(100));

        assertThat(limiter.check("203.0.113.21").status())
                .isEqualTo(CitySearchSelectionRateLimiter.Status.ALLOWED);
        assertThat(limiter.check("203.0.113.21").status())
                .isEqualTo(CitySearchSelectionRateLimiter.Status.RATE_LIMITED);
        Thread.sleep(200);
        assertThat(limiter.check("203.0.113.21").status())
                .isEqualTo(CitySearchSelectionRateLimiter.Status.ALLOWED);
    }

    @Test
    void allowsExactlyTwentyOfFortyConcurrentSelections() throws Exception {
        String clientAddress = "203.0.113.22";
        StringRedisCitySearchSelectionRateLimiter limiter = limiter(20, Duration.ofSeconds(5));
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CitySearchSelectionRateLimiter.Decision>> futures = new ArrayList<>();
            for (int request = 0; request < 40; request++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return limiter.check(clientAddress);
                }));
            }
            start.countDown();

            List<CitySearchSelectionRateLimiter.Decision> decisions = new ArrayList<>();
            for (Future<CitySearchSelectionRateLimiter.Decision> future : futures) {
                decisions.add(future.get());
            }
            assertThat(decisions).filteredOn(decision ->
                    decision.status() == CitySearchSelectionRateLimiter.Status.ALLOWED)
                    .hasSize(20);
            assertThat(decisions).filteredOn(decision ->
                    decision.status() == CitySearchSelectionRateLimiter.Status.RATE_LIMITED)
                    .hasSize(20);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failsClosedWhenTheCounterKeyHasNoExpiry() {
        String clientAddress = "203.0.113.23";
        String key = rateLimitKey(clientAddress);
        testKeys.add(key);
        redisTemplate.opsForValue().set(key, "20");

        CitySearchSelectionRateLimiter.Decision decision =
                limiter(20, Duration.ofSeconds(5)).check(clientAddress);

        assertThat(decision).isEqualTo(CitySearchSelectionRateLimiter.Decision.unavailable());
    }

    @Test
    void rateLimitKeysDoNotAffectIdempotencyKeys() {
        String clientAddress = "203.0.113.24";
        String idempotencyKey = namespaces.applicationNamespace()
                + ":inspection:idempotency:reservation-create:actor:key";
        testKeys.add(idempotencyKey);
        redisTemplate.opsForValue().set(idempotencyKey, "digest", Duration.ofMinutes(5));

        assertThat(limiter(20, Duration.ofSeconds(5)).check(clientAddress))
                .isEqualTo(CitySearchSelectionRateLimiter.Decision.allowed());
        assertThat(redisTemplate.opsForValue().get(idempotencyKey)).isEqualTo("digest");
        assertThat(redisTemplate.getExpire(idempotencyKey)).isPositive();
    }

    private StringRedisCitySearchSelectionRateLimiter limiter(int limit, Duration window) {
        return new StringRedisCitySearchSelectionRateLimiter(
                redisTemplate,
                namespaces,
                new CitySearchSelectionRateLimitProperties(limit, window));
    }

    private String rateLimitKey(String clientAddress) {
        String key = namespaces.applicationNamespace()
                + ":inspection:rate-limit:platform-city-search-selection:" + clientAddress;
        testKeys.add(key);
        return key;
    }
}
