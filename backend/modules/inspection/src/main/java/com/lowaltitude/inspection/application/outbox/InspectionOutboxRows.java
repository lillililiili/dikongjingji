package com.lowaltitude.inspection.application.outbox;

import java.time.Instant;
import java.util.UUID;

public final class InspectionOutboxRows {

    private InspectionOutboxRows() {
    }

    public record PendingEventRow(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Instant occurredAt,
            int attemptCount
    ) {
    }

    public record EventContextRow(
            UUID customerId,
            UUID reservationId,
            String reservationNo,
            String serviceTitle,
            UUID planId,
            UUID serviceOrderId,
            UUID visitId,
            Integer visitNo,
            Instant plannedStartAt
    ) {
    }
}
