package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityOrganizationReader {

    Optional<PersistedIdentityOrganization> findActiveOrganizationById(UUID organizationId);

    /** 这些 ID 里在用并且有资质审核通过时间的机构。调用方保证列表不空、不超过上限。 */
    List<UUID> findQualificationReviewedOrganizationIds(List<UUID> organizationIds);
}
