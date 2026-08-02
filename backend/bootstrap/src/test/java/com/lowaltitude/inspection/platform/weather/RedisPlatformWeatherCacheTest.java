package com.lowaltitude.inspection.platform.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisPlatformWeatherCacheTest {

    private static final Instant NOW = Instant.parse("2026-08-01T08:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(24);

    @Test
    void storesAndRestoresTheLastSuccessfulWeatherWithARetentionTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisPlatformWeatherCache cache = cache(redis);
        CurrentWeather weather = new CurrentWeather(
                "370500",
                "东营市",
                WeatherConditionCode.CLEAR,
                "晴",
                new BigDecimal("28"),
                NOW);

        cache.save("name:东营市", weather, NOW);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(values).set(
                org.mockito.ArgumentMatchers.eq("low-altitude:platform:weather:name:东营市"),
                payload.capture(),
                org.mockito.ArgumentMatchers.eq(RETENTION));
        when(values.get("low-altitude:platform:weather:name:东营市"))
                .thenReturn(payload.getValue());

        assertThat(cache.find("name:东营市")).contains(
                new PlatformWeatherCache.Snapshot(weather, NOW));
    }

    @Test
    void redisFailuresDoNotReplaceTheProviderFailurePolicy() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        RedisPlatformWeatherCache cache = cache(redis);

        assertThat(cache.find("code:370500")).isEmpty();
        assertThatCode(() -> cache.save(
                "code:370500",
                new CurrentWeather(
                        "370500",
                        "东营市",
                        WeatherConditionCode.UNKNOWN,
                        "",
                        BigDecimal.ZERO,
                        NOW),
                NOW)).doesNotThrowAnyException();
    }

    private RedisPlatformWeatherCache cache(StringRedisTemplate redis) {
        return new RedisPlatformWeatherCache(
                redis,
                new ObjectMapper().findAndRegisterModules(),
                "low-altitude:platform:weather",
                RETENTION);
    }
}
