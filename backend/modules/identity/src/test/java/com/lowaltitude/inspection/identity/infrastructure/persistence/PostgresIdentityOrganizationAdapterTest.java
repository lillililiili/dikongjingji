package com.lowaltitude.inspection.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedIdentityOrganizationPort;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/** G1：按 ID 读在用机构的名称和类型；R6-4：资质审核通过时间和“哪些审核过”。 */
class PostgresIdentityOrganizationAdapterTest {

    private static final UUID ORGANIZATION = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("c1000000-0000-4000-8000-000000000005");
    private static final OffsetDateTime REVIEWED = OffsetDateTime.of(2026, 11, 1, 2, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void mapsTheStoredOrganization() {
        PostgresIdentityOrganizationAdapter adapter = new PostgresIdentityOrganizationAdapter(reader(
                id -> Optional.of(new PersistedIdentityOrganization(id, "示例机器人商家", "PROVIDER", REVIEWED, false)),
                ids -> List.of()));

        IdentityOrganization found = adapter.findActiveOrganization(ORGANIZATION).orElseThrow();
        assertThat(found).isEqualTo(new IdentityOrganization(ORGANIZATION, "示例机器人商家", "PROVIDER",
                Instant.parse("2026-11-01T02:00:00Z")));
        assertThat(found.qualificationReviewed()).isTrue();
        assertThat(found.listingSuspended()).isFalse();
    }

    @Test
    void anOrganizationWithoutAReviewTimeIsNotReviewed() {
        PostgresIdentityOrganizationAdapter adapter = new PostgresIdentityOrganizationAdapter(reader(
                id -> Optional.of(new PersistedIdentityOrganization(id, "示例行业装备代理", "PROVIDER", null, true)),
                ids -> List.of()));

        IdentityOrganization found = adapter.findActiveOrganization(OTHER).orElseThrow();
        assertThat(found.qualificationReviewedAt()).isNull();
        assertThat(found.listingSuspended()).isTrue();
        assertThat(found.qualificationReviewed()).isFalse();
    }

    @Test
    void anUnknownOrMissingOrganizationIsEmptyAndTheFallbackFindsNothing() {
        PostgresIdentityOrganizationAdapter adapter = new PostgresIdentityOrganizationAdapter(reader(
                id -> Optional.empty(), ids -> List.of()));

        assertThat(adapter.findActiveOrganization(ORGANIZATION)).isEmpty();
        assertThat(adapter.findActiveOrganization(null)).isEmpty();
        FailClosedIdentityOrganizationPort fallback = new FailClosedIdentityOrganizationPort();
        assertThat(fallback.findActiveOrganization(ORGANIZATION)).isEmpty();
        assertThat(fallback.qualificationReviewed(List.of(ORGANIZATION))).isEmpty();
    }

    // 只问去重后的非空 ID；空的不查库；结果只含读到的。
    @Test
    void asksOnlyForDistinctIdsAndReturnsWhatTheStoreFound() {
        List<List<UUID>> asked = new ArrayList<>();
        PostgresIdentityOrganizationAdapter adapter = new PostgresIdentityOrganizationAdapter(reader(
                id -> Optional.empty(),
                ids -> {
                    asked.add(List.copyOf(ids));
                    return ids.contains(ORGANIZATION) ? List.of(ORGANIZATION) : List.of();
                }));

        assertThat(adapter.qualificationReviewed(Arrays.asList(ORGANIZATION, null, OTHER, ORGANIZATION)))
                .isEqualTo(Set.of(ORGANIZATION));
        assertThat(asked).containsExactly(List.of(ORGANIZATION, OTHER));

        assertThat(adapter.qualificationReviewed(List.of())).isEmpty();
        assertThat(adapter.qualificationReviewed(null)).isEmpty();
        assertThat(asked).hasSize(1);
    }

    @Test
    void asksInBatchesSoTheInListStaysBounded() {
        List<Integer> sizes = new ArrayList<>();
        PostgresIdentityOrganizationAdapter adapter = new PostgresIdentityOrganizationAdapter(reader(
                id -> Optional.empty(),
                ids -> {
                    sizes.add(ids.size());
                    return List.of();
                }));
        List<UUID> many = IntStream.range(0, PostgresIdentityOrganizationAdapter.BATCH + 5)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        assertThat(adapter.qualificationReviewed(many)).isEmpty();
        assertThat(sizes).containsExactly(PostgresIdentityOrganizationAdapter.BATCH, 5);
    }

    // 只回答在用的机构，只读名称、类型和审核时间。
    @Test
    void theQueryReadsOnlyActiveOrganizationsAndNoOtherColumns() throws Exception {
        Method query = IdentityPersistenceMapper.class.getMethod("findActiveOrganizationById", UUID.class);
        String sql = String.join("\n", query.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("o.id AS organization_id")
                .contains("o.organization_name")
                .contains("o.organization_type")
                .contains("o.qualification_reviewed_at")
                .contains("o.listing_suspended")
                .contains("WHERE o.id = #{organizationId}")
                .contains("o.status = 'ACTIVE'")
                .doesNotContain("organization_code")
                .doesNotContain("identity_user")
                .doesNotContain("${");
    }

    @Test
    void theReviewedQueryOnlyCountsActiveOrganizationsWithAReviewTime() throws Exception {
        Method query = IdentityPersistenceMapper.class.getMethod("findQualificationReviewedOrganizationIds",
                List.class);
        String sql = String.join("\n", query.getAnnotation(Select.class).value());

        assertThat(sql.strip()).startsWith("<script>");
        assertThat(sql)
                .contains("SELECT o.id")
                .contains("<foreach collection=\"organizationIds\"")
                .contains("o.status = 'ACTIVE'")
                .contains("o.qualification_reviewed_at IS NOT NULL")
                .doesNotContain("identity_user")
                .doesNotContain("${");
    }

    private static IdentityOrganizationReader reader(
            Function<UUID, Optional<PersistedIdentityOrganization>> byId,
            Function<List<UUID>, List<UUID>> reviewed
    ) {
        return new IdentityOrganizationReader() {
            @Override
            public Optional<PersistedIdentityOrganization> findActiveOrganizationById(UUID organizationId) {
                return byId.apply(organizationId);
            }

            @Override
            public List<UUID> findQualificationReviewedOrganizationIds(List<UUID> organizationIds) {
                return reviewed.apply(organizationIds);
            }
        };
    }
}
