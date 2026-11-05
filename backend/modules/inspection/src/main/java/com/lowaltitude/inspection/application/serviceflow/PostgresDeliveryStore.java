package com.lowaltitude.inspection.application.serviceflow;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.domain.files.DeliveryFileType;
import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.infrastructure.persistence.ServiceFlowPersistenceMapper;

final class PostgresDeliveryStore implements DeliveryStore {

    private final ServiceFlowPersistenceMapper mapper;

    PostgresDeliveryStore(ServiceFlowPersistenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<StoredDelivery> findDelivery(UUID deliveryId) {
        return mapper.findDelivery(deliveryId).map(this::toDelivery);
    }

    @Override
    public Optional<StoredDelivery> findDeliveryByVisit(UUID visitId) {
        return mapper.findDeliveryByVisit(visitId).map(this::toDelivery);
    }

    @Override
    public Optional<DeliveryFileObject> findFileObject(UUID fileId) {
        return mapper.findDeliveryFileObject(fileId).map(row ->
                new DeliveryFileObject(
                        row.id(),
                        row.visitId(),
                        row.providerId(),
                        fileType(row.extension()),
                        row.contentType(),
                        row.sizeBytes(),
                        row.bucket(),
                        row.objectKey(),
                        FileObjectStatus.valueOf(row.status())));
    }

    @Override
    public Optional<StoredAcceptance> findAcceptance(UUID acceptanceId) {
        return mapper.findAcceptance(acceptanceId).map(this::toAcceptance);
    }

    @Override
    public Optional<StoredAcceptance> findAcceptanceByVisit(UUID visitId) {
        return mapper.findAcceptanceByVisit(visitId).map(this::toAcceptance);
    }

    @Override
    public Optional<DeliveryIdempotencyEntry> findDeliveryIdempotency(
            UUID actorId,
            String operation,
            UUID key
    ) {
        return mapper.findIdempotency(actorId, operation, key)
                .map(row -> new DeliveryIdempotencyEntry(
                        actorId,
                        operation,
                        key,
                        row.requestDigest(),
                        row.resourceId()));
    }

    @Override
    public DeliveryWriteResult publishDelivery(
            StoredServiceOrder expectedOrder,
            ServiceOrderStatus nextOrderStatus,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit deliveredVisit,
            StoredDelivery delivery,
            DeliveryIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        if (mapper.insertIdempotency(
                entry.actorId(),
                entry.operation(),
                entry.key(),
                entry.requestDigest(),
                "DELIVERY",
                entry.resourceId(),
                201) == 0) {
            return DeliveryWriteResult.IDEMPOTENCY_EXISTS;
        }
        Instant at = delivery.publishedAt();
        if (mapper.updateVisitDelivered(
                expectedVisit.id(),
                expectedVisit.version(),
                at,
                actor.subjectId()) == 0) {
            return resolveVisitConflict(expectedVisit.id(), expectedVisit.version());
        }
        if (expectedOrder.status() != nextOrderStatus) {
            if (mapper.updateOrderDelivered(
                    expectedOrder.id(),
                    expectedOrder.version(),
                    at,
                    actor.subjectId()) == 0) {
                return resolveOrderConflict(expectedOrder.id(), expectedOrder.version());
            }
            mapper.insertTransition(
                    "SERVICE_ORDER",
                    expectedOrder.id(),
                    expectedOrder.status().name(),
                    nextOrderStatus.name(),
                    "PROVIDER_OPERATOR",
                    actor.subjectId(),
                    requestId,
                    at);
        }
        mapper.insertDelivery(
                delivery.id(),
                delivery.serviceOrderId(),
                delivery.visitId(),
                delivery.towerCount(),
                delivery.inspectedLengthMeters(),
                ServiceFlowJson.metrics(delivery.metrics()),
                delivery.issueCount(),
                delivery.issueGroupBy().name(),
                ServiceFlowJson.issues(delivery.issueSummary()),
                actor.subjectId(),
                at,
                delivery.version());
        for (int index = 0; index < delivery.files().size(); index++) {
            StoredDeliveryFile file = delivery.files().get(index);
            mapper.insertDeliveryFile(
                    delivery.id(),
                    file.fileId(),
                    file.type().name(),
                    file.displayName(),
                    file.pointCount(),
                    file.itemCount(),
                    index,
                    at);
        }
        mapper.insertTransition(
                "SERVICE_VISIT",
                expectedVisit.id(),
                expectedVisit.status().name(),
                deliveredVisit.status().name(),
                "PROVIDER_OPERATOR",
                actor.subjectId(),
                requestId,
                at);
        mapper.insertTransition(
                "DELIVERY",
                delivery.id(),
                null,
                DeliveryStatus.PUBLISHED.name(),
                "PROVIDER_OPERATOR",
                actor.subjectId(),
                requestId,
                at);
        mapper.insertOutbox(
                "DELIVERY",
                delivery.id(),
                "INSPECTION_DELIVERY_PUBLISHED",
                at);
        return DeliveryWriteResult.SAVED;
    }

    @Override
    public DeliveryWriteResult acceptDelivery(
            StoredServiceOrder expectedOrder,
            ServiceOrderStatus nextOrderStatus,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit acceptedVisit,
            StoredDelivery expectedDelivery,
            StoredDelivery acceptedDelivery,
            StoredAcceptance acceptance,
            DeliveryIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        if (mapper.insertIdempotency(
                entry.actorId(),
                entry.operation(),
                entry.key(),
                entry.requestDigest(),
                "ACCEPTANCE",
                entry.resourceId(),
                200) == 0) {
            return DeliveryWriteResult.IDEMPOTENCY_EXISTS;
        }
        Instant at = acceptance.acceptedAt();
        if (mapper.updateVisitAccepted(
                expectedVisit.id(),
                expectedVisit.version(),
                at,
                actor.subjectId()) == 0) {
            return resolveVisitConflict(expectedVisit.id(), expectedVisit.version());
        }
        if (expectedOrder.status() != nextOrderStatus) {
            if (mapper.updateOrderAccepted(
                    expectedOrder.id(),
                    expectedOrder.version(),
                    at,
                    actor.subjectId()) == 0) {
                return resolveOrderConflict(expectedOrder.id(), expectedOrder.version());
            }
            mapper.insertTransition(
                    "SERVICE_ORDER",
                    expectedOrder.id(),
                    expectedOrder.status().name(),
                    nextOrderStatus.name(),
                    "CUSTOMER",
                    actor.subjectId(),
                    requestId,
                    at);
        }
        if (mapper.updateDeliveryAccepted(
                expectedDelivery.id(),
                expectedDelivery.version(),
                at) == 0) {
            return DeliveryWriteResult.STATE_CONFLICT;
        }
        mapper.insertAcceptance(
                acceptance.id(),
                acceptance.serviceOrderId(),
                acceptance.visitId(),
                acceptance.deliveryId(),
                acceptance.acceptedBy(),
                at,
                acceptance.noteCiphertext() == null
                        ? null
                        : acceptance.noteCiphertext().getBytes(StandardCharsets.UTF_8),
                entry.key());
        mapper.insertTransition(
                "SERVICE_VISIT",
                expectedVisit.id(),
                expectedVisit.status().name(),
                acceptedVisit.status().name(),
                "CUSTOMER",
                actor.subjectId(),
                requestId,
                at);
        mapper.insertTransition(
                "DELIVERY",
                expectedDelivery.id(),
                expectedDelivery.status().name(),
                acceptedDelivery.status().name(),
                "CUSTOMER",
                actor.subjectId(),
                requestId,
                at);
        mapper.insertOutbox(
                "DELIVERY",
                expectedDelivery.id(),
                "INSPECTION_DELIVERY_ACCEPTED",
                at);
        return DeliveryWriteResult.SAVED;
    }

    @Override
    public boolean claimDeliveryIdempotency(
            DeliveryIdempotencyEntry entry,
            String resourceType,
            int responseStatus
    ) {
        return mapper.insertIdempotency(
                entry.actorId(),
                entry.operation(),
                entry.key(),
                entry.requestDigest(),
                resourceType,
                entry.resourceId(),
                responseStatus) == 1;
    }

    private StoredDelivery toDelivery(
            ServiceFlowPersistenceMapper.DeliveryRow row
    ) {
        List<DeliveryMetric> metrics = mapper.findDeliveryMetrics(row.id()).stream()
                .map(item -> new DeliveryMetric(
                        item.code(),
                        item.label(),
                        item.metricValue(),
                        item.unit()))
                .toList();
        List<IssueSummary> issues = mapper.findDeliveryIssues(row.id()).stream()
                .map(item -> new IssueSummary(
                        item.groupCode(),
                        item.groupName(),
                        item.count()))
                .toList();
        List<StoredDeliveryFile> files = mapper.findDeliveryFiles(row.id()).stream()
                .map(item -> new StoredDeliveryFile(
                        item.fileId(),
                        DeliveryFileType.valueOf(item.type()),
                        item.displayName(),
                        item.pointCount(),
                        item.itemCount(),
                        item.contentType(),
                        item.sizeBytes(),
                        FileObjectStatus.valueOf(item.status()),
                        item.bucket(),
                        item.objectKey()))
                .toList();
        return new StoredDelivery(
                row.id(),
                row.serviceOrderId(),
                row.visitId(),
                DeliveryStatus.valueOf(row.status()),
                row.towerCount(),
                row.inspectedLengthMeters(),
                metrics,
                row.issueCount(),
                IssueGroupBy.valueOf(row.issueGroupBy()),
                issues,
                files,
                row.publishedAt(),
                row.acceptedAt(),
                row.version());
    }

    private StoredAcceptance toAcceptance(
            ServiceFlowPersistenceMapper.AcceptanceRow row
    ) {
        return new StoredAcceptance(
                row.id(),
                row.serviceOrderId(),
                row.visitId(),
                row.deliveryId(),
                row.acceptedBy(),
                row.acceptedByName(),
                row.acceptedAt(),
                row.noteCiphertext() == null
                        ? null
                        : new String(row.noteCiphertext(), StandardCharsets.UTF_8),
                row.visitVersion(),
                ServiceOrderStatus.valueOf(row.orderStatus()));
    }

    private DeliveryWriteResult resolveVisitConflict(UUID visitId, int expectedVersion) {
        Optional<ServiceFlowPersistenceMapper.ServiceVisitRow> current =
                mapper.findVisit(visitId);
        if (current.isPresent() && current.get().version() != expectedVersion) {
            return DeliveryWriteResult.VERSION_CONFLICT;
        }
        return DeliveryWriteResult.STATE_CONFLICT;
    }

    private DeliveryWriteResult resolveOrderConflict(
            UUID orderId,
            int expectedVersion
    ) {
        Optional<ServiceFlowPersistenceMapper.ServiceOrderRow> current =
                mapper.findOrder(orderId);
        if (current.isPresent() && current.get().version() != expectedVersion) {
            return DeliveryWriteResult.VERSION_CONFLICT;
        }
        return DeliveryWriteResult.STATE_CONFLICT;
    }

    private static DeliveryFileType fileType(String extension) {
        for (DeliveryFileType type : DeliveryFileType.values()) {
            if (type.extension().substring(1).equalsIgnoreCase(extension)) {
                return type;
            }
        }
        throw new DeliveryFilesInvalidException();
    }
}
