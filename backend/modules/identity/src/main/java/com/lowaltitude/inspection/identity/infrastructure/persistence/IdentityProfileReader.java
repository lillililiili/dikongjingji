package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

public interface IdentityProfileReader {

    Optional<PersistedIdentityProfile> findActiveProfileById(UUID subjectId);
}
