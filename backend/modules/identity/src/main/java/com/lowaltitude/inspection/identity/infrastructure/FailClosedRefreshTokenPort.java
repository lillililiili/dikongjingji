package com.lowaltitude.inspection.identity.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.RefreshTokenPort;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRecord;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason;

/** Without persistence no refresh token is ever found, so none can be exchanged. */
public final class FailClosedRefreshTokenPort implements RefreshTokenPort {

    @Override
    public void save(RefreshTokenRecord token) {
        // Nothing is kept: the token handed out cannot be exchanged later.
    }

    @Override
    public Optional<RefreshTokenRecord> findByHash(String tokenHash) {
        return Optional.empty();
    }

    @Override
    public boolean markRotated(UUID tokenId, UUID replacedBy, Instant at) {
        return false;
    }

    @Override
    public int revokeFamily(UUID familyId, RefreshTokenRevokeReason reason, Instant at) {
        return 0;
    }
}
