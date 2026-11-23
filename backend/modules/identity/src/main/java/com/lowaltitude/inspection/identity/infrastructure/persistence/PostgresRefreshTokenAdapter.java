package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.RefreshTokenPort;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRecord;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason;
import com.lowaltitude.inspection.identity.domain.RefreshTokenStatus;

public final class PostgresRefreshTokenAdapter implements RefreshTokenPort {

    private final IdentityRefreshTokenMapper mapper;

    public PostgresRefreshTokenAdapter(IdentityRefreshTokenMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(RefreshTokenRecord token) {
        if (token.status() != RefreshTokenStatus.ACTIVE) {
            throw new IllegalArgumentException("Only an active refresh token can be stored");
        }
        mapper.insertRefreshToken(
                token.tokenId(),
                token.familyId(),
                token.subjectId(),
                token.tokenHash(),
                token.tokenVersion(),
                utc(token.issuedAt()),
                utc(token.expiresAt()),
                utc(token.familyExpiresAt()));
    }

    @Override
    public Optional<RefreshTokenRecord> findByHash(String tokenHash) {
        return mapper.findRefreshTokenByHash(tokenHash).flatMap(PostgresRefreshTokenAdapter::toRecord);
    }

    @Override
    public boolean markRotated(UUID tokenId, UUID replacedBy, Instant at) {
        return mapper.markRefreshTokenRotated(tokenId, replacedBy, utc(at)) == 1;
    }

    @Override
    public int revokeFamily(UUID familyId, RefreshTokenRevokeReason reason, Instant at) {
        return mapper.revokeRefreshTokenFamily(familyId, reason.name(), utc(at));
    }

    static Optional<RefreshTokenRecord> toRecord(PersistedRefreshToken row) {
        RefreshTokenStatus status;
        try {
            status = RefreshTokenStatus.valueOf(row.status());
        } catch (IllegalArgumentException | NullPointerException unknownStatus) {
            return Optional.empty();
        }
        return Optional.of(new RefreshTokenRecord(
                row.id(),
                row.familyId(),
                row.userId(),
                row.tokenHash(),
                row.tokenVersion(),
                status,
                row.issuedAt().toInstant(),
                row.expiresAt().toInstant(),
                row.familyExpiresAt().toInstant()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
