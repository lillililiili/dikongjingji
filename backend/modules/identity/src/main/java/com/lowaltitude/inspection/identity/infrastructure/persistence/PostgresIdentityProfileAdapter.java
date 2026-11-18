package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;

public final class PostgresIdentityProfileAdapter implements IdentityProfilePort {

    private final IdentityProfileReader reader;

    public PostgresIdentityProfileAdapter(IdentityProfileReader reader) {
        this.reader = reader;
    }

    @Override
    public Optional<IdentityProfile> findProfile(UUID subjectId) {
        if (subjectId == null) {
            return Optional.empty();
        }
        return reader.findActiveProfileById(subjectId)
                .map(row -> new IdentityProfile(
                        row.subjectId(),
                        row.displayName(),
                        blankToNull(row.organizationName()),
                        blankToNull(row.organizationType())));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
