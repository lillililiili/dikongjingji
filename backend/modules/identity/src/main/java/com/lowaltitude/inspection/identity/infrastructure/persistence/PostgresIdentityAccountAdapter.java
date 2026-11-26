package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
import com.lowaltitude.inspection.identity.domain.IdentityAccountStatus;

public final class PostgresIdentityAccountAdapter implements IdentityAccountPort {

    private static final String ACTIVE = "ACTIVE";
    private static final String GLOBAL = "GLOBAL";
    private static final String ORGANIZATION = "ORGANIZATION";

    private final IdentityPersistenceReader reader;

    public PostgresIdentityAccountAdapter(IdentityPersistenceReader reader) {
        this.reader = reader;
    }

    @Override
    public Optional<IdentityAccount> findByLoginName(String loginName) {
        return reader.findUserByLoginName(loginName)
                .filter(PostgresIdentityAccountAdapter::hasActiveDefaultOrganization)
                .flatMap(this::mapAccount);
    }

    @Override
    public Optional<IdentityAccount> findBySubjectId(java.util.UUID subjectId) {
        return reader.findUserById(subjectId)
                .filter(PostgresIdentityAccountAdapter::hasActiveDefaultOrganization)
                .flatMap(this::mapAccount);
    }

    private Optional<IdentityAccount> mapAccount(PersistedIdentityUser user) {
        IdentityAccountStatus status;
        try {
            status = IdentityAccountStatus.valueOf(user.userStatus());
        } catch (IllegalArgumentException | NullPointerException invalidStatus) {
            return Optional.empty();
        }

        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (PersistedRolePermission assignment : reader.findRolePermissions(user.subjectId())) {
            if (!isCurrentRoleForDefaultOrganization(assignment, user.defaultOrganizationId())) {
                continue;
            }
            roles.add(assignment.roleCode());
            if (assignment.permissionCode() != null) {
                permissions.add(assignment.permissionCode());
            }
        }
        return Optional.of(new IdentityAccount(
                user.subjectId(),
                user.loginName(),
                user.passwordHash(),
                status,
                roles,
                user.defaultOrganizationId(),
                permissions,
                user.tokenVersion()));
    }

    static boolean hasActiveDefaultOrganization(PersistedIdentityUser user) {
        return user.defaultOrganizationId() != null
                && ACTIVE.equals(user.organizationStatus())
                && ACTIVE.equals(user.memberStatus());
    }

    private static boolean isCurrentRoleForDefaultOrganization(
            PersistedRolePermission assignment,
            java.util.UUID defaultOrganizationId
    ) {
        // Match the clock and exact statement snapshot that PostgreSQL used to filter this row.
        Instant now = assignment.evaluatedAt();
        if (!ACTIVE.equals(assignment.roleStatus())
                || now == null
                || assignment.validFrom() == null
                || assignment.validFrom().isAfter(now)
                || (assignment.validUntil() != null && !assignment.validUntil().isAfter(now))) {
            return false;
        }
        if (GLOBAL.equals(assignment.scopeType())) {
            return assignment.organizationId() == null;
        }
        return ORGANIZATION.equals(assignment.scopeType())
                && defaultOrganizationId.equals(assignment.organizationId());
    }
}
