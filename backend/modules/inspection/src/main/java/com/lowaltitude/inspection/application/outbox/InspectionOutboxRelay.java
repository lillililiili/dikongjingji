package com.lowaltitude.inspection.application.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.outbox.InspectionOutboxRows.EventContextRow;
import com.lowaltitude.inspection.application.outbox.InspectionOutboxRows.PendingEventRow;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 把 inspection_domain_event_outbox 里已提交的事件交给订阅方（事务性发件箱的转发端）。
 * 每条事件单独一个事务：锁住这条事件、补上稳定标识、调用订阅方、标记已发布，要么都成要么都不成。
 * 一条失败只影响它自己：记下原因和下次重试时间，后面的事件照常处理。
 */
public class InspectionOutboxRelay {

    static final int BATCH_SIZE = 50;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final Duration FIRST_RETRY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY = Duration.ofMinutes(10);

    private final InspectionOutboxStore store;
    private final InspectionProgressEventSink sink;
    private final TransactionOperations transactions;
    private final Clock clock;

    InspectionOutboxRelay(
            InspectionOutboxStore store,
            InspectionProgressEventSink sink,
            TransactionOperations transactions,
            Clock clock
    ) {
        this.store = store;
        this.sink = sink;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** 处理一批到期的事件，返回成功发布的条数。 */
    public int relayPending() {
        List<UUID> pending = transactions.execute(status -> store.findPendingIds(clock.instant(), BATCH_SIZE));
        int published = 0;
        for (UUID eventId : pending == null ? List.<UUID>of() : pending) {
            if (relay(eventId)) {
                published++;
            }
        }
        return published;
    }

    private boolean relay(UUID eventId) {
        PendingEventRow[] seen = new PendingEventRow[1];
        try {
            Boolean done = transactions.execute(status -> {
                Optional<PendingEventRow> locked = store.lockPending(eventId);
                if (locked.isEmpty()) {
                    return false;
                }
                PendingEventRow event = locked.get();
                seen[0] = event;
                EventContextRow context = store.findContext(event.aggregateType(), event.aggregateId())
                        .orElseThrow(() -> new IllegalStateException(
                                "no " + event.aggregateType() + " found for the event"));
                sink.accept(new InspectionProgressEvent(
                        event.id(),
                        event.eventType(),
                        event.occurredAt(),
                        context.customerId(),
                        context.reservationId(),
                        context.reservationNo(),
                        context.serviceTitle(),
                        context.planId(),
                        context.serviceOrderId(),
                        context.visitId(),
                        context.visitNo(),
                        context.plannedStartAt()));
                store.markPublished(event.id(), clock.instant());
                return true;
            });
            return Boolean.TRUE.equals(done);
        } catch (RuntimeException failure) {
            int attempts = seen[0] == null ? 0 : seen[0].attemptCount();
            Instant nextAttempt = clock.instant().plus(retryDelay(attempts));
            transactions.executeWithoutResult(status ->
                    store.markFailed(eventId, describe(failure), nextAttempt));
            return false;
        }
    }

    /** 5 秒起，每次翻倍，最多 10 分钟。 */
    static Duration retryDelay(int attemptsSoFar) {
        long seconds = FIRST_RETRY.toSeconds() << Math.min(Math.max(attemptsSoFar, 0), 10);
        return seconds >= MAX_RETRY.toSeconds() ? MAX_RETRY : Duration.ofSeconds(seconds);
    }

    private static String describe(RuntimeException failure) {
        String text = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}
