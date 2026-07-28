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
    public Optional<StoredDelivery> findDeliveryByOrder(UUID serviceOrderId) {
        return mapper.findDeliveryByOrder(serviceOrderId).map(this::toDelivery);
    }

    @Override
    public Optional<DeliveryFileObject> findFileObject(UUID fileId) {
        return mapper.findDeliveryFileObject(fileId).map(row ->
                new DeliveryFileObject(
                        row.id(),
                        row.serviceOrderId(),
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
    public Optional<StoredAcceptance> findAcceptanceByOrder(UUID serviceOrderId) {
        return mapper.findAcceptanceByOrder(serviceOrderId).map(this::toAcceptance);
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
            StoredServiceOrder deliveredOrder,
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
        if (mapper.updateOrderDelivered(
                expectedOrder.id(),
                expectedOrder.version(),
                deliveredOrder.deliveredAt(),
                actor.subjectId()) == 0) {
            return resolveOrderConflict(expectedOrder.id(), expectedOrder.version());
        }
        mapper.insertDelivery(
                delivery.id(),
                delivery.serviceOrderId(),
                delivery.towerCount(),
                delivery.inspectedLengthMeters(),
                delivery.issueCount(),
                issueSummaryJson(delivery.issueSummary()),
                actor.subjectId(),
                delivery.publishedAt(),
                delivery.version());
        for (int index = 0; index < delivery.files().size(); index++) {
            StoredDeliveryFile file = delivery.files().get(index);
            mapper.insertDeliveryFile(
                    delivery.id(),
                    file.fileId(),
                    file.type().name(),
                    file.displayName(),
                    file.pointCount(),
                    index,
                    delivery.publishedAt());
        }
        mapper.insertTransition(
                "SERVICE_ORDER",
                expectedOrder.id(),
                expectedOrder.status().name(),
                deliveredOrder.status().name(),
                "PROVIDER_OPERATOR",
                actor.subjectId(),
                requestId,
                delivery.publishedAt());
        mapper.insertTransition(
                "DELIVERY",
                delivery.id(),
                null,
                DeliveryStatus.PUBLISHED.name(),
                "PROVIDER_OPERATOR",
                actor.subjectId(),
                requestId,
                delivery.publishedAt());
        mapper.insertOutbox(
                "DELIVERY",
                delivery.id(),
                "INSPECTION_DELIVERY_PUBLISHED",
                delivery.publishedAt());
        return DeliveryWriteResult.SAVED;
    }

    @Override
    public DeliveryWriteResult acceptDelivery(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder acceptedOrder,
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
        if (mapper.updateOrderAccepted(
                expectedOrder.id(),
                expectedOrder.version(),
                acceptance.acceptedAt(),
                actor.subjectId()) == 0) {
            return resolveOrderConflict(expectedOrder.id(), expectedOrder.version());
        }
        if (mapper.updateDeliveryAccepted(
                expectedDelivery.id(),
                expectedDelivery.version(),
                acceptance.acceptedAt()) == 0) {
            return DeliveryWriteResult.STATE_CONFLICT;
        }
        mapper.insertAcceptance(
                acceptance.id(),
                acceptance.serviceOrderId(),
                acceptance.deliveryId(),
                acceptance.acceptedBy(),
                acceptance.acceptedAt(),
                acceptance.noteCiphertext() == null
                        ? null
                        : acceptance.noteCiphertext().getBytes(StandardCharsets.UTF_8),
                entry.key());
        mapper.insertTransition(
                "SERVICE_ORDER",
                expectedOrder.id(),
                expectedOrder.status().name(),
                acceptedOrder.status().name(),
                "CUSTOMER",
                actor.subjectId(),
                requestId,
                acceptance.acceptedAt());
        mapper.insertTransition(
                "DELIVERY",
                expectedDelivery.id(),
                expectedDelivery.status().name(),
                acceptedDelivery.status().name(),
                "CUSTOMER",
                actor.subjectId(),
                requestId,
                acceptance.acceptedAt());
        mapper.insertOutbox(
                "DELIVERY",
                expectedDelivery.id(),
                "INSPECTION_DELIVERY_ACCEPTED",
                acceptance.acceptedAt());
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
        List<IssueSummary> issues = mapper.findDeliveryIssues(row.id()).stream()
                .map(item -> new IssueSummary(
                        item.severityCode(),
                        item.severityName(),
                        item.count()))
                .toList();
        List<StoredDeliveryFile> files = mapper.findDeliveryFiles(row.id()).stream()
                .map(item -> new StoredDeliveryFile(
                        item.fileId(),
                        DeliveryFileType.valueOf(item.type()),
                        item.displayName(),
                        item.pointCount(),
                        item.contentType(),
                        item.sizeBytes(),
                        FileObjectStatus.valueOf(item.status()),
                        item.bucket(),
                        item.objectKey()))
                .toList();
        return new StoredDelivery(
                row.id(),
                row.serviceOrderId(),
                DeliveryStatus.valueOf(row.status()),
                row.towerCount(),
                row.inspectedLengthMeters(),
                row.issueCount(),
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
                row.deliveryId(),
                row.acceptedBy(),
                row.acceptedAt(),
                row.noteCiphertext() == null
                        ? null
                        : new String(row.noteCiphertext(), StandardCharsets.UTF_8),
                row.orderVersion());
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

    private static String issueSummaryJson(List<IssueSummary> issues) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < issues.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            IssueSummary issue = issues.get(index);
            json.append("{\"severityCode\":\"")
                    .append(jsonEscape(issue.severityCode()))
                    .append("\",\"severityName\":\"")
                    .append(jsonEscape(issue.severityName()))
                    .append("\",\"count\":")
                    .append(issue.count())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
