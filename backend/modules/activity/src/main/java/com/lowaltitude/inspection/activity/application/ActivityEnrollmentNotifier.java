package com.lowaltitude.inspection.activity.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 报名成功告诉报名的账号（R6-5，docs/redesign/06-原型对齐修正计划.md）：免费报名提交成功、收费报名付款到账。
 * S5-3 加了三件：候补转正、改期成功、主办方录了结果。
 * 活动模块只认这个端口，启动模块把它接到站内信；两个模块互不依赖。
 *
 * <p>在业务的同一个事务里调用。实现者自己决定什么时候写（启动模块在事务提交之后写），而且不能抛异常。
 * 报名已退款由交易模块的退款到账转告，不经过这里。通知里只有活动名、场次时间和人数，不带参与人姓名。
 */
@FunctionalInterface
public interface ActivityEnrollmentNotifier {

    void enrolled(Enrolled event);

    /** 候补转正（S5-3）：名额为这条候补保留到 offerExpiresAt。 */
    default void waitlistOffered(WaitlistOffered event) {
    }

    /** 改期成功（S5-3）：原凭证作废，新场次的凭证已发放。 */
    default void rescheduled(Rescheduled event) {
    }

    /** 主办方给这张报名单里的凭证录了结果（S5-3）；同一张报名单只通知一次，由实现者按报名单去重。 */
    default void resultRecorded(ResultRecorded event) {
    }

    /**
     * 报名成功的事实：只有活动名、场次开始时间和人数，不带参与人姓名。
     *
     * @param people 这张报名单里生效的人数
     * @param paid   收费报名（付款到账时）为 true
     */
    record Enrolled(
            UUID accountId,
            UUID enrollmentId,
            String activityTitle,
            OffsetDateTime startsAt,
            int people,
            boolean paid,
            Instant at
    ) {
    }

    /** @param paid 收费活动：转正后要去付款；免费活动：要确认报名 */
    record WaitlistOffered(
            UUID accountId,
            UUID waitlistId,
            String activityTitle,
            OffsetDateTime startsAt,
            int people,
            boolean paid,
            OffsetDateTime offerExpiresAt,
            Instant at
    ) {
    }

    /** @param changeId 这一次改期的记录，同一次改期只记一条 */
    record Rescheduled(
            UUID accountId,
            UUID enrollmentId,
            UUID changeId,
            String activityTitle,
            OffsetDateTime newStartsAt,
            int people,
            Instant at
    ) {
    }

    record ResultRecorded(
            UUID accountId,
            UUID enrollmentId,
            String activityTitle,
            Instant at
    ) {
    }

    /** 没有接站内信时。 */
    static ActivityEnrollmentNotifier none() {
        return event -> {
        };
    }
}
