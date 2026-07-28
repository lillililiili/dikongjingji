package com.lowaltitude.inspection.bootstrap.security;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("security.cors")
public record ApiCorsProperties(List<String> allowedOrigins) {

    public ApiCorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .map(ApiCorsProperties::validatedOrigin)
                        .distinct()
                        .toList();
    }

    private static String validatedOrigin(String value) {
        URI origin = URI.create(value);
        String scheme = origin.getScheme();
        if (origin.getHost() == null
                || origin.getUserInfo() != null
                || origin.getPath() == null
                || !origin.getPath().isEmpty()
                || origin.getQuery() != null
                || origin.getFragment() != null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("CORS origin must be an absolute HTTP(S) origin");
        }
        return value;
    }
}
