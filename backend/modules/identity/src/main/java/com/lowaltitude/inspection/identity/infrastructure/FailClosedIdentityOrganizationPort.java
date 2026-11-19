package com.lowaltitude.inspection.identity.infrastructure;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;

/** 没有数据库时：任何机构都当作不存在。 */
public final class FailClosedIdentityOrganizationPort implements IdentityOrganizationPort {

    @Override
    public Optional<IdentityOrganization> findActiveOrganization(UUID organizationId) {
        return Optional.empty();
    }
}
