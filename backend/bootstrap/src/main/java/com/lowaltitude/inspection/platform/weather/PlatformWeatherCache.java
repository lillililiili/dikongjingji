package com.lowaltitude.inspection.platform.weather;

import java.time.Instant;
import java.util.Optional;

interface PlatformWeatherCache {

    Optional<Snapshot> find(String cacheKey);

    void save(String cacheKey, CurrentWeather weather, Instant cachedAt);

    static PlatformWeatherCache noOp() {
        return new PlatformWeatherCache() {
            @Override
            public Optional<Snapshot> find(String cacheKey) {
                return Optional.empty();
            }

            @Override
            public void save(String cacheKey, CurrentWeather weather, Instant cachedAt) {
                // Unit tests and standalone controller tests do not require durable caching.
            }
        };
    }

    record Snapshot(CurrentWeather weather, Instant cachedAt) {
    }
}
