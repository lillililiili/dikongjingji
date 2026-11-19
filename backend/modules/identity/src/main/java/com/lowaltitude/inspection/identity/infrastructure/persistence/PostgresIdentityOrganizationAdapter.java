package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;

public final class PostgresIdentityOrganizationAdapter implements IdentityOrganizationPort {

    private final IdentityOrganizationReader reader;

    public PostgresIdentityOrganizationAdapter(IdentityOrganizationReader reader) {
        this.reader = reader;
    }

    @Override
    public Optional<IdentityOrganization> findActiveOrganization(UUID organizationId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        return reader.findActiveOrganizationById(organizationId)
                .map(row -> new IdentityOrganization(row.organizationId(), row.organizationName(),
                        row.organizationType()));
    }
}
