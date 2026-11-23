package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import org.junit.jupiter.api.Test;

/** 收藏列表里的活动（计划 08 S5-6）：按人价、免费、咨询报价；说明是最近一场没结束的场次的北京日期。 */
class ActivityFavoriteTargetsTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-12T08:00:00Z");

    @Test
    void theNextSessionThatHasNotEndedGivesTheDateInBeijing() {
        FavoritePorts.Target target = ActivityFavoriteTargets.target(activity(
                new ActivityPriceView(ActivityPriceMode.PER_PERSON, 12_000L, "CNY", true),
                List.of(session("2026-11-10T01:00:00Z", "2026-11-10T05:00:00Z"),
                        session("2026-11-15T17:00:00Z", "2026-11-15T20:00:00Z"),
                        session("2026-11-14T01:00:00Z", "2026-11-14T05:00:00Z"))), NOW);

        assertThat(target.title()).isEqualTo("青少年无人机编程挑战赛（东营站）");
        // 11-14 01:00Z 是北京 11/14；11-15 17:00Z 已是北京 11/16，但更晚。
        assertThat(target.meta()).isEqualTo("11/14");
        assertThat(target.price()).isEqualTo(FavoritePorts.Price.amount(12_000, false, "/人"));
        assertThat(target.productCategory()).isNull();
    }

    @Test
    void freeAndInquiryActivitiesHaveNoAmountAndPastOnesNoDate() {
        FavoritePorts.Target free = ActivityFavoriteTargets.target(activity(
                new ActivityPriceView(ActivityPriceMode.FREE, null, "CNY", false),
                List.of(session("2026-11-10T01:00:00Z", "2026-11-10T05:00:00Z"))), NOW);
        FavoritePorts.Target inquiry = ActivityFavoriteTargets.target(activity(
                new ActivityPriceView(ActivityPriceMode.INQUIRY, null, "CNY", false), List.of()), NOW);

        assertThat(free.price()).isEqualTo(FavoritePorts.Price.free());
        assertThat(free.meta()).isNull();
        assertThat(inquiry.price()).isEqualTo(FavoritePorts.Price.quote());
    }

    private static ActivityDetailView activity(ActivityPriceView price, List<ActivitySessionView> sessions) {
        return new ActivityDetailView(UUID.randomUUID(), ActivityType.CONTEST, "青少年无人机编程挑战赛（东营站）", null,
                new ActivityMediaView("https://img.example.com/contest.jpg", "比赛"), price, null,
                ActivityEnrollmentStatus.OPEN, null, null, null, null, null, null, null, false, false, sessions);
    }

    private static ActivitySessionView session(String startsAt, String endsAt) {
        return new ActivitySessionView(UUID.randomUUID(), "场次", OffsetDateTime.parse(startsAt),
                OffsetDateTime.parse(endsAt), null, null, 30, 10, ActivityEnrollmentStatus.OPEN);
    }
}
