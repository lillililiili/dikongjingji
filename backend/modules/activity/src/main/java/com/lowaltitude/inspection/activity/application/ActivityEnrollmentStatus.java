package com.lowaltitude.inspection.activity.application;

import java.time.OffsetDateTime;

/**
 * 报名状态，声明顺序即“最好”到“最差”：一个活动取各场次里最靠前的那个。
 */
public enum ActivityEnrollmentStatus {
    /** 报名中 */
    OPEN,
    /** 已满，可候补 */
    FULL_WAITLIST,
    /** 已满 */
    FULL,
    /** 报名截止（含已开始但未结束） */
    CLOSED,
    /** 已结束 */
    ENDED;

    static ActivityEnrollmentStatus ofSession(
            OffsetDateTime now,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime enrollmentClosesAt,
            int capacity,
            int enrolledCount,
            boolean waitlistAllowed
    ) {
        return ofSession(now, startsAt, endsAt, enrollmentClosesAt, capacity, enrolledCount, waitlistAllowed, false);
    }

    /** saleStopped：运营把这一场停售了（S3-1，迁移 V004）——没结束就按报名截止处理，已有的报名不受影响。 */
    static ActivityEnrollmentStatus ofSession(
            OffsetDateTime now,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime enrollmentClosesAt,
            int capacity,
            int enrolledCount,
            boolean waitlistAllowed,
            boolean saleStopped
    ) {
        OffsetDateTime finishedAt = endsAt == null ? startsAt : endsAt;
        if (!finishedAt.isAfter(now)) {
            return ENDED;
        }
        if (saleStopped || !enrollmentClosesAt.isAfter(now)) {
            return CLOSED;
        }
        if (enrolledCount >= capacity) {
            return waitlistAllowed ? FULL_WAITLIST : FULL;
        }
        return OPEN;
    }
}
