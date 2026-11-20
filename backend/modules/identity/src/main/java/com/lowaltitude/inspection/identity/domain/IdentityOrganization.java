package com.lowaltitude.inspection.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 一个在用的机构对外能说的：ID、名称、类型（CUSTOMER / PROVIDER / PLATFORM）和资质审核通过时间（R6-4，没审核时为空）。
 * 不含联系方式。
 */
public record IdentityOrganization(UUID organizationId, String name, String organizationType,
                                   Instant qualificationReviewedAt) {

    /** 平台已审核过这家机构的资质（页面上的“资质已审核”）。 */
    public boolean qualificationReviewed() {
        return qualificationReviewedAt != null;
    }
}
