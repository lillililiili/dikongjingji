package com.lowaltitude.inspection.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import com.lowaltitude.inspection.infrastructure.cache.RedisIdempotencyProperties;
import com.lowaltitude.inspection.infrastructure.storage.MinioStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class InfrastructurePropertiesBindingTest {

    @Test
    void bindsRedisAndMinioSettingsWithoutEmbeddingConnectionDefaults() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "inspection.infrastructure.redis.application-namespace", "low-altitude",
                "inspection.infrastructure.redis.business-namespace", "inspection",
                "inspection.infrastructure.redis.idempotency-ttl", "PT45M",
                "inspection.infrastructure.storage.environment", "staging",
                "inspection.infrastructure.storage.endpoint", "https://minio.example.test",
                "inspection.infrastructure.storage.access-key", "configured-access",
                "inspection.infrastructure.storage.secret-key", "configured-secret",
                "inspection.infrastructure.storage.upload-ttl", "PT3M",
                "inspection.infrastructure.storage.download-ttl", "PT8M",
                "inspection.infrastructure.storage.reservation-allowed-mime-types[0]", "image/jpeg"
        )));

        RedisIdempotencyProperties redis = binder.bind(
                "inspection.infrastructure.redis", RedisIdempotencyProperties.class)
                .orElseThrow(() -> new AssertionError("Redis properties were not bound"));
        MinioStorageProperties storage = binder.bind(
                "inspection.infrastructure.storage", MinioStorageProperties.class)
                .orElseThrow(() -> new AssertionError("Storage properties were not bound"));

        assertThat(redis.idempotencyTtl()).isEqualTo(Duration.ofMinutes(45));
        assertThat(storage.quarantineBucket()).isEqualTo("staging-inspection-quarantine");
        assertThat(storage.privateBucket()).isEqualTo("staging-inspection-private");
        assertThat(storage.downloadTtl()).isEqualTo(Duration.ofMinutes(8));
        assertThat(storage.reservationAllowedMimeTypes()).containsExactly("image/jpeg");
    }
}
