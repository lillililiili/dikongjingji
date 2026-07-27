package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

public record PersistedRolePermission(
        String roleCode,
        String roleStatus,
        String scopeType,
        UUID organizationId,
        Instant validFrom,
        Instant validUntil,
        String permissionCode
) {
}
