package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;

public final class PostgresIdentityOrganizationAdapter implements IdentityOrganizationPort {

    /** 一次最多问这么多家；一页报价、订单远到不了这个数，超出的分批查。 */
    static final int BATCH = 100;

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
                        row.organizationType(),
                        row.qualificationReviewedAt() == null ? null : row.qualificationReviewedAt().toInstant(),
                        row.listingSuspended()));
    }

    @Override
    public Set<UUID> qualificationReviewed(Collection<UUID> organizationIds) {
        if (organizationIds == null || organizationIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = organizationIds.stream().filter(Objects::nonNull).distinct().toList();
        Set<UUID> reviewed = new HashSet<>();
        for (int from = 0; from < ids.size(); from += BATCH) {
            reviewed.addAll(reader.findQualificationReviewedOrganizationIds(
                    ids.subList(from, Math.min(ids.size(), from + BATCH))));
        }
        return Set.copyOf(reviewed);
    }
}
