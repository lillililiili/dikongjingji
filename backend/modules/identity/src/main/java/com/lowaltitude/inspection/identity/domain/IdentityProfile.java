package com.lowaltitude.inspection.identity.domain;

import java.util.UUID;

/**
 * What an account may show about itself. The organization fields are null for an account that
 * has no default organization.
 */
public record IdentityProfile(
        UUID subjectId,
        String displayName,
        String organizationName,
        String organizationType
) {
}
