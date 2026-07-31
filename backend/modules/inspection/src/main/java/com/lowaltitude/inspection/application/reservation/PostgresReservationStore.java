package com.lowaltitude.inspection.application.reservation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.domain.files.FilePurpose;
import com.lowaltitude.inspection.infrastructure.persistence.ReservationPersistenceMapper;

final class PostgresReservationStore
        implements ReservationStore, InspectionServiceCatalog, FileObjectStore {
    private final ReservationPersistenceMapper mapper;
    PostgresReservationStore(ReservationPersistenceMapper mapper) { this.mapper = mapper; }
    @Override public Optional<InspectionServiceSnapshot> findActive(UUID id, String cityCode) {
        return mapper.findActiveService(id, cityCode).map(row -> new InspectionServiceSnapshot(
                row.serviceId(),
                row.providerId(),
                row.title(),
                row.coverUrl(),
                row.cityCode(),
                mapper.findServiceBookingItems(id).stream()
                        .map(item -> new InspectionItemSnapshot(item.code(), item.label()))
                        .toList()));
    }
    @Override public Optional<IdempotencyEntry> findIdempotency(UUID actorId, UUID key) {
        return mapper.findIdempotency(actorId, key).map(row -> new IdempotencyEntry(actorId, key, row.requestDigest(), row.reservationId()));
    }
    @Override public void save(StoredReservation value, IdempotencyEntry entry) {
        if (mapper.insertIdempotency(
                entry.actorId(),
                entry.key(),
                entry.requestDigest(),
                entry.reservationId()) == 0) {
            throw new DuplicateIdempotencyKeyException();
        }
        mapper.insertReservation(new ReservationPersistenceMapper.ReservationRow(value.id(), value.reservationNo(), value.customerId(), value.snapshot().providerId(), value.snapshot().serviceId(), value.snapshot().title(), value.snapshot().coverUrl(), value.cityCode(), bytes(value.addressCiphertext()), value.longitude(), value.latitude(), value.lineLengthMeters(), value.preferredServiceDate(), value.preferredTimeSlotCode(), bytes(value.noteCiphertext()), bytes(value.contactNameCiphertext()), bytes(value.contactMobileCiphertext()), value.agreementVersion(), value.submittedAt(), value.version()));
        for (int index = 0; index < value.items().size(); index++) {
            InspectionItemSnapshot item = value.items().get(index);
            mapper.insertItem(
                    UUID.randomUUID(),
                    value.id(),
                    item.code(),
                    item.label(),
                    index);
        }
    }
    @Override public Optional<StoredReservation> findById(UUID id) {
        return mapper.findReservation(id).map(row -> new StoredReservation(
                row.id(),
                row.reservationNo(),
                row.customerId(),
                new InspectionServiceSnapshot(
                        row.serviceId(),
                        row.providerId(),
                        row.title(),
                        row.coverUrl(),
                        row.cityCode(),
                        List.of()),
                row.cityCode(),
                text(row.addressCiphertext()),
                row.longitude(),
                row.latitude(),
                row.lineLengthMeters(),
                row.preferredServiceDate(),
                row.preferredTimeSlotCode(),
                text(row.noteCiphertext()),
                text(row.contactNameCiphertext()),
                text(row.contactMobileCiphertext()),
                mapper.findItems(id).stream()
                        .map(item -> new InspectionItemSnapshot(
                                item.code(),
                                item.label()))
                        .toList(),
                row.agreementVersion(),
                row.submittedAt(),
                row.version()));
    }
    @Override public void lockActiveService(UUID serviceId, String cityCode) {
        if (mapper.lockActiveService(serviceId, cityCode) == null) {
            throw new ReservationValidationException("service is unknown or disabled");
        }
    }
    @Override public List<BookedTimeSlotCount> findBookedTimeSlotCounts(
            UUID serviceId, String cityCode, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        return mapper.findBookedTimeSlotCounts(serviceId, cityCode, startDate, endDate).stream()
                .map(row -> new BookedTimeSlotCount(
                        row.serviceDate(), row.slotCode(), row.bookedCount()))
                .toList();
    }
    @Override public Optional<ReservationFlowState> findFlowState(UUID reservationId) {
        return mapper.findReservationFlowState(reservationId)
                .map(row -> new ReservationFlowState(
                        row.status(),
                        row.currentPlanId(),
                        row.version()));
    }
    @Override public List<ReservationFileView> findAttachments(UUID reservationId) {
        return mapper.findReservationFiles(reservationId).stream()
                .map(row -> new ReservationFileView(
                        row.id(),
                        row.originalName(),
                        row.contentType(),
                        row.sizeBytes(),
                        FileObjectStatus.valueOf(row.status()),
                        row.failureReason()))
                .toList();
    }
    @Override public Optional<ReservationFileOwner> findReservationOwner(UUID reservationId) {
        return mapper.findReservationOwner(reservationId)
                .map(row -> new ReservationFileOwner(
                        row.reservationId(),
                        row.customerId(),
                        row.providerId()));
    }
    @Override public int countReservationFiles(UUID reservationId) {
        return mapper.countReservationFiles(reservationId);
    }
    @Override public Optional<DeliveryFileOwner> findDeliveryFileOwner(UUID serviceOrderId) {
        return mapper.findDeliveryFileOwner(serviceOrderId)
                .map(row -> new DeliveryFileOwner(
                        row.serviceOrderId(), row.customerId(), row.providerId(), row.orderStatus()));
    }
    @Override public int countDeliveryFiles(UUID serviceOrderId) {
        return mapper.countDeliveryFiles(serviceOrderId);
    }
    @Override public Optional<FileIdempotencyEntry> findIdempotency(
            UUID actorId,
            String operation,
            UUID key
    ) {
        return mapper.findFileIdempotency(actorId, operation, key)
                .map(row -> new FileIdempotencyEntry(
                        actorId,
                        operation,
                        key,
                        row.requestDigest(),
                        row.fileId()));
    }
    @Override public void saveIntent(StoredFileObject file, FileIdempotencyEntry entry) {
        if (mapper.insertFileIdempotency(
                entry.actorId(),
                entry.operation(),
                entry.key(),
                entry.requestDigest(),
                entry.fileId(),
                201) == 0) {
            throw new DuplicateIdempotencyKeyException();
        }
        mapper.insertFileObject(new ReservationPersistenceMapper.FileRow(
                file.id(),
                file.purpose().name(),
                file.ownerId(),
                file.providerId(),
                file.bucket(),
                file.objectKey(),
                file.originalName(),
                file.contentType(),
                file.extension(),
                file.sizeBytes(),
                file.sha256(),
                file.etag(),
                file.status().name(),
                file.failureReason(),
                file.uploadedBy(),
                file.createdAt(),
                file.version()));
    }
    @Override public boolean claimCompletion(FileIdempotencyEntry entry) {
        return mapper.insertFileIdempotency(
                entry.actorId(),
                entry.operation(),
                entry.key(),
                entry.requestDigest(),
                entry.fileId(),
                200) == 1;
    }
    @Override public Optional<StoredFileObject> findFile(UUID fileId) {
        return mapper.findFile(fileId).map(PostgresReservationStore::toStoredFile);
    }
    @Override public void updateStatus(
            UUID fileId,
            FileObjectStatus expected,
            FileObjectStatus next,
            String etag,
            String failureReason
    ) {
        if (mapper.updateFileStatus(
                fileId,
                expected.name(),
                next.name(),
                etag,
                failureReason) != 1) {
            throw new FileUploadConflictException();
        }
    }
    @Override public void linkReservationAttachment(UUID reservationId, UUID fileId) {
        mapper.linkReservationAttachment(reservationId, fileId);
    }

    private static StoredFileObject toStoredFile(ReservationPersistenceMapper.FileRow row) {
        return new StoredFileObject(
                row.id(),
                FilePurpose.valueOf(row.purpose()),
                row.ownerId(),
                row.providerId(),
                row.bucket(),
                row.objectKey(),
                row.originalName(),
                row.contentType(),
                row.extension(),
                row.sizeBytes(),
                row.sha256(),
                row.etag(),
                FileObjectStatus.valueOf(row.status()),
                row.failureReason(),
                row.uploadedBy(),
                row.createdAt(),
                row.version());
    }
    private static byte[] bytes(String value) { return value == null ? null : value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return value == null ? null : new String(value, StandardCharsets.UTF_8); }
}
