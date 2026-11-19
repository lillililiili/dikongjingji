package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.UUID;

public record PersistedIdentityOrganization(UUID organizationId, String organizationName, String organizationType) {
}
