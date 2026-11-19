package com.lowaltitude.inspection.identity.domain;

import java.util.UUID;

/** 一个在用的机构对外能说的：ID、名称、类型（CUSTOMER / PROVIDER / PLATFORM）。不含联系方式。 */
public record IdentityOrganization(UUID organizationId, String name, String organizationType) {
}
