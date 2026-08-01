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
        return currentByQuery("code:" + cityCode, cityCode);
    }

    CurrentWeather currentByCityName(String requestedCityName) {
        String cityName = requestedCityName == null ? "" : requestedCityName.trim();
        if (cityName.length() < 2
                || cityName.length() > 40
                || !cityName.matches("[\\p{IsHan}A-Za-z0-9·\\-\\s]+")) {
            throw new IllegalArgumentException("cityName is invalid");
        }
        return currentByQuery("name:" + cityName, cityName);
    }

    private CurrentWeather currentByQuery(String cacheKey, String providerQuery) {
        Instant now = clock.instant();
        CachedWeather cached = cache.get(cacheKey);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.weather();
        }

        CurrentWeather weather = gateway.load(providerQuery);
        cache.put(cacheKey, new CachedWeather(weather, now.plus(cacheTtl)));
        return weather;
    }

    private record CachedWeather(CurrentWeather weather, Instant expiresAt) {
    }
}
