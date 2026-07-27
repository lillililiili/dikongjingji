package com.lowaltitude.inspection.identity.domain;

import java.util.Set;
import java.util.UUID;

public record CurrentSubject(
        UUID subjectId,
        Set<String> roles,
        UUID organizationId,
        Set<String> permissions,
        int tokenVersion
) {
    public CurrentSubject {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }
}
