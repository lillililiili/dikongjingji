package com.lowaltitude.inspection.domain.files;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

public record PresignedObjectIntent(
        String method,
        URI url,
        Map<String, String> requiredHeaders,
        Instant expiresAt
) {

    public PresignedObjectIntent {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }
}
