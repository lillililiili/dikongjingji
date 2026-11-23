package com.lowaltitude.inspection.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Storage of refresh tokens (by hash). The rules (rotation, reuse detection, expiry) are the caller's. */
public interface RefreshTokenPort {

    /** Stores a new ACTIVE token. */
    void save(RefreshTokenRecord token);

    Optional<RefreshTokenRecord> findByHash(String tokenHash);

    /**
     * Marks the token ROTATED, replaced by {@code replacedBy}, only if it is still ACTIVE.
     * Returns false when another request exchanged or withdrew it first.
     */
    boolean markRotated(UUID tokenId, UUID replacedBy, Instant at);

    /** Withdraws every still-active token of the family; returns how many. */
    int revokeFamily(UUID familyId, RefreshTokenRevokeReason reason, Instant at);
}
