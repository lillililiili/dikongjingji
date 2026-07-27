package com.lowaltitude.inspection.identity.infrastructure;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
import com.lowaltitude.inspection.identity.domain.TokenVersionPort;

/**
 * Task 3 replaces this with PostgreSQL-backed ports. Until then, absence of persisted identity
 * data rejects both login attempts and bearer tokens.
 */
public final class FailClosedIdentityAccessAdapter implements IdentityAccountPort, TokenVersionPort {

    @Override
    public Optional<IdentityAccount> findByLoginName(String loginName) {
        return Optional.empty();
    }

    @Override
    public OptionalInt findCurrentTokenVersion(UUID subjectId) {
        return OptionalInt.empty();
    }
}
