package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.UUID;

public record PersistedIdentityUser(
        UUID subjectId,
        String loginName,
        String passwordHash,
        String userStatus,
        int tokenVersion,
        UUID defaultOrganizationId,
        String organizationStatus,
        String memberStatus
) {
}
