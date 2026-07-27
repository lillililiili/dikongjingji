package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.OptionalInt;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.TokenVersionPort;

public final class PostgresTokenVersionAdapter implements TokenVersionPort {

    private final IdentityPersistenceReader reader;

    public PostgresTokenVersionAdapter(IdentityPersistenceReader reader) {
        this.reader = reader;
    }

    @Override
    public OptionalInt findCurrentTokenVersion(UUID subjectId) {
        return reader.findUserById(subjectId)
                .filter(PostgresIdentityAccountAdapter::hasActiveDefaultOrganization)
                .filter(user -> "ACTIVE".equals(user.userStatus()))
                .map(user -> OptionalInt.of(user.tokenVersion()))
                .orElseGet(OptionalInt::empty);
    }
}
