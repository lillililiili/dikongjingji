package com.lowaltitude.inspection.application.reservation;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.domain.files.FilePurpose;
import com.lowaltitude.inspection.domain.files.ObjectStoragePort;
import com.lowaltitude.inspection.domain.files.PreparedObjectUpload;
import com.lowaltitude.inspection.domain.files.UploadObjectRequest;
import com.lowaltitude.inspection.domain.files.UploadVerificationResult;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.transaction.annotation.Transactional;

public class FileObjectApplicationService {

    private static final String RESERVATION_PURPOSE = "RESERVATION_ATTACHMENT";
    private static final String CREATE_OPERATION = "inspection.file.upload-intent";
    private static final String COMPLETE_OPERATION = "inspection.file.complete";
    private static final int MAX_RESERVATION_FILES = 9;
    private static final long MAX_FILE_SIZE_BYTES = 1_073_741_824L;
    private static final Set<String> SUPPORTED_RESERVATION_MIMES = Set.of(
            "image/jpeg",
            "image/png",
            "application/pdf");

    private final FileObjectStore store;
    private final ObjectStoragePort storage;
    private final Clock clock;

    public FileObjectApplicationService(
            FileObjectStore store,
            ObjectStoragePort storage,
            Clock clock
    ) {
        this.store = store;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public UploadIntentView createUploadIntent(
            CurrentSubject subject,
            UUID idempotencyKey,
            CreateUploadIntentCommand command
    ) {
        requireCustomerUpload(subject);
        validateCreate(idempotencyKey, command);
        String digest = digest(java.util.Arrays.asList(
                command.purpose(),
                command.ownerId(),
                command.deliveryFileType(),
                command.originalName(),
                command.contentType(),
                command.sizeBytes(),
                command.sha256()));
        Optional<FileIdempotencyEntry> prior =
                store.findIdempotency(subject.subjectId(), CREATE_OPERATION, idempotencyKey);
        if (prior.isPresent()) {
            requireSameDigest(prior.get(), digest);
            return reissueIntent(visibleFile(subject, prior.get().fileId()));
        }

        ReservationFileOwner owner = store.findReservationOwner(command.ownerId())
                .filter(value -> value.customerId().equals(subject.subjectId()))
                .orElseThrow(ReservationNotFoundException::new);
        if (store.countReservationFiles(owner.reservationId()) >= MAX_RESERVATION_FILES) {
            throw new FileCountExceededException();
        }

        UUID fileId = UUID.randomUUID();
        UploadObjectRequest storageRequest = UploadObjectRequest.reservationAttachment(
                        owner.providerId(),
                        owner.reservationId(),
                        fileId,
                        command.originalName().trim(),
                        command.contentType().toLowerCase(Locale.ROOT),
                        command.sizeBytes())
                .withSha256(command.sha256());
        PreparedObjectUpload prepared = storage.createUploadIntent(storageRequest);
        StoredFileObject file = new StoredFileObject(
                fileId,
                FilePurpose.RESERVATION_ATTACHMENT,
                owner.reservationId(),
                owner.providerId(),
                prepared.finalBucket(),
                prepared.objectKey(),
                command.originalName().trim(),
                command.contentType().toLowerCase(Locale.ROOT),
                extension(command.originalName()),
                command.sizeBytes(),
                command.sha256(),
                null,
                FileObjectStatus.PENDING_UPLOAD,
                null,
                subject.subjectId(),
                clock.instant(),
                0);
        try {
            store.saveIntent(
                    file,
                    new FileIdempotencyEntry(
                            subject.subjectId(),
                            CREATE_OPERATION,
                            idempotencyKey,
                            digest,
                            fileId));
            store.linkReservationAttachment(owner.reservationId(), fileId);
        } catch (DuplicateIdempotencyKeyException conflict) {
            FileIdempotencyEntry concurrent =
                    store.findIdempotency(subject.subjectId(), CREATE_OPERATION, idempotencyKey)
                            .orElseThrow(() -> conflict);
            requireSameDigest(concurrent, digest);
            return reissueIntent(visibleFile(subject, concurrent.fileId()));
        }
        return UploadIntentView.from(fileId, prepared);
    }

    public FileObjectView completeUpload(
            CurrentSubject subject,
            UUID idempotencyKey,
            UUID fileId,
            CompleteUploadCommand command
    ) {
        requireCustomerUpload(subject);
        validateComplete(idempotencyKey, fileId, command);
        StoredFileObject file = visibleFile(subject, fileId);
        String digest = digest(java.util.Arrays.asList(
                fileId,
                command.etag(),
                command.sizeBytes(),
                command.sha256()));
        Optional<FileIdempotencyEntry> prior =
                store.findIdempotency(subject.subjectId(), COMPLETE_OPERATION, idempotencyKey);
        if (prior.isPresent()) {
            requireSameDigest(prior.get(), digest);
            return resumeCompletion(subject, prior.get().fileId(), command);
        }
        if (!store.claimCompletion(new FileIdempotencyEntry(
                subject.subjectId(),
                COMPLETE_OPERATION,
                idempotencyKey,
                digest,
                fileId))) {
            FileIdempotencyEntry concurrent =
                    store.findIdempotency(subject.subjectId(), COMPLETE_OPERATION, idempotencyKey)
                            .orElseThrow(DuplicateIdempotencyKeyException::new);
            requireSameDigest(concurrent, digest);
            return resumeCompletion(subject, concurrent.fileId(), command);
        }

        return resumeCompletion(subject, fileId, command);
    }

    private FileObjectView resumeCompletion(
            CurrentSubject subject,
            UUID fileId,
            CompleteUploadCommand command
    ) {
        StoredFileObject file = visibleFile(subject, fileId);
        if (file.status() == FileObjectStatus.READY
                || file.status() == FileObjectStatus.REJECTED) {
            return FileObjectView.from(file);
        }
        if (file.sizeBytes() != command.sizeBytes()
                || !file.sha256().equals(command.sha256())) {
            throw new FileUploadConflictException();
        }

        if (file.status() == FileObjectStatus.PENDING_UPLOAD) {
            store.updateStatus(
                    fileId,
                    FileObjectStatus.PENDING_UPLOAD,
                    FileObjectStatus.UPLOADED,
                    command.etag().trim(),
                    null);
            file = visibleFile(subject, fileId);
        }
        if (file.status() == FileObjectStatus.UPLOADED) {
            store.updateStatus(
                    fileId,
                    FileObjectStatus.UPLOADED,
                    FileObjectStatus.SCANNING,
                    command.etag().trim(),
                    null);
            file = visibleFile(subject, fileId);
        }
        if (file.status() != FileObjectStatus.SCANNING) {
            throw new FileUploadConflictException();
        }

        UploadVerificationResult verification =
                storage.verifyUpload(file.toStorageRequest());
        if (verification == UploadVerificationResult.REJECTED) {
            store.updateStatus(
                    fileId,
                    FileObjectStatus.SCANNING,
                    FileObjectStatus.REJECTED,
                    command.etag().trim(),
                    "文件内容、大小或类型校验失败");
            return FileObjectView.from(visibleFile(subject, fileId));
        }

        store.updateStatus(
                fileId,
                FileObjectStatus.SCANNING,
                FileObjectStatus.READY,
                command.etag().trim(),
                null);
        return FileObjectView.from(visibleFile(subject, fileId));
    }

    private UploadIntentView reissueIntent(StoredFileObject file) {
        if (file.status() != FileObjectStatus.PENDING_UPLOAD) {
            throw new FileUploadConflictException();
        }
        return UploadIntentView.from(
                file.id(),
                storage.createUploadIntent(file.toStorageRequest()));
    }

    private StoredFileObject visibleFile(CurrentSubject subject, UUID fileId) {
        StoredFileObject file = store.findFile(fileId)
                .orElseThrow(ReservationNotFoundException::new);
        ReservationFileOwner owner = store.findReservationOwner(file.ownerId())
                .orElseThrow(ReservationNotFoundException::new);
        if (!file.uploadedBy().equals(subject.subjectId())
                || !owner.customerId().equals(subject.subjectId())) {
            throw new ReservationNotFoundException();
        }
        return file;
    }

    private static void requireCustomerUpload(CurrentSubject subject) {
        if (subject == null
                || !subject.roles().contains("CUSTOMER")
                || !subject.permissions().contains("inspection.reservation.create")) {
            throw new FileAccessDeniedException();
        }
    }

    private static void validateCreate(
            UUID idempotencyKey,
            CreateUploadIntentCommand command
    ) {
        if (idempotencyKey == null
                || command == null
                || !RESERVATION_PURPOSE.equals(command.purpose())
                || command.ownerId() == null
                || command.deliveryFileType() != null
                || !validText(command.originalName(), 1, 180)
                || !validText(command.contentType(), 3, 120)
                || command.sha256() == null
                || !command.sha256().matches("^[a-f0-9]{64}$")) {
            throw new FileUploadValidationException();
        }
        if (command.sizeBytes() <= 0 || command.sizeBytes() > MAX_FILE_SIZE_BYTES) {
            throw new FileTooLargeException();
        }
        if (!SUPPORTED_RESERVATION_MIMES.contains(
                        command.contentType().toLowerCase(Locale.ROOT))
                || !extensionMatchesMime(
                        extension(command.originalName()),
                        command.contentType())) {
            throw new FileTypeUnsupportedException();
        }
    }

    private static void validateComplete(
            UUID idempotencyKey,
            UUID fileId,
            CompleteUploadCommand command
    ) {
        if (idempotencyKey == null
                || fileId == null
                || command == null
                || !validText(command.etag(), 1, 160)
                || command.sha256() == null
                || !command.sha256().matches("^[a-f0-9]{64}$")) {
            throw new FileUploadValidationException();
        }
        if (command.sizeBytes() <= 0 || command.sizeBytes() > MAX_FILE_SIZE_BYTES) {
            throw new FileTooLargeException();
        }
    }

    private static boolean extensionMatchesMime(String extension, String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> extension.equals("jpg") || extension.equals("jpeg");
            case "image/png" -> extension.equals("png");
            case "application/pdf" -> extension.equals("pdf");
            default -> false;
        };
    }

