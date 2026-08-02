package com.lowaltitude.inspection.platform.weather;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

final class RedisPlatformWeatherCache implements PlatformWeatherCache {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration retentionTtl;

    RedisPlatformWeatherCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            String keyPrefix,
            Duration retentionTtl
    ) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("weather cache key prefix must not be blank");
        }
        if (retentionTtl.isNegative() || retentionTtl.isZero()) {
            throw new IllegalArgumentException("weather cache retention ttl must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix.replaceAll(":+$", "");
        this.retentionTtl = retentionTtl;
    }

    @Override
    public Optional<Snapshot> find(String cacheKey) {
        try {
            String payload = redisTemplate.opsForValue().get(redisKey(cacheKey));
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            StoredWeather stored = objectMapper.readValue(payload, StoredWeather.class);
            return Optional.of(stored.toSnapshot());
        } catch (RuntimeException | JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void save(String cacheKey, CurrentWeather weather, Instant cachedAt) {
        try {
            String payload = objectMapper.writeValueAsString(StoredWeather.from(weather, cachedAt));
            redisTemplate.opsForValue().set(redisKey(cacheKey), payload, retentionTtl);
        } catch (RuntimeException | JsonProcessingException exception) {
            // Weather must remain available from the provider when Redis is degraded.
        }
    }

    private String redisKey(String cacheKey) {
        return keyPrefix + ":" + cacheKey;
    }

    private record StoredWeather(
            String cityCode,
            String cityName,
            WeatherConditionCode conditionCode,
            String conditionText,
            BigDecimal temperatureCelsius,
            Instant observedAt,
            Instant cachedAt
    ) {
        private static StoredWeather from(CurrentWeather weather, Instant cachedAt) {
            return new StoredWeather(
                    weather.cityCode(),
                    weather.cityName(),
                    weather.conditionCode(),
                    weather.conditionText(),
                    weather.temperatureCelsius(),
                    weather.observedAt(),
                    cachedAt);
        }

        private Snapshot toSnapshot() {
            return new Snapshot(
                    new CurrentWeather(
                            cityCode,
                            cityName,
                            conditionCode,
                            conditionText,
                            temperatureCelsius,
                            observedAt),
                    cachedAt);
        }
    }
}
