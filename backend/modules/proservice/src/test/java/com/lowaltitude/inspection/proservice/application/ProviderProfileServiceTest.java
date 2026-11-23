package com.lowaltitude.inspection.proservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.proservice.application.ProServicePorts.Provider;
import com.lowaltitude.inspection.proservice.application.ProviderProfileService.ProfileDraft;
import com.lowaltitude.inspection.proservice.application.ProviderProfileService.ProfileError;
import com.lowaltitude.inspection.proservice.application.ProviderProfileService.ReviewStatus;
import com.lowaltitude.inspection.proservice.application.ProviderProfileService.ReviewView;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProviderProfileMapper;
import org.junit.jupiter.api.Test;

/** S4-4：服务商主页资料——工作台提交、运营审核、主页只显示审核通过的一份。 */
class ProviderProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final UUID EAGLE = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final UUID NOT_PROVIDER = UUID.fromString("f2000000-0000-4000-8000-0000000000ff");
    private static final UUID OPERATOR = UUID.fromString("22222222-2222-2222-2222-aaaaaaaaaaaa");
    private static final UUID REVIEWER = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final ProviderProfileService service = new ProviderProfileService(mapper,
            id -> EAGLE.equals(id) ? Optional.of(new Provider(EAGLE, "鹰眼智能巡检", "企业认证", null, true))
                    : Optional.empty(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aProviderSubmitsAndTheHomepageShowsOnlyTheApprovedCopy() {
        ReviewView none = service.mine(EAGLE);
        assertThat(none.status()).isEqualTo(ReviewStatus.NONE);
        assertThat(none.version()).isZero();

        ReviewView pending = service.submit(EAGLE, OPERATOR, "王工", draft("示例鹰眼智能科技有限公司", 0));
        assertThat(pending.status()).isEqualTo(ReviewStatus.PENDING);
        assertThat(pending.submitted().deliverables()).containsExactly("巡检报告", "隐患清单");
        assertThat(pending.published()).isNull();
        assertThat(service.published(EAGLE)).isEmpty();
        // 同样的内容再交一次没有变化。
        assertThat(service.submit(EAGLE, OPERATOR, "王工", draft("示例鹰眼智能科技有限公司", pending.version())).version())
                .isEqualTo(pending.version());

        var approved = service.review(EAGLE, "APPROVE", null, pending.version(), REVIEWER, "运营");
        assertThat(approved.status()).isEqualTo(ProviderProfileService.OpsStatus.APPROVED);
        assertThat(service.published(EAGLE)).get().satisfies(profile -> {
            assertThat(profile.legalEntity()).isEqualTo("示例鹰眼智能科技有限公司");
            assertThat(profile.authorizationUntil()).isEqualTo(LocalDate.parse("2027-10-31"));
            assertThat(profile.localTeamLabel()).isEqualTo("东营本地团队");
        });

        // 再提交一份被驳回：主页仍是上一份通过的。
        ReviewView again = service.submit(EAGLE, OPERATOR, "王工", draft("新名称有限公司", approved.version()));
        var rejected = service.review(EAGLE, "REJECT", "营业执照名称对不上", again.version(), REVIEWER, "运营");
        assertThat(rejected.rejectReason()).isEqualTo("营业执照名称对不上");
        assertThat(service.mine(EAGLE).status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(service.published(EAGLE)).get().extracting(ProviderProfileService.ProfileView::legalEntity)
                .isEqualTo("示例鹰眼智能科技有限公司");
        assertThat(service.detail(EAGLE).changes()).extracting(change -> change.action())
                .containsExactly("REJECT", "SUBMIT", "APPROVE", "SUBMIT");
        assertThat(service.list("REJECTED", 1, 20).items()).singleElement()
                .satisfies(row -> assertThat(row.providerName()).isEqualTo("鹰眼智能巡检"));
    }

    @Test
    void submissionsAndReviewsAreGuarded() {
        assertRejected(ProfileError.NOT_PROVIDER, () -> service.mine(NOT_PROVIDER));
        assertRejected(ProfileError.NOT_PROVIDER, () -> service.submit(NOT_PROVIDER, OPERATOR, "王工", draft("甲", 0)));
        assertRejected(ProfileError.SUBMIT_VERSION_CONFLICT, () -> service.submit(EAGLE, OPERATOR, "王工", draft("甲", 3)));
        assertInvalid(() -> service.submit(EAGLE, OPERATOR, "王工",
                new ProfileDraft("甲", "无人机", "2026-11-01", List.of(), null, 0)));
        assertInvalid(() -> service.submit(EAGLE, OPERATOR, "王工",
                new ProfileDraft("甲", "无人机", null, List.of("报告", "报告"), null, 0)));
        assertInvalid(() -> service.submit(EAGLE, OPERATOR, "王工",
                new ProfileDraft("甲", "无人机", null, List.of("报".repeat(13)), null, 0)));
        assertInvalid(() -> service.submit(EAGLE, OPERATOR, "王工",
                new ProfileDraft("甲".repeat(61), "无人机", null, List.of(), null, 0)));
        ReviewView pending = service.submit(EAGLE, OPERATOR, "王工", draft("甲", 0));
        assertRejected(ProfileError.REVIEW_VERSION_CONFLICT,
                () -> service.review(EAGLE, "APPROVE", null, pending.version() + 1, REVIEWER, "运营"));
        assertInvalid(() -> service.review(EAGLE, "REJECT", " ", pending.version(), REVIEWER, "运营"));
        assertInvalid(() -> service.review(EAGLE, "MAYBE", null, pending.version(), REVIEWER, "运营"));
        var approved = service.review(EAGLE, "APPROVE", null, pending.version(), REVIEWER, "运营");
        assertRejected(ProfileError.NOTHING_TO_REVIEW,
                () -> service.review(EAGLE, "APPROVE", null, approved.version(), REVIEWER, "运营"));
        assertRejected(ProfileError.NOT_FOUND, () -> service.detail(NOT_PROVIDER));
    }

    private static ProfileDraft draft(String legalEntity, int version) {
        return new ProfileDraft(" " + legalEntity + " ", "无人机巡检 · 机器狗夜巡", "2027-10-31", List.of("巡检报告", "隐患清单"),
                "东营本地团队", version);
    }

    private static void assertRejected(ProfileError error, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(OpsException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class InMemoryMapper implements ProviderProfileMapper {

        final Map<UUID, ProfileRow> rows = new LinkedHashMap<>();
        final List<ChangeRow> changes = new ArrayList<>();

        @Override
        public ProfileRow find(UUID providerId) {
            return rows.get(providerId);
        }

        @Override
        public ProfileRow lock(UUID providerId) {
            return rows.get(providerId);
        }

        @Override
        public int insert(UUID providerId, String legalEntity, String serviceModes, LocalDate authorizationUntil,
                          String deliverables, String localTeamLabel, UUID submittedBy, OffsetDateTime at) {
            if (rows.containsKey(providerId)) {
                return 0;
            }
            rows.put(providerId, new ProfileRow(providerId, "PENDING", legalEntity, serviceModes, authorizationUntil,
                    deliverables, localTeamLabel, at, null, null, null, null, null, null, null, 0));
            return 1;
        }

        @Override
        public int resubmit(UUID providerId, String legalEntity, String serviceModes, LocalDate authorizationUntil,
                            String deliverables, String localTeamLabel, UUID submittedBy, int version,
                            OffsetDateTime at) {
            ProfileRow row = rows.get(providerId);
            if (row == null || row.version() != version) {
                return 0;
            }
            rows.put(providerId, new ProfileRow(providerId, "PENDING", legalEntity, serviceModes, authorizationUntil,
                    deliverables, localTeamLabel, at, row.publishedLegalEntity(), row.publishedServiceModes(),
                    row.publishedAuthorizationUntil(), row.publishedDeliverables(), row.publishedLocalTeamLabel(),
                    null, row.reviewedAt(), version + 1));
            return 1;
        }

        @Override
        public int approve(UUID providerId, UUID reviewedBy, int version, OffsetDateTime at) {
            ProfileRow row = rows.get(providerId);
            if (row == null || row.version() != version || !row.status().equals("PENDING")) {
                return 0;
            }
            rows.put(providerId, new ProfileRow(providerId, "APPROVED", row.submittedLegalEntity(),
                    row.submittedServiceModes(), row.submittedAuthorizationUntil(), row.submittedDeliverables(),
                    row.submittedLocalTeamLabel(), row.submittedAt(), row.submittedLegalEntity(),
                    row.submittedServiceModes(), row.submittedAuthorizationUntil(), row.submittedDeliverables(),
                    row.submittedLocalTeamLabel(), null, at, version + 1));
            return 1;
        }

        @Override
        public int reject(UUID providerId, String reason, UUID reviewedBy, int version, OffsetDateTime at) {
            ProfileRow row = rows.get(providerId);
            if (row == null || row.version() != version || !row.status().equals("PENDING")) {
                return 0;
            }
            rows.put(providerId, new ProfileRow(providerId, "REJECTED", row.submittedLegalEntity(),
                    row.submittedServiceModes(), row.submittedAuthorizationUntil(), row.submittedDeliverables(),
                    row.submittedLocalTeamLabel(), row.submittedAt(), row.publishedLegalEntity(),
                    row.publishedServiceModes(), row.publishedAuthorizationUntil(), row.publishedDeliverables(),
                    row.publishedLocalTeamLabel(), reason, at, version + 1));
            return 1;
        }

        @Override
        public long count(String status) {
            return rows.values().stream().filter(row -> status == null || row.status().equals(status)).count();
        }

        @Override
        public List<ProfileRow> findPage(String status, int limit, int offset) {
            return rows.values().stream().filter(row -> status == null || row.status().equals(status))
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public int insertChange(UUID providerId, String action, String summary, UUID operatorId, String operatorName,
                                OffsetDateTime at) {
            changes.add(new ChangeRow(UUID.randomUUID(), providerId, action, summary, operatorName,
                    at.plusNanos(changes.size())));
            return 1;
        }

        @Override
        public List<ChangeRow> findChanges(UUID providerId, int limit) {
            return changes.stream().filter(change -> change.providerId().equals(providerId))
                    .sorted(Comparator.comparing(ChangeRow::changedAt).reversed()).limit(limit).toList();
        }
    }
}
