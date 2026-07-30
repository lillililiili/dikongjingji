package com.lowaltitude.inspection.infrastructure.cache;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("inspection.infrastructure.redis.city-search-selection-rate-limit")
public record CitySearchSelectionRateLimitProperties(int limit, Duration window) {

    public CitySearchSelectionRateLimitProperties {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Objects.requireNonNull(window, "window must not be null");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
    }
}
