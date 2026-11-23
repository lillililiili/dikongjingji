package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PersistedRefreshToken(
        UUID id,
        UUID familyId,
        UUID userId,
        String tokenHash,
        int tokenVersion,
        String status,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime familyExpiresAt
) {
}
