package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.domain.files.ObjectStoragePort;
import com.lowaltitude.inspection.domain.files.PreparedObjectUpload;
import com.lowaltitude.inspection.domain.files.PresignedObjectIntent;
import com.lowaltitude.inspection.domain.files.UploadObjectRequest;
import com.lowaltitude.inspection.domain.files.UploadVerificationResult;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.Test;

class FileObjectApplicationServiceTest {

    static final UUID CUSTOMER =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CUSTOMER =
            UUID.fromString("11111111-1111-1111-1111-222222222222");
    static final UUID PROVIDER =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID RESERVATION =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final Instant NOW = Instant.parse("2026-07-28T03:00:00Z");
    private static final String SHA256 = "a".repeat(64);

    @Test
    void customerCreatesIdempotentUploadIntentForOwnReservation() {
        InMemoryFileObjectStore store = new InMemoryFileObjectStore();
        RecordingObjectStorage storage = new RecordingObjectStorage();
        FileObjectApplicationService service = service(store, storage);

        UploadIntentView first =
                service.createUploadIntent(customer(), IDEMPOTENCY_KEY, command());
        UploadIntentView replay =
                service.createUploadIntent(customer(), IDEMPOTENCY_KEY, command());

        assertThat(first.fileId()).isEqualTo(replay.fileId());
        assertThat(first.method()).isEqualTo("PUT");
        assertThat(first.uploadUrl()).isEqualTo(URI.create("https://storage.test/upload"));
        assertThat(store.files).hasSize(1);
        assertThat(store.files.get(first.fileId()).ownerId()).isEqualTo(RESERVATION);
        assertThat(store.files.get(first.fileId()).uploadedBy()).isEqualTo(CUSTOMER);
    }

    @Test
    void customerCannotCreateIntentForAnotherCustomersReservation() {
        InMemoryFileObjectStore store = new InMemoryFileObjectStore();
        FileObjectApplicationService service =
                service(store, new RecordingObjectStorage());

        assertThatThrownBy(() -> service.createUploadIntent(
                        customer(OTHER_CUSTOMER),
                        IDEMPOTENCY_KEY,
                        command()))
                .isInstanceOf(ReservationNotFoundException.class);
        assertThat(store.files).isEmpty();
    }

    @Test
    void completeVerifiesPromotesAndLinksReadyAttachment() {
        InMemoryFileObjectStore store = new InMemoryFileObjectStore();
        RecordingObjectStorage storage = new RecordingObjectStorage();
        FileObjectApplicationService service = service(store, storage);
        UploadIntentView intent =
                service.createUploadIntent(customer(), IDEMPOTENCY_KEY, command());

        FileObjectView completed = service.completeUpload(
                customer(),
                UUID.randomUUID(),
                intent.fileId(),
                new CompleteUploadCommand("etag-1", 1_024, SHA256));

        assertThat(completed.status()).isEqualTo(FileObjectStatus.READY);
        assertThat(storage.verifiedRequest.sha256()).isEqualTo(SHA256);
        assertThat(store.files.get(intent.fileId()).status()).isEqualTo(FileObjectStatus.READY);
        assertThat(store.linkedFiles).containsExactly(intent.fileId());
    }

    @Test
    void rejectedContentCannotBeLinkedToReservation() {
        InMemoryFileObjectStore store = new InMemoryFileObjectStore();
        RecordingObjectStorage storage = new RecordingObjectStorage();
        storage.verification = UploadVerificationResult.REJECTED;
        FileObjectApplicationService service = service(store, storage);
        UploadIntentView intent =
                service.createUploadIntent(customer(), IDEMPOTENCY_KEY, command());

        FileObjectView completed = service.completeUpload(
                customer(),
                UUID.randomUUID(),
                intent.fileId(),
                new CompleteUploadCommand("etag-1", 1_024, SHA256));

        assertThat(completed.status()).isEqualTo(FileObjectStatus.REJECTED);
        assertThat(store.linkedFiles).containsExactly(intent.fileId());
    }

