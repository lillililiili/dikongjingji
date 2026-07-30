package com.lowaltitude.inspection.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import com.lowaltitude.inspection.infrastructure.cache.CitySearchSelectionRateLimitProperties;
import com.lowaltitude.inspection.infrastructure.cache.RedisIdempotencyProperties;
import com.lowaltitude.inspection.infrastructure.storage.MinioStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class InfrastructurePropertiesBindingTest {

    @Test
    void bindsRedisAndMinioSettingsWithoutEmbeddingConnectionDefaults() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("inspection.infrastructure.redis.application-namespace", "low-altitude"),
                Map.entry("inspection.infrastructure.redis.business-namespace", "inspection"),
                Map.entry("inspection.infrastructure.redis.idempotency-ttl", "PT45M"),
                Map.entry("inspection.infrastructure.redis.city-search-selection-rate-limit.limit", "20"),
                Map.entry("inspection.infrastructure.redis.city-search-selection-rate-limit.window", "PT1M"),
                Map.entry("inspection.infrastructure.storage.environment", "staging"),
                Map.entry("inspection.infrastructure.storage.endpoint", "https://minio.example.test"),
                Map.entry("inspection.infrastructure.storage.public-endpoint", "https://files.example.test"),
                Map.entry("inspection.infrastructure.storage.region", "us-east-1"),
                Map.entry("inspection.infrastructure.storage.access-key", "configured-access"),
                Map.entry("inspection.infrastructure.storage.secret-key", "configured-secret"),
                Map.entry("inspection.infrastructure.storage.upload-ttl", "PT3M"),
                Map.entry("inspection.infrastructure.storage.download-ttl", "PT8M"),
                Map.entry("inspection.infrastructure.storage.reservation-allowed-mime-types[0]", "image/jpeg")
        )));

        RedisIdempotencyProperties redis = binder.bind(
                "inspection.infrastructure.redis", RedisIdempotencyProperties.class)
                .orElseThrow(() -> new AssertionError("Redis properties were not bound"));
        CitySearchSelectionRateLimitProperties rateLimit = binder.bind(
                "inspection.infrastructure.redis.city-search-selection-rate-limit",
                CitySearchSelectionRateLimitProperties.class)
                .orElseThrow(() -> new AssertionError("Rate-limit properties were not bound"));
        MinioStorageProperties storage = binder.bind(
                "inspection.infrastructure.storage", MinioStorageProperties.class)
                .orElseThrow(() -> new AssertionError("Storage properties were not bound"));

        assertThat(redis.idempotencyTtl()).isEqualTo(Duration.ofMinutes(45));
        assertThat(rateLimit.limit()).isEqualTo(20);
        assertThat(rateLimit.window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(storage.quarantineBucket()).isEqualTo("staging-inspection-quarantine");
        assertThat(storage.privateBucket()).isEqualTo("staging-inspection-private");
        assertThat(storage.publicEndpoint()).isEqualTo(java.net.URI.create("https://files.example.test"));
        assertThat(storage.region()).isEqualTo("us-east-1");
        assertThat(storage.downloadTtl()).isEqualTo(Duration.ofMinutes(8));
        assertThat(storage.reservationAllowedMimeTypes()).containsExactly("image/jpeg");
    }

    @Test
    void citySearchSelectionRateLimitSettingsMustBePositive() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "inspection.infrastructure.redis.city-search-selection-rate-limit.limit", "0",
                "inspection.infrastructure.redis.city-search-selection-rate-limit.window", "PT1M"
        )));

        assertThatThrownBy(() -> binder.bind(
                "inspection.infrastructure.redis.city-search-selection-rate-limit",
                CitySearchSelectionRateLimitProperties.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("limit must be positive");
    }

    @Test
    void storageSettingsFailClosedWithoutAnExplicitEnvironment() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "inspection.infrastructure.storage.endpoint", "https://minio.example.test",
                "inspection.infrastructure.storage.public-endpoint", "https://files.example.test",
                "inspection.infrastructure.storage.region", "us-east-1",
                "inspection.infrastructure.storage.access-key", "configured-access",
                "inspection.infrastructure.storage.secret-key", "configured-secret",
                "inspection.infrastructure.storage.upload-ttl", "PT3M",
                "inspection.infrastructure.storage.download-ttl", "PT8M"
        )));

        assertThatThrownBy(() -> binder.bind(
                "inspection.infrastructure.storage", MinioStorageProperties.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("environment must be safe for bucket names");
    }

    @Test
    void nonLocalStoragePublicEndpointRequiresHttps() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "inspection.infrastructure.storage.environment", "staging",
                "inspection.infrastructure.storage.endpoint", "http://minio.internal:9000",
                "inspection.infrastructure.storage.public-endpoint", "http://files.example.test",
                "inspection.infrastructure.storage.region", "us-east-1",
                "inspection.infrastructure.storage.access-key", "configured-access",
                "inspection.infrastructure.storage.secret-key", "configured-secret",
                "inspection.infrastructure.storage.upload-ttl", "PT3M",
                "inspection.infrastructure.storage.download-ttl", "PT8M"
        )));

        assertThatThrownBy(() -> binder.bind(
                "inspection.infrastructure.storage", MinioStorageProperties.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("non-local publicEndpoint must use HTTPS");
    }
}
