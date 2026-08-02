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
    private final PlatformWeatherCache durableCache;
    private final ConcurrentMap<String, PlatformWeatherCache.Snapshot> cache =
            new ConcurrentHashMap<>();

    PlatformWeatherService(
            CurrentWeatherGateway gateway,
            Clock clock,
            Duration cacheTtl
    ) {
        this(gateway, clock, cacheTtl, PlatformWeatherCache.noOp());
    }

    PlatformWeatherService(
            CurrentWeatherGateway gateway,
            Clock clock,
            Duration cacheTtl,
            PlatformWeatherCache durableCache
    ) {
        if (cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalArgumentException("weather cache ttl must be positive");
        }
        this.gateway = gateway;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
        this.durableCache = durableCache;
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
        PlatformWeatherCache.Snapshot cached = cache.get(cacheKey);
        if (cached == null) {
            cached = durableCache.find(cacheKey).orElse(null);
            if (cached != null) {
                cache.put(cacheKey, cached);
            }
        }
        if (cached != null && now.isBefore(cached.cachedAt().plus(cacheTtl))) {
            return cached.weather();
        }

        try {
            CurrentWeather weather = gateway.load(providerQuery);
            cacheSuccessfulWeather(cacheKey, weather, now);
            return weather;
        } catch (PlatformWeatherUnavailableException exception) {
            if (cached != null) {
                return cached.weather();
            }
            throw exception;
        }
    }

    private void cacheSuccessfulWeather(String requestedKey, CurrentWeather weather, Instant cachedAt) {
        PlatformWeatherCache.Snapshot snapshot =
                new PlatformWeatherCache.Snapshot(weather, cachedAt);
        for (String cacheKey : new String[] {
                requestedKey,
                "code:" + weather.cityCode(),
                "name:" + weather.cityName()
        }) {
            cache.put(cacheKey, snapshot);
            durableCache.save(cacheKey, weather, cachedAt);
        }
    }
}
