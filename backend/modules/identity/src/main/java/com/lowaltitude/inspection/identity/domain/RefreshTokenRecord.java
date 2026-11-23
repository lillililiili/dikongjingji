package com.lowaltitude.inspection.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One stored refresh token. Only the SHA-256 of the token is kept; the token itself goes to the
 * client once. All tokens handed out for one login share a family.
 */
public record RefreshTokenRecord(
        UUID tokenId,
        UUID familyId,
        UUID subjectId,
        String tokenHash,
        int tokenVersion,
        RefreshTokenStatus status,
        Instant issuedAt,
        Instant expiresAt,
        Instant familyExpiresAt
) {
    public RefreshTokenRecord {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(familyExpiresAt, "familyExpiresAt");
    }

    /** Usable at this instant: still active and neither it nor its login has run out. */
    public boolean usableAt(Instant now) {
        return status == RefreshTokenStatus.ACTIVE && expiresAt.isAfter(now) && familyExpiresAt.isAfter(now);
    }
}
