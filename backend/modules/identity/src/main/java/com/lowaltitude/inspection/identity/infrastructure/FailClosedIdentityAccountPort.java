package com.lowaltitude.inspection.identity.infrastructure;

import java.util.Optional;

import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;

/**
 * Task 3 replaces this fallback with a PostgreSQL-backed identity account port.
 * Until then, login has no implicit account source and must fail closed.
 */
public final class FailClosedIdentityAccountPort implements IdentityAccountPort {

    @Override
    public Optional<IdentityAccount> findByLoginName(String loginName) {
        return Optional.empty();
    }
}
