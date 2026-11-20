package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PersistedIdentityOrganization(UUID organizationId, String organizationName, String organizationType,
                                            OffsetDateTime qualificationReviewedAt) {
}
