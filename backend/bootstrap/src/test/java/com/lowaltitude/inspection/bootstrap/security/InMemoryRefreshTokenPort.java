package com.lowaltitude.inspection.bootstrap.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.lowaltitude.inspection.identity.domain.RefreshTokenPort;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRecord;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason;
import com.lowaltitude.inspection.identity.domain.RefreshTokenStatus;

/** Refresh token storage for tests: the same conditional updates the PostgreSQL adapter makes. */
public final class InMemoryRefreshTokenPort implements RefreshTokenPort {

    private final Map<UUID, RefreshTokenRecord> tokens = new ConcurrentHashMap<>();
    private final Map<UUID, RefreshTokenRevokeReason> revokeReasons = new ConcurrentHashMap<>();
    /** Makes the next markRotated lose, as if another request had exchanged the token first. */
    public volatile boolean loseNextRotation;

    @Override
    public void save(RefreshTokenRecord token) {
        tokens.put(token.tokenId(), token);
    }

    @Override
    public Optional<RefreshTokenRecord> findByHash(String tokenHash) {
        return tokens.values().stream().filter(token -> token.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public synchronized boolean markRotated(UUID tokenId, UUID replacedBy, Instant at) {
        RefreshTokenRecord token = tokens.get(tokenId);
        if (loseNextRotation) {
            loseNextRotation = false;
            if (token != null) tokens.put(tokenId, with(token, RefreshTokenStatus.ROTATED));
            return false;
        }
        if (token == null || token.status() != RefreshTokenStatus.ACTIVE) {
            return false;
        }
        tokens.put(tokenId, with(token, RefreshTokenStatus.ROTATED));
        return true;
    }

    @Override
    public synchronized int revokeFamily(UUID familyId, RefreshTokenRevokeReason reason, Instant at) {
        int revoked = 0;
        for (RefreshTokenRecord token : List.copyOf(tokens.values())) {
            if (token.familyId().equals(familyId) && token.status() == RefreshTokenStatus.ACTIVE) {
                tokens.put(token.tokenId(), with(token, RefreshTokenStatus.REVOKED));
                revokeReasons.put(token.tokenId(), reason);
                revoked++;
            }
        }
        return revoked;
    }

    public List<RefreshTokenRecord> all() {
        return new ArrayList<>(tokens.values());
    }

    public Optional<RefreshTokenRevokeReason> revokeReason(UUID tokenId) {
        return Optional.ofNullable(revokeReasons.get(tokenId));
    }

    public void clear() {
        tokens.clear();
        revokeReasons.clear();
        loseNextRotation = false;
    }

    private static RefreshTokenRecord with(RefreshTokenRecord token, RefreshTokenStatus status) {
        return new RefreshTokenRecord(token.tokenId(), token.familyId(), token.subjectId(), token.tokenHash(),
                token.tokenVersion(), status, token.issuedAt(), token.expiresAt(), token.familyExpiresAt());
    }
}
