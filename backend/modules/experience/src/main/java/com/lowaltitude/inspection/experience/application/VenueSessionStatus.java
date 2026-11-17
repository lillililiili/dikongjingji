package com.lowaltitude.inspection.experience.application;

import java.time.OffsetDateTime;

/** 场次状态：可约、已满、已结束（已开始即算结束）。 */
public enum VenueSessionStatus {
    OPEN,
    FULL,
    ENDED;

    static VenueSessionStatus of(OffsetDateTime now, OffsetDateTime startsAt, int capacity, int bookedCount) {
        if (!startsAt.isAfter(now)) {
            return ENDED;
        }
        return bookedCount >= capacity ? FULL : OPEN;
    }
}
