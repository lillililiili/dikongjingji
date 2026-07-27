package com.lowaltitude.inspection.identity.domain;

import java.util.Set;
import java.util.UUID;

public record IdentityAccount(
        UUID subjectId,
        String loginName,
        String passwordHash,
        Set<String> roles,
        UUID organizationId,
        Set<String> permissions,
        int tokenVersion
) {
    public IdentityAccount {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }
}
