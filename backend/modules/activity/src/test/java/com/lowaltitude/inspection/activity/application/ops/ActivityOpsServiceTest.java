package com.lowaltitude.inspection.activity.application.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.ActivityDraft;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsActivityView;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.Operator;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.SessionDraft;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOpsMapper;
import com.lowaltitude.inspection.ops.common.OpsError;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.Test;

/** 运营端“活动与场次”的规则：草稿、上线前必填、下线、删除、有报名后的限制、场次增删改与停售、改动记录。 */
class ActivityOpsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T02:00:00Z");
    private static final OffsetDateTime AT = NOW.atOffset(ZoneOffset.UTC);
    private static final UUID ORGANIZER = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private static final UUID CO_ORGANIZER = UUID.fromString("a1000000-0000-4000-8000-000000000002");
    private static final UUID CUSTOMER_ORG = UUID.fromString("a1000000-0000-4000-8000-000000000003");
    private static final Operator OPERATOR = new Operator(UUID.fromString("90000000-0000-4000-8000-0000000000b1"),
            "平台运营（示例）");
    private static final String COVER = "https://cdn.example.com/cover.jpg";

    private final FakeMapper mapper = new FakeMapper();
    private boolean organizerSuspended;
    private final ActivityOpsService service = new ActivityOpsService(mapper,
            id -> Map.of(
                    ORGANIZER, new ActivityOpsPorts.Organization(ORGANIZER, "示例青少年科技机构", "PROVIDER",
                            organizerSuspended),
                    CO_ORGANIZER, new ActivityOpsPorts.Organization(CO_ORGANIZER, "东营城市运营中心", "PLATFORM"),
                    CUSTOMER_ORG, new ActivityOpsPorts.Organization(CUSTOMER_ORG, "示例企业客户", "CUSTOMER"))
                    .entrySet().stream().filter(entry -> entry.getKey().equals(id)).map(Map.Entry::getValue)
                    .findFirst(),
            code -> "370500".equals(code) ? Optional.of("东营市") : Optional.empty(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aNewActivityIsADraftWithItsGalleryAndARecord() {
        OpsActivityView created = service.create(draft(null, List.of("https://cdn.example.com/1.jpg",
                "https://cdn.example.com/2.jpg"), "PER_PERSON", 12000L), OPERATOR);

        assertThat(created.status()).isEqualTo(ActivityOpsViews.OpsActivityStatus.DRAFT);
        assertThat(created.galleryUrls()).containsExactly("https://cdn.example.com/1.jpg",
                "https://cdn.example.com/2.jpg");
        assertThat(created.cityName()).isEqualTo("东营市");
        assertThat(created.organizer().name()).isEqualTo("示例青少年科技机构");
        assertThat(created.coOrganizer().name()).isEqualTo("东营城市运营中心");
        assertThat(created.deletable()).isTrue();
        assertThat(created.publishMissing()).containsExactly("coverUrl", "enrollmentNotice", "refundNote", "sessions");
        assertThat(service.changes(created.activityId())).singleElement().satisfies(change -> {
            assertThat(change.action()).isEqualTo("CREATE");
            assertThat(change.subjectType()).isEqualTo("ACTIVITY");
            assertThat(change.summary()).contains("挑战赛");
            assertThat(change.operatorName()).isEqualTo("平台运营（示例）");
        });
    }

    @Test
    void badInputIsRejectedBeforeAnythingIsWritten() {
        for (ActivityDraft bad : List.of(
                draft("http://cdn.example.com/cover.jpg", List.of(), "FREE", null),
                draft(null, Collections.nCopies(1, "https://cdn.example.com/a.jpg").stream()
                        .flatMap(url -> java.util.stream.Stream.of(url, url)).toList(), "FREE", null),
                draft(null, java.util.stream.IntStream.range(0, 10)
                        .mapToObj(index -> "https://cdn.example.com/" + index + ".jpg").toList(), "FREE", null),
                draft(null, List.of(), "PER_PERSON", null),
                draft(null, List.of(), "PER_PERSON", 0L),
                draft(null, List.of(), "LOTTERY", null),
                withAges(10, 8))) {
            assertThatThrownBy(() -> service.create(bad, OPERATOR)).isInstanceOf(IllegalArgumentException.class);
        }
        assertRejected(ActivityOpsError.CITY_UNKNOWN, () -> service.create(withCity("110100"), OPERATOR));
        assertRejected(ActivityOpsError.ORGANIZER_UNKNOWN, () -> service.create(withOrganizer(CUSTOMER_ORG), OPERATOR));
        assertRejected(ActivityOpsError.ORGANIZER_UNKNOWN, () -> service.create(withOrganizer(UUID.randomUUID()),
                OPERATOR));
        assertThat(mapper.activities).isEmpty();
        assertThat(mapper.changes).isEmpty();
        // 免费活动的单价不存。
        assertThat(service.create(draft(null, List.of(), "FREE", 500L), OPERATOR).priceAmountMinor()).isNull();
    }

    @Test
    void publishingNeedsEveryRequiredFieldAndAnOpenSession() {
        UUID id = service.create(draft(null, List.of(), "PER_PERSON", 12000L), OPERATOR).activityId();

        OpsException incomplete = assertRejected(ActivityOpsError.PUBLISH_INCOMPLETE,
                () -> service.publish(id, 0, OPERATOR));
        assertThat(incomplete.details()).isEqualTo(Map.of("missing",
                List.of("coverUrl", "enrollmentNotice", "refundNote", "sessions")));

        OpsActivityView filled = service.update(id, complete(), 0, OPERATOR);
        assertThat(filled.publishMissing()).containsExactly("sessions");
        OpsActivityView withSession = service.addSession(id, session(AT.plusDays(10), AT.plusDays(9), 60), OPERATOR);
        assertThat(withSession.publishMissing()).isEmpty();
        assertThat(withSession.sessions()).singleElement().satisfies(session -> {
            assertThat(session.saleStatus()).isEqualTo(ActivityOpsViews.OpsSessionSaleStatus.ON_SALE);
            assertThat(session.hasEnrollments()).isFalse();
        });

        assertRejected(ActivityOpsError.VERSION_CONFLICT, () -> service.publish(id, 0, OPERATOR));
        // 身份 V013：主办机构资质没审核通过时不能上线。
        organizerSuspended = true;
        assertRejected(ActivityOpsError.ORGANIZER_NOT_QUALIFIED,
                () -> service.publish(id, withSession.version(), OPERATOR));
        organizerSuspended = false;
        OpsActivityView published = service.publish(id, withSession.version(), OPERATOR);
        assertThat(published.status()).isEqualTo(ActivityOpsViews.OpsActivityStatus.PUBLISHED);
        assertThat(published.firstPublishedAt()).isEqualTo(AT);
        assertThat(published.deletable()).isFalse();
        assertRejected(ActivityOpsError.STATUS_CONFLICT, () -> service.publish(id, published.version(), OPERATOR));

        // 已上线的活动不能把必填项改没了。
        ActivityDraft noCover = new ActivityDraft("CONTEST", "挑战赛", null, null, List.of(), ORGANIZER, null,
                "370500", "体验馆", "示例路 1 号", null, "PER_PERSON", 12000L, null, null, null, null, "开始前 48 小时可退",
                "到场出示凭证", null, null);
        OpsException stillIncomplete = assertRejected(ActivityOpsError.PUBLISH_INCOMPLETE,
                () -> service.update(id, noCover, published.version(), OPERATOR));
        assertThat(stillIncomplete.details()).isEqualTo(Map.of("missing", List.of("coverUrl")));

        OpsActivityView offline = service.offline(id, published.version(), OPERATOR);
        assertThat(offline.status()).isEqualTo(ActivityOpsViews.OpsActivityStatus.OFFLINE);
        assertRejected(ActivityOpsError.STATUS_CONFLICT, () -> service.offline(id, offline.version(), OPERATOR));
        // 上线过的不能删，只能下线；再上线照常。
        assertRejected(ActivityOpsError.STATUS_CONFLICT, () -> service.delete(id, offline.version(), OPERATOR));
        assertThat(service.publish(id, offline.version(), OPERATOR).firstPublishedAt()).isEqualTo(AT);
        assertThat(service.changes(id)).extracting(change -> change.action())
                .containsExactly("PUBLISH", "OFFLINE", "PUBLISH", "CREATE", "UPDATE", "CREATE")
                .hasSize(6);
    }

    @Test
    void onlyNeverPublishedDraftsAreDeleted() {
        UUID id = service.create(draft(null, List.of(), "FREE", null), OPERATOR).activityId();
        assertRejected(ActivityOpsError.VERSION_CONFLICT, () -> service.delete(id, 3, OPERATOR));
        service.delete(id, 0, OPERATOR);
        assertRejected(ActivityOpsError.NOT_FOUND, () -> service.activity(id));
        assertThat(mapper.activities.get(id).status).isEqualTo("DELETED");
        assertThat(mapper.changes).extracting(row -> row.action()).containsExactly("CREATE", "DELETE");
    }

    @Test
    void enrollmentsLockThePriceModeOrganizerAndCity() {
        UUID id = service.create(complete(), OPERATOR).activityId();
        mapper.activities.get(id).hasEnrollments = true;

        assertRejected(ActivityOpsError.LOCKED_BY_ENROLLMENTS, () -> service.update(id,
                new ActivityDraft("CONTEST", "挑战赛", null, COVER, List.of(), ORGANIZER, null, "370500", "体验馆",
                        "示例路 1 号", null, "FREE", null, null, null, null, null, null, "到场出示凭证", null, null), 0, OPERATOR));
        assertRejected(ActivityOpsError.LOCKED_BY_ENROLLMENTS, () -> service.update(id,
                new ActivityDraft("CONTEST", "挑战赛", null, COVER, List.of(), CO_ORGANIZER, null, "370500", "体验馆",
                        "示例路 1 号", null, "PER_PERSON", 12000L, null, null, null, null, "可退", "到场出示凭证", null, null),
                0, OPERATOR));
        // 单价能改。
        assertThat(service.update(id, new ActivityDraft("CONTEST", "挑战赛", null, COVER, List.of(), ORGANIZER, null,
                "370500", "体验馆", "示例路 1 号", null, "PER_PERSON", 15000L, null, null, null, null, "可退",
                "到场出示凭证", null, null), 0, OPERATOR).priceAmountMinor()).isEqualTo(15000L);
    }

    @Test
    void waitlistAndTeamSwitchesNeedOnlineEnrollmentAndStayWhenNotSent() {
        OpsActivityView created = service.create(withSwitches("PER_PERSON", 12000L, true, true), OPERATOR);
        assertThat(created.waitlistAllowed()).isTrue();
        assertThat(created.teamEnrollmentAllowed()).isTrue();
        assertThat(created.activeWaitlistCount()).isZero();
        assertThat(mapper.changes).last().satisfies(change ->
                assertThat(change.summary()).endsWith("；打开候补，打开团队报名"));
        // 不带开关：新建为关。
        OpsActivityView plain = service.create(complete(), OPERATOR);
        assertThat(plain.waitlistAllowed()).isFalse();
        assertThat(plain.teamEnrollmentAllowed()).isFalse();
        assertThat(mapper.changes).last().satisfies(change -> assertThat(change.summary()).isEqualTo("新建草稿“挑战赛”"));

        // 修改时不带开关保持原值，改动记录不提开关。
        UUID id = created.activityId();
        OpsActivityView kept = service.update(id, complete(), 0, OPERATOR);
        assertThat(kept.waitlistAllowed()).isTrue();
        assertThat(kept.teamEnrollmentAllowed()).isTrue();
        assertThat(mapper.changes).last().satisfies(change -> assertThat(change.summary()).isEqualTo("修改活动信息"));
        OpsActivityView onlyTeam = service.update(id, withSwitches("FREE", null, false, null), kept.version(),
                OPERATOR);
        assertThat(onlyTeam.waitlistAllowed()).isFalse();
        assertThat(onlyTeam.teamEnrollmentAllowed()).isTrue();
        assertThat(mapper.changes).last().satisfies(change ->
                assertThat(change.summary()).isEqualTo("修改活动信息；关闭候补"));

        // 询价活动不能在线报名：明确打开是参数错误，什么都不写；不带时跟着关掉。
        int before = mapper.changes.size();
        assertThatThrownBy(() -> service.create(withSwitches("INQUIRY", null, true, null), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(id, withSwitches("INQUIRY", null, null, true), onlyTeam.version(),
                OPERATOR)).isInstanceOf(IllegalArgumentException.class);
        assertThat(mapper.changes).hasSize(before);
        OpsActivityView inquiry = service.update(id, withSwitches("INQUIRY", null, null, null), onlyTeam.version(),
                OPERATOR);
        assertThat(inquiry.teamEnrollmentAllowed()).isFalse();
        assertThat(mapper.changes).last().satisfies(change ->
                assertThat(change.summary()).isEqualTo("修改活动信息；关闭团队报名（已有的团队报名照常）"));
    }

    @Test
    void switchesCanBeTurnedOffWithEnrollmentsAndAQueueThatKeepsGoing() {
        UUID id = service.create(withSwitches("PER_PERSON", 12000L, true, true), OPERATOR).activityId();
        FakeActivity stored = mapper.activities.get(id);
        stored.hasEnrollments = true;
        stored.activeWaitlists = 3;
        assertThat(service.activity(id).activeWaitlistCount()).isEqualTo(3);

        OpsActivityView closed = service.update(id, withSwitches("PER_PERSON", 12000L, false, false), 0, OPERATOR);

        assertThat(closed.waitlistAllowed()).isFalse();
        assertThat(closed.teamEnrollmentAllowed()).isFalse();
        // 排队中的候补不动（转正不看开关），页面照样看到条数。
        assertThat(closed.activeWaitlistCount()).isEqualTo(3);
        assertThat(service.changes(id)).first().satisfies(change -> assertThat(change.summary())
                .isEqualTo("修改活动信息；关闭候补（3 条排队中的照常转正），关闭团队报名（已有的团队报名照常）"));
        // 有报名后也能再打开。
        assertThat(service.update(id, withSwitches("PER_PERSON", 12000L, true, null), closed.version(), OPERATOR)
                .waitlistAllowed()).isTrue();
    }

    @Test
    void sessionsFollowTheEnrollmentRules() {
        UUID id = service.create(complete(), OPERATOR).activityId();
        assertThatThrownBy(() -> service.addSession(id, session(AT.minusHours(1), AT.minusHours(2), 10), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addSession(id, session(AT.plusDays(2), AT.plusDays(3), 10), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addSession(id, session(AT.plusDays(2), AT.plusDays(1), 0), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);

        UUID sessionId = service.addSession(id, session(AT.plusDays(10), AT.plusDays(9), 60), OPERATOR)
                .sessions().get(0).sessionId();
        FakeSession stored = mapper.sessions.get(sessionId);
        stored.enrolledCount = 48;
        stored.enrollmentCount = 3;

        assertRejected(ActivityOpsError.SESSION_HAS_ENROLLMENTS, () -> service.updateSession(id, sessionId,
                session(AT.plusDays(11), AT.plusDays(9), 60), stored.version, OPERATOR));
        assertRejected(ActivityOpsError.CAPACITY_BELOW_ENROLLED, () -> service.updateSession(id, sessionId,
                session(AT.plusDays(10), AT.plusDays(9), 40), stored.version, OPERATOR));
        assertRejected(ActivityOpsError.VERSION_CONFLICT, () -> service.updateSession(id, sessionId,
                session(AT.plusDays(10), AT.plusDays(9), 80), stored.version + 1, OPERATOR));
        // 已有报名时还能加名额、改报名截止和名称。
        var widened = service.updateSession(id, sessionId, new SessionDraft("上午场", AT.plusDays(10), AT.plusDays(10).plusHours(3), null,
                AT.plusDays(8), 80), stored.version, OPERATOR).sessions().get(0);
        assertThat(widened.capacity()).isEqualTo(80);
        assertThat(widened.label()).isEqualTo("上午场");

        assertRejected(ActivityOpsError.SESSION_HAS_ENROLLMENTS,
                () -> service.deleteSession(id, sessionId, widened.version(), OPERATOR));
        var stopped = service.stopSale(id, sessionId, widened.version(), OPERATOR).sessions().get(0);
        assertThat(stopped.saleStatus()).isEqualTo(ActivityOpsViews.OpsSessionSaleStatus.STOPPED);
        assertThat(service.activity(id).publishMissing()).containsExactly("sessions");
        assertRejected(ActivityOpsError.SALE_STATUS_UNCHANGED,
                () -> service.stopSale(id, sessionId, stopped.version(), OPERATOR));
        var resumed = service.resumeSale(id, sessionId, stopped.version(), OPERATOR).sessions().get(0);
        assertThat(resumed.saleStatus()).isEqualTo(ActivityOpsViews.OpsSessionSaleStatus.ON_SALE);

        // 开始了就不能再改、不能恢复售卖；停售照样可以。
        stored.startsAt = AT.minusMinutes(5);
        stored.enrollmentClosesAt = AT.minusHours(1);
        assertRejected(ActivityOpsError.SESSION_STARTED, () -> service.updateSession(id, sessionId,
                session(AT.plusDays(10), AT.plusDays(9), 80), stored.version, OPERATOR));
        assertThat(service.stopSale(id, sessionId, stored.version, OPERATOR).sessions().get(0).started()).isTrue();
        assertRejected(ActivityOpsError.SESSION_STARTED,
                () -> service.resumeSale(id, sessionId, stored.version, OPERATOR));

        // 没有报名单的场次能删。
        UUID empty = service.addSession(id, session(AT.plusDays(20), AT.plusDays(19), 30), OPERATOR).sessions()
                .stream().filter(view -> !view.sessionId().equals(sessionId)).findFirst().orElseThrow().sessionId();
        assertThat(service.deleteSession(id, empty, 0, OPERATOR).sessions()).hasSize(1);
        assertRejected(ActivityOpsError.NOT_FOUND, () -> service.deleteSession(id, empty, 0, OPERATOR));
        assertThat(mapper.changes).filteredOn(row -> row.subjectType().equals("SESSION"))
                .extracting(row -> row.action())
                .containsExactly("CREATE", "UPDATE", "STOP_SALE", "RESUME_SALE", "STOP_SALE", "CREATE", "DELETE");
        assertThat(mapper.changes).filteredOn(row -> row.action().equals("STOP_SALE")).first()
                .satisfies(row -> assertThat(row.summary()).contains("已报名 48 人不受影响"));
    }

    @Test
    void listFiltersArePassedAndNamesFilledIn() {
        service.create(complete(), OPERATOR);
        var page = service.activities(" 挑战%", "370500", "contest", "DRAFT", 1, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.cityName()).isEqualTo("东营市");
            assertThat(item.organizer().name()).isEqualTo("示例青少年科技机构");
        });
        assertThat(mapper.lastFilter).containsExactly("挑战", "370500", "CONTEST", "DRAFT");
        assertThatThrownBy(() -> service.activities(null, null, null, "DELETED", 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.activities(null, null, null, null, 1, 51))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 小工具 ----

    private static OpsException assertRejected(OpsError error, Runnable action) {
        try {
            action.run();
        } catch (OpsException rejected) {
            assertThat(rejected.error()).isEqualTo(error);
            return rejected;
        }
        throw new AssertionError("expected " + error);
    }

    private static ActivityDraft draft(String cover, List<String> gallery, String priceMode, Long amount) {
        return new ActivityDraft("CONTEST", "挑战赛", null, cover, gallery, ORGANIZER, CO_ORGANIZER, "370500", "体验馆",
                "示例路 1 号", null, priceMode, amount, 8, 15, null, null, null, null, null, null);
    }

    private static ActivityDraft complete() {
        return new ActivityDraft("CONTEST", "挑战赛", "图形化编程", COVER, List.of(), ORGANIZER, null, "370500",
                "体验馆", "示例路 1 号", null, "PER_PERSON", 12000L, 8, 15, null, null, "开始前 48 小时可退",
                "到场出示凭证\n提前 15 分钟签到", null, null);
    }

    private static ActivityDraft withSwitches(String priceMode, Long amount, Boolean waitlist, Boolean team) {
        return new ActivityDraft("CONTEST", "挑战赛", "图形化编程", COVER, List.of(), ORGANIZER, null, "370500",
                "体验馆", "示例路 1 号", null, priceMode, amount, 8, 15, null, null, "开始前 48 小时可退",
                "到场出示凭证\n提前 15 分钟签到", waitlist, team);
    }

    private static ActivityDraft withAges(int min, int max) {
        return new ActivityDraft("CONTEST", "挑战赛", null, null, List.of(), ORGANIZER, null, "370500", "体验馆",
                "示例路 1 号", null, "FREE", null, min, max, null, null, null, null, null, null);
    }

    private static ActivityDraft withCity(String city) {
        return new ActivityDraft("CONTEST", "挑战赛", null, null, List.of(), ORGANIZER, null, city, "体验馆",
                "示例路 1 号", null, "FREE", null, null, null, null, null, null, null, null, null);
    }

    private static ActivityDraft withOrganizer(UUID organizer) {
        return new ActivityDraft("CONTEST", "挑战赛", null, null, List.of(), organizer, null, "370500", "体验馆",
                "示例路 1 号", null, "FREE", null, null, null, null, null, null, null, null, null);
    }

    private static SessionDraft session(OffsetDateTime startsAt, OffsetDateTime closesAt, int capacity) {
        return new SessionDraft(null, startsAt, startsAt.plusHours(3), null, closesAt, capacity);
    }

    // ---- 内存里的 mapper ----

    static final class FakeActivity {
        UUID id;
        ActivityOpsMapper.ActivityValues values;
        String status = "DRAFT";
        OffsetDateTime firstPublishedAt;
        int version;
        boolean hasEnrollments;
        int activeWaitlists;
        OffsetDateTime updatedAt;
        List<String> gallery = new ArrayList<>();
    }

    static final class FakeSession {
        UUID id;
        UUID activityId;
        String label;
        OffsetDateTime startsAt;
        OffsetDateTime endsAt;
        OffsetDateTime checkInOpensAt;
        OffsetDateTime enrollmentClosesAt;
        int capacity;
        int enrolledCount;
        String saleStatus = "ON_SALE";
        int version;
        long enrollmentCount;
    }

    static final class FakeMapper implements ActivityOpsMapper {
        final Map<UUID, FakeActivity> activities = new LinkedHashMap<>();
        final Map<UUID, FakeSession> sessions = new LinkedHashMap<>();
        final List<ChangeRow> changes = new ArrayList<>();
        List<String> lastFilter;

        @Override
        public long count(String keyword, String cityCode, String type, String status) {
            lastFilter = java.util.Arrays.asList(keyword, cityCode, type, status);
            return activities.values().stream().filter(a -> !"DELETED".equals(a.status)).count();
        }

        @Override
        public List<SummaryRow> page(String keyword, String cityCode, String type, String status, OffsetDateTime now,
                                     int limit, int offset) {
            return activities.values().stream().filter(a -> !"DELETED".equals(a.status))
                    .map(a -> new SummaryRow(a.id, a.values.type(), a.values.title(), a.values.cityCode(),
                            a.values.organizerId(), a.status, a.values.priceMode(), a.values.priceAmountMinor(), 0,
                            null, 0, a.updatedAt, a.version))
                    .toList();
        }

        @Override
        public ActivityRow find(UUID activityId) {
            FakeActivity a = activities.get(activityId);
            if (a == null || "DELETED".equals(a.status)) {
                return null;
            }
            ActivityValues v = a.values;
            return new ActivityRow(a.id, v.type(), v.title(), v.subtitle(), v.coverUrl(), v.organizerId(),
                    v.coOrganizerId(), v.cityCode(), v.venueName(), v.venueAddress(), v.scheduleNote(), v.priceMode(),
                    v.priceAmountMinor(), v.minAge(), v.maxAge(), v.eligibilityNote(), v.seatNote(), v.refundNote(),
                    v.enrollmentNotice(), a.status, a.firstPublishedAt, a.version, a.updatedAt, a.updatedAt,
                    a.hasEnrollments, v.waitlistAllowed(), v.teamEnrollmentAllowed(), a.activeWaitlists);
        }

        @Override
        public List<String> gallery(UUID activityId) {
            return List.copyOf(activities.get(activityId).gallery);
        }

        @Override
        public List<SessionRow> sessions(UUID activityId) {
            return sessions.values().stream().filter(s -> s.activityId.equals(activityId)).map(FakeMapper::row)
                    .toList();
        }

        @Override
        public SessionRow lockSession(UUID activityId, UUID sessionId) {
            FakeSession s = sessions.get(sessionId);
            return s == null || !s.activityId.equals(activityId) ? null : row(s);
        }

        private static SessionRow row(FakeSession s) {
            return new SessionRow(s.id, s.activityId, s.label, s.startsAt, s.endsAt, s.checkInOpensAt,
                    s.enrollmentClosesAt, s.capacity, s.enrolledCount, "ACTIVE", s.saleStatus, s.version,
                    s.enrollmentCount);
        }

        @Override
        public int insert(UUID id, ActivityValues values, OffsetDateTime at) {
            FakeActivity a = new FakeActivity();
            a.id = id;
            a.values = values;
            a.updatedAt = at;
            activities.put(id, a);
            return 1;
        }

        @Override
        public int update(UUID id, ActivityValues values, int version, OffsetDateTime at) {
            FakeActivity a = activities.get(id);
            if (a == null || a.version != version || "DELETED".equals(a.status)) {
                return 0;
            }
            a.values = values;
            a.version++;
            a.updatedAt = at;
            return 1;
        }

        @Override
        public int changeStatus(UUID id, String status, int version, OffsetDateTime at) {
            FakeActivity a = activities.get(id);
            if (a == null || a.version != version || "DELETED".equals(a.status)) {
                return 0;
            }
            a.status = status;
            if ("PUBLISHED".equals(status) && a.firstPublishedAt == null) {
                a.firstPublishedAt = at;
            }
            a.version++;
            return 1;
        }

        @Override
        public int clearGallery(UUID activityId) {
            activities.get(activityId).gallery.clear();
            return 0;
        }

        @Override
        public int insertGalleryImage(UUID activityId, int sortOrder, String url, OffsetDateTime at) {
            activities.get(activityId).gallery.add(url);
            return 1;
        }

        @Override
        public int insertSession(UUID id, UUID activityId, SessionValues values, OffsetDateTime at) {
            FakeSession s = new FakeSession();
            s.id = id;
            s.activityId = activityId;
            apply(s, values);
            sessions.put(id, s);
            return 1;
        }

        private static void apply(FakeSession s, SessionValues values) {
            s.label = values.label();
            s.startsAt = values.startsAt();
            s.endsAt = values.endsAt();
            s.checkInOpensAt = values.checkInOpensAt();
            s.enrollmentClosesAt = values.enrollmentClosesAt();
            s.capacity = values.capacity();
        }

        @Override
        public int updateSession(UUID id, SessionValues values, int version, OffsetDateTime at) {
            FakeSession s = sessions.get(id);
            if (s.version != version || s.enrolledCount > values.capacity()) {
                return 0;
            }
            apply(s, values);
            s.version++;
            return 1;
        }

        @Override
        public int changeSaleStatus(UUID id, String saleStatus, int version, OffsetDateTime at) {
            FakeSession s = sessions.get(id);
            if (s.version != version) {
                return 0;
            }
            s.saleStatus = saleStatus;
            s.version++;
            return 1;
        }

        @Override
        public int deleteSession(UUID id, int version) {
            FakeSession s = sessions.get(id);
            if (s.version != version || s.enrollmentCount > 0) {
                return 0;
            }
            sessions.remove(id);
            return 1;
        }

        @Override
        public int touch(UUID id, OffsetDateTime at) {
            activities.get(id).updatedAt = at;
            return 1;
        }

        @Override
        public int insertChange(UUID activityId, String subjectType, UUID subjectId, String action, String summary,
                                UUID operatorId, String operatorName, OffsetDateTime at) {
            changes.add(new ChangeRow(UUID.randomUUID(), subjectType, subjectId, action, summary, operatorName, at));
            return 1;
        }

        @Override
        public List<ChangeRow> changes(UUID activityId, int limit) {
            List<ChangeRow> newestFirst = new ArrayList<>(changes);
            Collections.reverse(newestFirst);
            return newestFirst.stream().limit(limit).toList();
        }
    }
}
