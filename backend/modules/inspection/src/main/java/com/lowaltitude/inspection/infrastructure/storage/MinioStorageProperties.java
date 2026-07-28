package com.lowaltitude.inspection.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("inspection.infrastructure.storage")
public record MinioStorageProperties(
        String environment,
        URI endpoint,
        URI publicEndpoint,
        String region,
        String accessKey,
        String secretKey,
        Duration uploadTtl,
        Duration downloadTtl,
        Set<String> reservationAllowedMimeTypes
) {

    private static final Pattern ENVIRONMENT = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Duration MAX_DOWNLOAD_TTL = Duration.ofMinutes(10);
    private static final Set<String> LOCAL_ENVIRONMENTS = Set.of("dev", "test", "local");

    public MinioStorageProperties {
        if (environment == null || !ENVIRONMENT.matcher(environment).matches()) {
            throw new IllegalArgumentException("environment must be safe for bucket names");
        }
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(publicEndpoint, "publicEndpoint must not be null");
        requireHttpEndpoint(endpoint, "endpoint");
        requireHttpEndpoint(publicEndpoint, "publicEndpoint");
        if (!LOCAL_ENVIRONMENTS.contains(environment)
                && !"https".equalsIgnoreCase(publicEndpoint.getScheme())) {
            throw new IllegalArgumentException("non-local publicEndpoint must use HTTPS");
        }
        requireText(region, "region");
        requireText(accessKey, "accessKey");
        requireText(secretKey, "secretKey");
        requirePositive(uploadTtl, "uploadTtl");
        requirePositive(downloadTtl, "downloadTtl");
        if (downloadTtl.compareTo(MAX_DOWNLOAD_TTL) > 0) {
            throw new IllegalArgumentException("downloadTtl must not exceed 10 minutes");
        }
        reservationAllowedMimeTypes = reservationAllowedMimeTypes == null
                ? Set.of()
                : reservationAllowedMimeTypes.stream()
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public String quarantineBucket() {
        return environment + "-inspection-quarantine";
    }

    public String privateBucket() {
        return environment + "-inspection-private";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireHttpEndpoint(URI value, String name) {
        String scheme = value.getScheme();
        if (value.getHost() == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP(S) URI");
        }
    }
}
