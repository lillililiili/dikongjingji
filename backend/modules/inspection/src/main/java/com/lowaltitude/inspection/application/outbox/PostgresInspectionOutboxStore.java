package com.lowaltitude.inspection.application.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.outbox.InspectionOutboxRows.EventContextRow;
import com.lowaltitude.inspection.application.outbox.InspectionOutboxRows.PendingEventRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionOutboxMapper;

final class PostgresInspectionOutboxStore implements InspectionOutboxStore {

    private final InspectionOutboxMapper mapper;

    PostgresInspectionOutboxStore(InspectionOutboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<UUID> findPendingIds(Instant now, int limit) {
        return mapper.findPendingIds(now, limit);
    }

    @Override
    public Optional<PendingEventRow> lockPending(UUID eventId) {
        return mapper.lockPending(eventId);
    }

    @Override
    public Optional<EventContextRow> findContext(String aggregateType, UUID aggregateId) {
        return switch (aggregateType) {
            case "RESERVATION" -> mapper.findReservationContext(aggregateId);
            case "PLAN" -> mapper.findPlanContext(aggregateId);
            case "SERVICE_ORDER" -> mapper.findOrderContext(aggregateId);
            case "SERVICE_VISIT" -> mapper.findVisitContext(aggregateId);
            case "DELIVERY" -> mapper.findDeliveryContext(aggregateId);
            default -> Optional.empty();
        };
    }

    @Override
    public void markPublished(UUID eventId, Instant at) {
        mapper.markPublished(eventId, at);
    }

    @Override
    public void markFailed(UUID eventId, String error, Instant nextAttemptAt) {
        mapper.markFailed(eventId, error, nextAttemptAt);
    }
}
