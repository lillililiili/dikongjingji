package com.lowaltitude.inspection.platform.weather;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class PlatformWeatherService {

    private final CurrentWeatherGateway gateway;
    private final Clock clock;
    private final Duration cacheTtl;
    private final ConcurrentMap<String, CachedWeather> cache = new ConcurrentHashMap<>();

    PlatformWeatherService(
            CurrentWeatherGateway gateway,
            Clock clock,
            Duration cacheTtl
    ) {
        if (cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalArgumentException("weather cache ttl must be positive");
        }
        this.gateway = gateway;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    CurrentWeather current(String requestedCityCode) {
        String cityCode = requestedCityCode == null ? "" : requestedCityCode.trim();
        if (!cityCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("cityCode must be a six-digit adcode");
        }

        Instant now = clock.instant();
        CachedWeather cached = cache.get(cityCode);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.weather();
        }

        CurrentWeather weather = gateway.load(cityCode);
        cache.put(cityCode, new CachedWeather(weather, now.plus(cacheTtl)));
        return weather;
    }

    private record CachedWeather(CurrentWeather weather, Instant expiresAt) {
    }
}
