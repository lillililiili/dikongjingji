package com.lowaltitude.inspection.identity.infrastructure;

import java.util.OptionalInt;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.TokenVersionPort;

/**
 * Task 3 replaces this fallback with a PostgreSQL-backed token version port.
 * Until then, bearer tokens cannot bypass persisted identity validation.
 */
public final class FailClosedTokenVersionPort implements TokenVersionPort {

    @Override
    public OptionalInt findCurrentTokenVersion(UUID subjectId) {
        return OptionalInt.empty();
    }
}
