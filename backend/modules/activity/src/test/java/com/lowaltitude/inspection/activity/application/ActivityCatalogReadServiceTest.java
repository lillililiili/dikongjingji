package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ActivityCatalogReadServiceTest {

    /** 北京时间 2026-11-16 12:00。 */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-16T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T04:00:00Z"), ZoneOffset.UTC);
    private static final UUID ACTIVITY_ID = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    private static final UUID ORGANIZER = UUID.fromString("a1000000-0000-4000-8000-000000000001");

    private final FakeStore store = new FakeStore();
    private final ActivityCatalogReadService service = new ActivityCatalogReadService(store, CLOCK);

    @Test
    void pagesThroughTheStoreWithOneClockReadingForCountAndList() {
        store.total = 5;
        store.cards = List.of(card(true, false, true, 60, 48));

        ActivityPageView page = service.search("370500", " CONTEST ", null, true, true, 2, 2);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.hasMore()).isTrue();
        assertThat(store.limit).isEqualTo(2);
        assertThat(store.offset).isEqualTo(2);
        assertThat(store.queries).hasSize(2);
        assertThat(store.queries.get(0)).isEqualTo(store.queries.get(1));
        ActivityListQuery query = store.queries.get(0);
        assertThat(query.type()).isEqualTo("CONTEST");
        assertThat(query.freeOnly()).isTrue();
        assertThat(query.enrollingOnly()).isTrue();
        assertThat(query.filterByDate()).isFalse();
        assertThat(query.now()).isEqualTo(NOW);
        assertThat(query.dateStart()).isNotNull();
        assertThat(query.dateEnd()).isNotNull();
    }

    @Test
    void lastPageReportsNoMore() {
        store.total = 4;
        assertThat(service.search("370500", null, null, false, false, 2, 2).hasMore()).isFalse();
        assertThat(store.queries.get(0).type()).isEqualTo("ALL");
    }

    @Test
    void aDateFiltersByTheBeijingCalendarDay() {
        service.search("370500", null, "2026-11-28", false, false, 1, 20);

        ActivityListQuery query = store.queries.get(0);
        assertThat(query.filterByDate()).isTrue();
        // 北京时间 11-28 00:00 到 11-29 00:00，也就是 UTC 11-27 16:00 起的 24 小时。
        assertThat(query.dateStart().toInstant()).isEqualTo(Instant.parse("2026-11-27T16:00:00Z"));
        assertThat(query.dateEnd().toInstant()).isEqualTo(Instant.parse("2026-11-28T16:00:00Z"));
    }

    @Test
    void rejectsInvalidListArguments() {
        assertInvalid(() -> service.search(null, null, null, false, false, 1, 20));
        assertInvalid(() -> service.search("37 0500", null, null, false, false, 1, 20));
        assertInvalid(() -> service.search("370500", "PARTY", null, false, false, 1, 20));
        assertInvalid(() -> service.search("370500", null, "2026-13-40", false, false, 1, 20));
        assertInvalid(() -> service.search("370500", null, null, false, false, 0, 20));
        assertInvalid(() -> service.search("370500", null, null, false, false, 1, 0));
        assertInvalid(() -> service.search("370500", null, null, false, false, 1, 51));
        assertThat(store.queries).isEmpty();
    }

    @Test
    void cardStatusFollowsTheSessionsStillAhead() {
        store.cards = List.of(
                card(true, true, true, 60, 48),
                card(false, true, true, 60, 60),
                card(false, true, false, 60, 60),
                card(false, false, true, 60, 10));

        List<ActivityCardView> items =
                service.search("370500", null, null, false, false, 1, 20).items();

        assertThat(items).extracting(ActivityCardView::enrollmentStatus).containsExactly(
                ActivityEnrollmentStatus.OPEN,
                ActivityEnrollmentStatus.FULL_WAITLIST,
                ActivityEnrollmentStatus.FULL,
                ActivityEnrollmentStatus.CLOSED);
        assertThat(items.get(0).nextSession().seatsLeft()).isEqualTo(12);
        assertThat(items.get(1).nextSession().seatsLeft()).isZero();
    }

    @Test
    void cardHidesAmountsThatDoNotBelongToThePriceModeAndUnsafeImages() {
        store.cards = List.of(new StoredActivityCard(
                ACTIVITY_ID, "OPEN_DAY", "无人机开放体验日", "http://insecure.example/cover.jpg",
                "东营低空与机器人体验馆", "  ", "FREE", 9900L, "CNY", null, null, true,
                UUID.randomUUID(), NOW.plusDays(1), 80, 55, true, false, ORGANIZER));

        ActivityCardView card = service.search("370500", null, null, false, false, 1, 20).items().get(0);

        assertThat(card.price().mode()).isEqualTo(ActivityPriceMode.FREE);
        assertThat(card.price().amountMinor()).isNull();
        assertThat(card.coverImage()).isNull();
        assertThat(card.scheduleNote()).isNull();
        assertThat(card.ageRange().minAge()).isNull();
    }

    // E09：price.onlinePayment 只在按人收费、单价大于 0、主办机构能在线收款时为 true；
    // 免费、询价、单价为 0 的不问交易模块；一页里同一家主办机构只问一次。
    @Test
    void onlinePaymentNeedsAPositivePerPersonPriceAndAnOrganizerThatCanCollect() {
        UUID unregistered = UUID.fromString("a1000000-0000-4000-8000-000000000003");
        List<UUID> asked = new ArrayList<>();
        ActivityCatalogReadService paying = new ActivityCatalogReadService(store, CLOCK, organizer -> {
            asked.add(organizer);
            return ORGANIZER.equals(organizer);
        });
        store.cards = List.of(
                priced("PER_PERSON", 12000L, ORGANIZER),
                priced("PER_PERSON", 16800L, ORGANIZER),
                priced("PER_PERSON", 9900L, unregistered),
                priced("PER_PERSON", 0L, ORGANIZER),
                priced("FREE", null, ORGANIZER),
                priced("INQUIRY", null, ORGANIZER));

        List<ActivityCardView> cards = paying.search("370500", null, null, false, false, 1, 20).items();

        assertThat(cards).extracting(card -> card.price().onlinePayment())
                .containsExactly(true, true, false, false, false, false);
        assertThat(asked).containsExactly(ORGANIZER, unregistered);
        // 详情也一样；没接交易模块的服务一律 false。
        store.detail = Optional.of(detail(true));
        assertThat(paying.findDetail(ACTIVITY_ID, "370500").price())
                .isEqualTo(new ActivityPriceView(ActivityPriceMode.PER_PERSON, 12000L, "CNY", true));
        assertThat(service.findDetail(ACTIVITY_ID, "370500").price().onlinePayment()).isFalse();
    }

    private static StoredActivityCard priced(String mode, Long amountMinor, UUID organizer) {
        return new StoredActivityCard(
                UUID.randomUUID(), "CONTEST", "挑战赛", null, "体验馆", null, mode, amountMinor, "CNY", 8, 15, false,
                UUID.randomUUID(), NOW.plusDays(12), 60, 48, true, false, organizer);
    }

    @Test
    void httpsCoverIsReturnedWithTheTitleAsAlternativeText() {
        store.cards = List.of(new StoredActivityCard(
                ACTIVITY_ID, "CONTEST", "挑战赛", "https://media.example.com/a.jpg",
                "体验馆", null, "PER_PERSON", 12000L, "CNY", 8, 15, true,
                UUID.randomUUID(), NOW.plusDays(12), 60, 48, true, false, ORGANIZER));

        ActivityCardView card = service.search("370500", null, null, false, false, 1, 20).items().get(0);

        assertThat(card.coverImage()).isEqualTo(new ActivityMediaView("https://media.example.com/a.jpg", "挑战赛"));
        assertThat(card.price().amountMinor()).isEqualTo(12000L);
        assertThat(card.ageRange()).isEqualTo(new ActivityAgeRangeView(8, 15));
    }

    @Test
    void sessionDatesCoverTheWholeLastDayAndRejectLongOrReversedRanges() {
        store.dates = List.of(LocalDate.parse("2026-11-17"));

        ActivitySessionDatesView view = service.findSessionDates("370500", null, "2026-11-16", "2026-11-30");

        assertThat(view.dates()).containsExactly(LocalDate.parse("2026-11-17"));
        assertThat(store.zoneId).isEqualTo("Asia/Shanghai");
        assertThat(store.rangeStart.toInstant()).isEqualTo(Instant.parse("2026-11-15T16:00:00Z"));
        assertThat(store.rangeEnd.toInstant()).isEqualTo(Instant.parse("2026-11-30T16:00:00Z"));

        assertInvalid(() -> service.findSessionDates("370500", null, "2026-11-30", "2026-11-16"));
        assertInvalid(() -> service.findSessionDates("370500", null, "2026-11-01", "2027-03-01"));
        assertInvalid(() -> service.findSessionDates("370500", null, null, "2026-11-16"));
        assertInvalid(() -> service.findSessionDates("x", null, "2026-11-16", "2026-11-17"));
    }

    @Test
    void detailComputesEachSessionAndTakesTheBestStatusForTheActivity() {
        store.detail = Optional.of(detail(true));
        store.sessions = List.of(
                session(NOW.minusDays(3), NOW.minusDays(3).plusHours(2), NOW.minusDays(4), 80, 80),
                session(NOW.plusHours(20), null, NOW.minusHours(1), 80, 55),
                session(NOW.plusDays(12), null, NOW.plusDays(11), 60, 60),
                session(NOW.plusDays(19), null, NOW.plusDays(18), 60, 30));

        ActivityDetailView view = service.findDetail(ACTIVITY_ID, "370500");

        assertThat(view.sessions()).extracting(ActivitySessionView::status).containsExactly(
                ActivityEnrollmentStatus.ENDED,
                ActivityEnrollmentStatus.CLOSED,
                ActivityEnrollmentStatus.FULL_WAITLIST,
                ActivityEnrollmentStatus.OPEN);
        assertThat(view.sessions()).extracting(ActivitySessionView::seatsLeft)
                .containsExactly(0, 25, 0, 30);
        assertThat(view.enrollmentStatus()).isEqualTo(ActivityEnrollmentStatus.OPEN);
        assertThat(view.organizer().name()).isEqualTo("示例青少年科技机构");
        assertThat(view.coOrganizer()).isNull();
        assertThat(view.location().latitude()).isEqualTo(37.4487);
        assertThat(view.refundNote()).isNull();
    }

    @Test
    void detailWithoutWaitlistShowsFullAndWithoutSessionsShowsEnded() {
        store.detail = Optional.of(detail(false));
        store.sessions = List.of(session(NOW.plusDays(2), null, NOW.plusDays(1), 10, 10));
        assertThat(service.findDetail(ACTIVITY_ID, "370500").enrollmentStatus())
                .isEqualTo(ActivityEnrollmentStatus.FULL);

        store.sessions = List.of();
        assertThat(service.findDetail(ACTIVITY_ID, "370500").enrollmentStatus())
                .isEqualTo(ActivityEnrollmentStatus.ENDED);
    }

    // S3-1：运营停售的场次读成报名截止，活动取剩下能报的；图集只给 https，报名须知原样给出。
    @Test
    void stoppedSessionsReadAsClosedAndTheDetailCarriesGalleryAndNotice() {
        store.detail = Optional.of(detail(false));
        StoredActivitySession open = session(NOW.plusDays(2), null, NOW.plusDays(1), 10, 3);
        StoredActivitySession stopped = new StoredActivitySession(UUID.randomUUID(), "下午", NOW.plusDays(3), null,
                null, NOW.plusDays(2), 10, 3, true);
        store.sessions = List.of(stopped);
        store.gallery = List.of("https://cdn.example.com/a.jpg", "http://cdn.example.com/b.jpg",
                "https://cdn.example.com/c.jpg");

        ActivityDetailView view = service.findDetail(ACTIVITY_ID, "370500");
        assertThat(view.sessions()).extracting(ActivitySessionView::status)
                .containsExactly(ActivityEnrollmentStatus.CLOSED);
        assertThat(view.sessions().get(0).saleStopped()).isTrue();
        assertThat(view.enrollmentStatus()).isEqualTo(ActivityEnrollmentStatus.CLOSED);
        assertThat(view.gallery()).extracting(ActivityMediaView::url)
                .containsExactly("https://cdn.example.com/a.jpg", "https://cdn.example.com/c.jpg");
        assertThat(view.enrollmentNotice()).isEqualTo("到场请出示报名凭证\n请提前 15 分钟签到");

        store.sessions = List.of(open, stopped);
        assertThat(service.findDetail(ACTIVITY_ID, "370500").enrollmentStatus())
                .isEqualTo(ActivityEnrollmentStatus.OPEN);
        // 停售但已经结束的仍是已结束。
        assertThat(ActivityEnrollmentStatus.ofSession(NOW, NOW.minusDays(1), null, NOW.minusDays(2), 10, 0, false,
                true)).isEqualTo(ActivityEnrollmentStatus.ENDED);
    }

    @Test
    void aSessionThatHasStartedButNotFinishedIsClosedNotEnded() {
        assertThat(ActivityEnrollmentStatus.ofSession(
                NOW, NOW.minusHours(1), NOW.plusHours(2), NOW.minusDays(1), 60, 10, true))
                .isEqualTo(ActivityEnrollmentStatus.CLOSED);
        assertThat(ActivityEnrollmentStatus.ofSession(
                NOW, NOW.minusHours(1), null, NOW.minusDays(1), 60, 10, true))
                .isEqualTo(ActivityEnrollmentStatus.ENDED);
        // 截止时刻本身已不能报名。
        assertThat(ActivityEnrollmentStatus.ofSession(
                NOW, NOW.plusDays(1), null, NOW, 60, 10, true))
                .isEqualTo(ActivityEnrollmentStatus.CLOSED);
    }

    @Test
    void detailIsNotFoundWhenTheStoreHasNoPublishedRowAndInvalidWhenArgumentsAreBad() {
        store.detail = Optional.empty();

        assertThatThrownBy(() -> service.findDetail(ACTIVITY_ID, "370500"))
                .isInstanceOf(ActivityNotFoundException.class);
        assertInvalid(() -> service.findDetail(null, "370500"));
        assertInvalid(() -> service.findDetail(ACTIVITY_ID, null));
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
    }

    private static StoredActivityCard card(
            boolean hasOpen, boolean hasFull, boolean waitlist, int capacity, int enrolled
    ) {
        return new StoredActivityCard(
                UUID.randomUUID(), "CONTEST", "挑战赛", null, "体验馆", null,
                "PER_PERSON", 12000L, "CNY", 8, 15, waitlist,
                UUID.randomUUID(), NOW.plusDays(12), capacity, enrolled, hasOpen, hasFull, ORGANIZER);
    }

    private static StoredActivityDetail detail(boolean waitlistAllowed) {
        return new StoredActivityDetail(
                ACTIVITY_ID, "CONTEST", "青少年无人机编程挑战赛（东营站）", "图形化编程", null,
                "PER_PERSON", 12000L, "CNY", 8, 15,
                UUID.fromString("a1000000-0000-4000-8000-000000000001"), "示例青少年科技机构",
                null, null,
                "东营低空与机器人体验馆", "东营区示例路 66 号", 37.4487, 118.5823,
                null, "8–15 岁，由监护人代报名并到场", "陪同家长不用报名", " ",
                waitlistAllowed, true, "到场请出示报名凭证\n请提前 15 分钟签到");
    }

    private static StoredActivitySession session(
            OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime closesAt, int capacity, int enrolled
    ) {
        return new StoredActivitySession(
                UUID.randomUUID(), "上午", startsAt, endsAt, startsAt.minusMinutes(30), closesAt, capacity, enrolled,
                false);
    }

    private static final class FakeStore implements ActivityCatalogReadStore {
        long total;
        List<StoredActivityCard> cards = List.of();
        List<LocalDate> dates = List.of();
        Optional<StoredActivityDetail> detail = Optional.empty();
        List<StoredActivitySession> sessions = List.of();
        List<String> gallery = List.of();
        final List<ActivityListQuery> queries = new ArrayList<>();
        int limit;
        int offset;
        String zoneId;
        OffsetDateTime rangeStart;
        OffsetDateTime rangeEnd;

        @Override
        public long countListed(ActivityListQuery query) {
            queries.add(query);
            return total;
        }

        @Override
        public List<StoredActivityCard> findListedPage(ActivityListQuery query, int limit, int offset) {
            queries.add(query);
            this.limit = limit;
            this.offset = offset;
            return cards;
        }

        @Override
        public List<LocalDate> findSessionDates(
                String cityCode, String type, String zoneId,
                OffsetDateTime rangeStart, OffsetDateTime rangeEnd, OffsetDateTime now
        ) {
            this.zoneId = zoneId;
            this.rangeStart = rangeStart;
            this.rangeEnd = rangeEnd;
            return dates;
        }

        @Override
        public Optional<StoredActivityDetail> findPublishedDetail(UUID activityId, String cityCode) {
            return detail;
        }

        @Override
        public List<StoredActivitySession> findActiveSessions(UUID activityId) {
            return sessions;
        }

        @Override
        public List<String> findGallery(UUID activityId) {
            return gallery;
        }
    }
}
