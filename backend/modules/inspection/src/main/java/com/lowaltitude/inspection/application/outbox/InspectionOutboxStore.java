package com.lowaltitude.inspection.application.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.outbox.InspectionOutboxRows.EventContextRow;
import com.lowaltitude.inspection.application.outbox.InspectionOutboxRows.PendingEventRow;

interface InspectionOutboxStore {

    List<UUID> findPendingIds(Instant now, int limit);

    /** 锁住一条还没发布的事件；已发布或正被别的实例处理时为空。 */
    Optional<PendingEventRow> lockPending(UUID eventId);

    Optional<EventContextRow> findContext(String aggregateType, UUID aggregateId);

    void markPublished(UUID eventId, Instant at);

    void markFailed(UUID eventId, String error, Instant nextAttemptAt);
}
