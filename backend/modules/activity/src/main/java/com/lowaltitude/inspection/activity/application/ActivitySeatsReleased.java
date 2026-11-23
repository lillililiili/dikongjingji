package com.lowaltitude.inspection.activity.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一场有名额放回来了（S5-3）：调用方已经锁住这一场、把名额加回去了，在同一个事务里调用。
 * 候补转正（{@link ActivityWaitlistQueue}）实现它；没有候补时什么都不做。
 */
@FunctionalInterface
public interface ActivitySeatsReleased {

    /** @return 这次转正了几条候补 */
    int released(UUID sessionId, OffsetDateTime now);

    static ActivitySeatsReleased none() {
        return (sessionId, now) -> 0;
    }
}
