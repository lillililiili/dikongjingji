package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.UUID;

public record PersistedIdentityProfile(
        UUID subjectId,
        String displayName,
        String organizationName,
        String organizationType
) {
}
