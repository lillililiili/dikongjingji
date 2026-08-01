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
        long windowMillis;
        try {
            windowMillis = window.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("window must fit in milliseconds", exception);
        }
        if (windowMillis < 1L) {
            throw new IllegalArgumentException("window must be at least one millisecond");
        }
    }
}