    @Test
    void completionRetryResumesScanningWithTheSameIdempotencyKey() {
        InMemoryFileObjectStore store = new InMemoryFileObjectStore();
        RecordingObjectStorage storage = new RecordingObjectStorage();
        storage.failFirstVerification = true;
        FileObjectApplicationService service = service(store, storage);
        UploadIntentView intent =
                service.createUploadIntent(customer(), IDEMPOTENCY_KEY, command());
        UUID completionKey = UUID.randomUUID();
        CompleteUploadCommand completion =
                new CompleteUploadCommand("etag-1", 1_024, SHA256);

        assertThatThrownBy(() -> service.completeUpload(
                        customer(), completionKey, intent.fileId(), completion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary storage failure");
        assertThat(store.files.get(intent.fileId()).status())
                .isEqualTo(FileObjectStatus.SCANNING);

        FileObjectView retried =
                service.completeUpload(customer(), completionKey, intent.fileId(), completion);

        assertThat(retried.status()).isEqualTo(FileObjectStatus.READY);
        assertThat(storage.verificationAttempts).isEqualTo(2);
        assertThat(store.files).hasSize(1);
    }

    private static FileObjectApplicationService service(
            InMemoryFileObjectStore store,
            RecordingObjectStorage storage
    ) {
        store.reservations.put(
                RESERVATION,
                new ReservationFileOwner(RESERVATION, CUSTOMER, PROVIDER));
        return new FileObjectApplicationService(
                store,
                storage,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CreateUploadIntentCommand command() {
        return new CreateUploadIntentCommand(
                "RESERVATION_ATTACHMENT",
                RESERVATION,
                null,
                "现场照片.jpg",
                "image/jpeg",
                1_024,
                SHA256);
    }

    private static CurrentSubject customer() {
        return customer(CUSTOMER);
    }

    private static CurrentSubject customer(UUID subjectId) {
        return new CurrentSubject(
                subjectId,
                Set.of("CUSTOMER"),
                UUID.randomUUID(),
                Set.of("inspection.reservation.create"),
                0);
    }
}

final class InMemoryFileObjectStore implements FileObjectStore {

    final Map<UUID, ReservationFileOwner> reservations = new HashMap<>();
    final Map<UUID, StoredFileObject> files = new HashMap<>();
    final Map<String, FileIdempotencyEntry> idempotency = new HashMap<>();
    final java.util.ArrayList<UUID> linkedFiles = new java.util.ArrayList<>();

    @Override
    public Optional<ReservationFileOwner> findReservationOwner(UUID reservationId) {
        return Optional.ofNullable(reservations.get(reservationId));
    }

    @Override
    public int countReservationFiles(UUID reservationId) {
        return (int) files.values().stream()
                .filter(file -> file.ownerId().equals(reservationId))
                .filter(file -> file.status() != FileObjectStatus.DELETED)
                .count();
    }

    @Override
    public Optional<FileIdempotencyEntry> findIdempotency(
            UUID actorId,
            String operation,
            UUID key
    ) {
        return Optional.ofNullable(idempotency.get(actorId + ":" + operation + ":" + key));
    }

    @Override
    public void saveIntent(StoredFileObject file, FileIdempotencyEntry entry) {
        files.put(file.id(), file);
        idempotency.put(
                entry.actorId() + ":" + entry.operation() + ":" + entry.key(),
                entry);
    }

    @Override
    public boolean claimCompletion(FileIdempotencyEntry entry) {
        String key = entry.actorId() + ":" + entry.operation() + ":" + entry.key();
        return idempotency.putIfAbsent(key, entry) == null;
    }

    @Override
    public Optional<StoredFileObject> findFile(UUID fileId) {
        return Optional.ofNullable(files.get(fileId));
    }

    @Override
    public void updateStatus(
            UUID fileId,
            FileObjectStatus expected,
            FileObjectStatus next,
            String etag,
            String failureReason
    ) {
        StoredFileObject current = files.get(fileId);
        if (current.status() != expected) {
            throw new IllegalStateException("unexpected file status");
        }
        files.put(
                fileId,
                current.withStatus(next, etag, failureReason));
    }

    @Override
    public void linkReservationAttachment(UUID reservationId, UUID fileId) {
        linkedFiles.add(fileId);
    }
}

final class RecordingObjectStorage implements ObjectStoragePort {

    UploadVerificationResult verification = UploadVerificationResult.VERIFIED;
    UploadObjectRequest verifiedRequest;
    boolean failFirstVerification;
    int verificationAttempts;

    @Override
    public PreparedObjectUpload createUploadIntent(UploadObjectRequest request) {
        return new PreparedObjectUpload(
                "dev-inspection-quarantine",
                "dev-inspection-private",
                "tenant/" + FileObjectApplicationServiceTest.PROVIDER + "/reservation/"
                        + FileObjectApplicationServiceTest.RESERVATION + "/" + request.fileId(),
                new PresignedObjectIntent(
                        "PUT",
                        URI.create("https://storage.test/upload"),
                        Map.of("Content-Type", request.contentType()),
                        FileObjectApplicationServiceTest.NOW.plusSeconds(300)));
    }

    @Override
    public UploadVerificationResult verifyUpload(UploadObjectRequest request) {
        verificationAttempts++;
        if (failFirstVerification && verificationAttempts == 1) {
            throw new IllegalStateException("temporary storage failure");
        }
        verifiedRequest = request;
        return verification;
    }

    @Override
    public PresignedObjectIntent createDownloadIntent(
            com.lowaltitude.inspection.domain.files.DownloadObjectRequest request
    ) {
        throw new UnsupportedOperationException();
    }
}
