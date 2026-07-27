package com.lowaltitude.inspection.infrastructure.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("inspection.infrastructure.redis")
public record RedisIdempotencyProperties(
        String applicationNamespace,
        String businessNamespace,
        Duration idempotencyTtl
) {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    public RedisIdempotencyProperties {
        requireNamespace(applicationNamespace, "applicationNamespace");
        requireNamespace(businessNamespace, "businessNamespace");
        Objects.requireNonNull(idempotencyTtl, "idempotencyTtl must not be null");
        if (idempotencyTtl.isZero() || idempotencyTtl.isNegative()) {
            throw new IllegalArgumentException("idempotencyTtl must be positive");
        }
    }

    private static void requireNamespace(String value, String name) {
        if (value == null || !NAMESPACE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a safe namespace");
        }
    }
}