    private static String extension(String originalName) {
        int separator = originalName.lastIndexOf('.');
        if (separator < 0 || separator == originalName.length() - 1) {
            return "";
        }
        return originalName.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean validText(String value, int min, int max) {
        if (value == null) {
            return false;
        }
        int length = value.trim().length();
        return length >= min && length <= max;
    }

    private static void requireSameDigest(FileIdempotencyEntry entry, String digest) {
        if (!entry.requestDigest().equals(digest)) {
            throw new IdempotencyConflictException();
        }
    }

    private static String digest(Collection<?> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

record CreateUploadIntentCommand(
        String purpose,
        UUID ownerId,
        String deliveryFileType,
        String originalName,
        String contentType,
        long sizeBytes,
        String sha256
) {
}

record CompleteUploadCommand(String etag, long sizeBytes, String sha256) {
}

record UploadIntentView(
        UUID fileId,
        String method,
        URI uploadUrl,
        Map<String, String> requiredHeaders,
        Instant expiresAt
) {

    static UploadIntentView from(UUID fileId, PreparedObjectUpload prepared) {
        return new UploadIntentView(
                fileId,
                prepared.intent().method(),
                prepared.intent().url(),
                prepared.intent().requiredHeaders(),
                prepared.intent().expiresAt());
    }
}

record FileObjectView(
        UUID id,
        String originalName,
        String contentType,
        long sizeBytes,
        FileObjectStatus status,
        String scanFailureReason
) {

    static FileObjectView from(StoredFileObject file) {
        return new FileObjectView(
                file.id(),
                file.originalName(),
                file.contentType(),
                file.sizeBytes(),
                file.status(),
                file.failureReason());
    }
}

record ReservationFileOwner(UUID reservationId, UUID customerId, UUID providerId) {
}

record FileIdempotencyEntry(
        UUID actorId,
        String operation,
        UUID key,
        String requestDigest,
        UUID fileId
) {
}

record StoredFileObject(
        UUID id,
        FilePurpose purpose,
        UUID ownerId,
        UUID providerId,
        String bucket,
        String objectKey,
        String originalName,
        String contentType,
        String extension,
        long sizeBytes,
        String sha256,
        String etag,
        FileObjectStatus status,
        String failureReason,
        UUID uploadedBy,
        Instant createdAt,
        int version
) {

    StoredFileObject withStatus(
            FileObjectStatus next,
            String nextEtag,
            String nextFailureReason
    ) {
        return new StoredFileObject(
                id,
                purpose,
                ownerId,
                providerId,
                bucket,
                objectKey,
                originalName,
                contentType,
                extension,
                sizeBytes,
                sha256,
                nextEtag,
                next,
                nextFailureReason,
                uploadedBy,
                createdAt,
                version + 1);
    }

    UploadObjectRequest toStorageRequest() {
        if (purpose != FilePurpose.RESERVATION_ATTACHMENT) {
            throw new IllegalStateException("unsupported file purpose");
        }
        return UploadObjectRequest.reservationAttachment(
                        providerId,
                        ownerId,
                        id,
                        originalName,
                        contentType,
                        sizeBytes)
                .withSha256(sha256);
    }
}

interface FileObjectStore {

    Optional<ReservationFileOwner> findReservationOwner(UUID reservationId);

    int countReservationFiles(UUID reservationId);

    Optional<FileIdempotencyEntry> findIdempotency(
            UUID actorId,
            String operation,
            UUID key
    );

    void saveIntent(StoredFileObject file, FileIdempotencyEntry entry);

    boolean claimCompletion(FileIdempotencyEntry entry);

    Optional<StoredFileObject> findFile(UUID fileId);

    void updateStatus(
            UUID fileId,
            FileObjectStatus expected,
            FileObjectStatus next,
            String etag,
            String failureReason
    );

    void linkReservationAttachment(UUID reservationId, UUID fileId);
}
