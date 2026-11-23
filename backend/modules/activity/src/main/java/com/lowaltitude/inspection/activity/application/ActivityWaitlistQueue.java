package com.lowaltitude.inspection.activity.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper.QueueRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper.SessionSeatsRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 候补转正（S5-3，docs/redesign/08-后续开发计划.md §5“候补、团队报名、改期”）：一场有名额放回来时，
 * 从排在最前的候补开始，人数放得下的依次转正（同单几个人一起转正；这一单放不下时它继续排在最前，后面放得下的先转正）。
 * 转正时名额就为它占住，保留 15 分钟、且不晚于场次开始；收费的在这段时间里去付款，免费的确认报名。
 *
 * <p>由放回名额的路径调用：取消凭证、交易订单没付款就关闭、改期离开、候补放弃或超时。调用方已经锁住这一场，
 * 并且已经把名额加回去了。
 */
public class ActivityWaitlistQueue implements ActivitySeatsReleased {

    /** 转正后保留名额的时间（和收费报名的付款时限一样）。 */
    static final Duration OFFER_WINDOW = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(ActivityWaitlistQueue.class);

    private final ActivityWaitlistMapper mapper;
    private final ActivityEnrollmentNotifier notifier;

    public ActivityWaitlistQueue(ActivityWaitlistMapper mapper, ActivityEnrollmentNotifier notifier) {
        this.mapper = mapper;
        this.notifier = notifier;
    }

    /** @return 这次转正了几条候补 */
    @Override
    public int released(UUID sessionId, OffsetDateTime now) {
        SessionSeatsRow session = mapper.findSessionSeats(sessionId);
        // 下架、停售、已经开始的场次不再转正；开始时还在排队的由定时任务结束。
        if (session == null || !session.openForOffers() || !now.isBefore(session.startsAt())) {
            return 0;
        }
        int free = session.capacity() - session.enrolledCount();
        int offered = 0;
        for (QueueRow entry : mapper.findQueue(sessionId)) {
            if (free <= 0) {
                break;
            }
            if (entry.people() > free) {
                continue;
            }
            OffsetDateTime expiresAt = now.plus(OFFER_WINDOW);
            if (expiresAt.isAfter(session.startsAt())) {
                expiresAt = session.startsAt();
            }
            if (mapper.offer(entry.id(), now, expiresAt) == 0) {
                continue;
            }
            mapper.addEnrolled(sessionId, entry.people(), now);
            free -= entry.people();
            offered++;
            notifyOffered(entry, session, expiresAt, now);
        }
        return offered;
    }

    private void notifyOffered(QueueRow entry, SessionSeatsRow session, OffsetDateTime expiresAt, OffsetDateTime now) {
        boolean paid = ActivityPriceMode.PER_PERSON.name().equals(session.priceMode())
                && session.priceAmountMinor() != null
                && session.priceAmountMinor() > 0;
        try {
            notifier.waitlistOffered(new ActivityEnrollmentNotifier.WaitlistOffered(
                    entry.accountId(), entry.id(), session.title(), session.startsAt(), entry.people(), paid,
                    expiresAt, now.toInstant()));
        } catch (RuntimeException exception) {
            log.error("telling account {} that waitlist {} was offered a seat failed", entry.accountId(), entry.id(),
                    exception);
        }
    }
}
