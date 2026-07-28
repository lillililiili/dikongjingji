package com.lowaltitude.inspection.application.serviceflow;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.application.reservation.AesGcmSensitiveDataCipher;
import com.lowaltitude.inspection.domain.files.BundleDownloadRequest;
import com.lowaltitude.inspection.domain.files.BundleObjectSource;
import com.lowaltitude.inspection.domain.files.DeliveryFileType;
import com.lowaltitude.inspection.domain.files.DeliveryIdentity;
import com.lowaltitude.inspection.domain.files.DeliveryObjectKeys;
import com.lowaltitude.inspection.domain.files.DownloadObjectRequest;
import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.domain.files.ObjectStoragePort;
import com.lowaltitude.inspection.domain.files.PresignedObjectIntent;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.transaction.annotation.Transactional;

/** Publishes immutable delivery results, protects downloads and records acceptance. */
public class DeliveryApplicationService {

    static final String PUBLISH_OPERATION = "inspection.delivery.publish";
    static final String ACCEPT_OPERATION = "inspection.delivery.accept";
    static final String DOWNLOAD_OPERATION = "inspection.delivery.download";
    static final String DOWNLOAD_ALL_OPERATION = "inspection.delivery.download-all";
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    private static final int MAX_ISSUE_SUMMARY = 20;
    private static final int MAX_NOTE_LENGTH = 500;

    private final PlanStore orderStore;
    private final DeliveryStore store;
    private final ObjectStoragePort storage;
    private final AesGcmSensitiveDataCipher cipher;
    private final Clock clock;

    public DeliveryApplicationService(
            PlanStore orderStore,
            DeliveryStore store,
            ObjectStoragePort storage,
            AesGcmSensitiveDataCipher cipher,
            Clock clock
    ) {
        this.orderStore = orderStore;
        this.store = store;
        this.storage = storage;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Transactional
    public DeliveryView publish(
            CurrentSubject subject,
            UUID serviceOrderId,
            UUID idempotencyKey,
            int expectedOrderVersion,
            PublishDeliveryCommand command,
            String requestId
    ) {
        requireProvider(subject);
        validatePublish(
                serviceOrderId,
                idempotencyKey,
                expectedOrderVersion,
                command,
                requestId);
        StoredServiceOrder order = orderStore.findOrder(serviceOrderId)
                .orElseThrow(PlanResourceNotFoundException::new);
        requireProviderOwns(order, subject);

        String requestDigest = digestPublish(command);
        Optional<DeliveryIdempotencyEntry> prior = store.findDeliveryIdempotency(
                subject.subjectId(),
                PUBLISH_OPERATION,
                idempotencyKey);
        if (prior.isPresent()) {
            return replayDelivery(prior.get(), requestDigest);
        }
        if (order.version() != expectedOrderVersion) {
            throw new PlanVersionConflictException();
        }
        if (order.status() != ServiceOrderStatus.IN_PROGRESS
                || order.progress().stream().noneMatch(
                        event -> event.eventCode() == OrderProgressCode.SERVICE_COMPLETED)
                || store.findDeliveryByOrder(serviceOrderId).isPresent()) {
            throw new DeliveryStateInvalidException();
        }

        UUID deliveryId = DeliveryIdentity.forServiceOrder(serviceOrderId);
        List<StoredDeliveryFile> files =
                validatedFiles(order, deliveryId, command.files(), command.towerCount());
        Instant now = clock.instant();
        List<IssueSummary> issues = command.issueSummary().stream()
                .map(item -> new IssueSummary(
                        item.severityCode().trim(),
                        item.severityName().trim(),
                        item.count()))
                .toList();
        StoredDelivery delivery = new StoredDelivery(
                deliveryId,
                serviceOrderId,
                DeliveryStatus.PUBLISHED,
                command.towerCount(),
                command.inspectedLengthMeters(),
                command.issueCount(),
                issues,
                files,
                now,
                null,
                0);
        StoredServiceOrder deliveredOrder = copyOrder(
                order,
                ServiceOrderStatus.DELIVERED,
                now,
                null,
                order.version() + 1);
        DeliveryIdempotencyEntry entry = new DeliveryIdempotencyEntry(
                subject.subjectId(),
                PUBLISH_OPERATION,
                idempotencyKey,
                requestDigest,
                delivery.id());
        DeliveryWriteResult result = store.publishDelivery(
                order,
                deliveredOrder,
                delivery,
                entry,
                subject,
                requestId.trim());
        if (result == DeliveryWriteResult.IDEMPOTENCY_EXISTS) {
            return replayDelivery(
                    store.findDeliveryIdempotency(
                                    subject.subjectId(),
                                    PUBLISH_OPERATION,
                                    idempotencyKey)
                            .orElseThrow(PlanIdempotencyConflictException::new),
                    requestDigest);
        }
        if (result == DeliveryWriteResult.VERSION_CONFLICT) {
            throw new PlanVersionConflictException();
        }
        if (result != DeliveryWriteResult.SAVED) {
            throw new DeliveryStateInvalidException();
        }
        return toView(delivery);
    }

    public DeliveryView read(CurrentSubject subject, UUID serviceOrderId) {
        VisibleDelivery visible = visibleDelivery(subject, serviceOrderId);
        return toView(visible.delivery());
    }

    @Transactional
    public DownloadIntentView download(
            CurrentSubject subject,
            UUID serviceOrderId,
            UUID fileId,
            UUID idempotencyKey
    ) {
        if (fileId == null || idempotencyKey == null) {
            throw new PlanValidationException();
        }
        VisibleDelivery visible = visibleDownload(subject, serviceOrderId);
        StoredDeliveryFile file = visible.delivery().files().stream()
                .filter(item -> item.fileId().equals(fileId))
                .findFirst()
                .orElseThrow(PlanResourceNotFoundException::new);
        String requestDigest = digest(List.of(serviceOrderId, fileId));
        claimDownload(
                subject,
                DOWNLOAD_OPERATION,
                idempotencyKey,
                requestDigest,
                file.fileId(),
                "FILE_OBJECT");
        PresignedObjectIntent intent = storage.createDownloadIntent(
                new DownloadObjectRequest(
                        file.bucket(),
                        file.objectKey(),
                        file.status(),
                        DOWNLOAD_TTL));
        return new DownloadIntentView(
                intent.method(),
                intent.url(),
                file.displayName(),
                intent.expiresAt());
    }

    @Transactional
    public DownloadIntentView downloadAll(
            CurrentSubject subject,
            UUID serviceOrderId,
            UUID idempotencyKey
    ) {
        if (idempotencyKey == null) {
            throw new PlanValidationException();
        }
        VisibleDelivery visible = visibleDownload(subject, serviceOrderId);
        StoredDelivery delivery = visible.delivery();
        String requestDigest = digest(List.of(serviceOrderId, delivery.id()));
        claimDownload(
                subject,
                DOWNLOAD_ALL_OPERATION,
                idempotencyKey,
                requestDigest,
                delivery.id(),
                "DELIVERY");
        String bucket = delivery.files().get(0).bucket();
        PresignedObjectIntent intent = storage.createBundleDownloadIntent(
                new BundleDownloadRequest(
                        bucket,
                        DeliveryObjectKeys.bundle(
                                visible.order().providerId(),
                                visible.order().id(),
                                delivery.id()),
                        delivery.files().stream()
                                .map(file -> new BundleObjectSource(
                                        file.bucket(),
                                        file.objectKey(),
                                        file.status(),
                                        file.displayName()))
                                .toList(),
                        DOWNLOAD_TTL));
        return new DownloadIntentView(
                intent.method(),
                intent.url(),
                visible.order().serviceOrderNo() + "-交付文件.zip",
                intent.expiresAt());
    }

    @Transactional
    public AcceptanceView accept(
            CurrentSubject subject,
            UUID serviceOrderId,
            UUID idempotencyKey,
            int expectedOrderVersion,
            AcceptDeliveryCommand command,
            String requestId
    ) {
        requireCustomerAcceptor(subject);
        validateAccept(
                serviceOrderId,
                idempotencyKey,
                expectedOrderVersion,
                command,
                requestId);
        StoredServiceOrder order = orderStore.findOrder(serviceOrderId)
                .orElseThrow(PlanResourceNotFoundException::new);
        if (!order.customerId().equals(subject.subjectId())) {
            throw new PlanResourceNotFoundException();
        }

        String requestDigest = digest(List.of(
                command.confirmed(),
                normalize(command.note())));
        Optional<DeliveryIdempotencyEntry> prior = store.findDeliveryIdempotency(
                subject.subjectId(),
                ACCEPT_OPERATION,
                idempotencyKey);
        if (prior.isPresent()) {
            return replayAcceptance(prior.get(), requestDigest);
        }
        if (order.version() != expectedOrderVersion) {
            throw new PlanVersionConflictException();
        }
        if (order.status() == ServiceOrderStatus.ACCEPTED) {
            return store.findAcceptanceByOrder(serviceOrderId)
                    .map(this::toView)
                    .orElseThrow(DeliveryStateInvalidException::new);
        }
        StoredDelivery delivery = store.findDeliveryByOrder(serviceOrderId)
                .orElseThrow(PlanResourceNotFoundException::new);
        if (order.status() != ServiceOrderStatus.DELIVERED
                || delivery.status() != DeliveryStatus.PUBLISHED) {
            throw new DeliveryStateInvalidException();
        }

        Instant now = clock.instant();
        StoredAcceptance acceptance = new StoredAcceptance(
                UUID.randomUUID(),
                serviceOrderId,
                delivery.id(),
                subject.subjectId(),
                now,
                encryptNote(command.note()),
                order.version() + 1);
        StoredServiceOrder acceptedOrder = copyOrder(
                order,
                ServiceOrderStatus.ACCEPTED,
                order.deliveredAt(),
                now,
                order.version() + 1);
        StoredDelivery acceptedDelivery = new StoredDelivery(
                delivery.id(),
                delivery.serviceOrderId(),
                DeliveryStatus.ACCEPTED,
                delivery.towerCount(),
                delivery.inspectedLengthMeters(),
                delivery.issueCount(),
                delivery.issueSummary(),
                delivery.files(),
                delivery.publishedAt(),
                now,
                delivery.version() + 1);
        DeliveryIdempotencyEntry entry = new DeliveryIdempotencyEntry(
                subject.subjectId(),
                ACCEPT_OPERATION,
                idempotencyKey,
                requestDigest,
                acceptance.id());
        DeliveryWriteResult result = store.acceptDelivery(
                order,
                acceptedOrder,
                delivery,
                acceptedDelivery,
                acceptance,
                entry,
                subject,
                requestId.trim());
        if (result == DeliveryWriteResult.IDEMPOTENCY_EXISTS) {
            return replayAcceptance(
                    store.findDeliveryIdempotency(
                                    subject.subjectId(),
                                    ACCEPT_OPERATION,
                                    idempotencyKey)
                            .orElseThrow(PlanIdempotencyConflictException::new),
                    requestDigest);
        }
        if (result == DeliveryWriteResult.VERSION_CONFLICT) {
            throw new PlanVersionConflictException();
        }
        if (result != DeliveryWriteResult.SAVED) {
            throw new DeliveryStateInvalidException();
        }
        return toView(acceptance);
    }

    private void claimDownload(
            CurrentSubject subject,
            String operation,
            UUID key,
            String digest,
            UUID resourceId,
            String resourceType
    ) {
        Optional<DeliveryIdempotencyEntry> prior =
                store.findDeliveryIdempotency(subject.subjectId(), operation, key);
        if (prior.isPresent()) {
            requireSameDigest(prior.get(), digest);
            return;
        }
        DeliveryIdempotencyEntry entry = new DeliveryIdempotencyEntry(
                subject.subjectId(),
                operation,
                key,
                digest,
                resourceId);
        if (!store.claimDeliveryIdempotency(entry, resourceType, 201)) {
            DeliveryIdempotencyEntry concurrent =
                    store.findDeliveryIdempotency(subject.subjectId(), operation, key)
                            .orElseThrow(PlanIdempotencyConflictException::new);
            requireSameDigest(concurrent, digest);
        }
    }

    private VisibleDelivery visibleDelivery(
            CurrentSubject subject,
            UUID serviceOrderId
    ) {
        if (subject == null || serviceOrderId == null) {
            throw new PlanResourceNotFoundException();
        }
        StoredServiceOrder order = orderStore.findOrder(serviceOrderId)
                .orElseThrow(PlanResourceNotFoundException::new);
        boolean own = subject.roles().contains("CUSTOMER")
                && subject.permissions().contains("inspection.order.read.own")
                && order.customerId().equals(subject.subjectId());
        boolean platform = subject.roles().contains("PLATFORM_OPERATOR")
                && subject.permissions().contains("inspection.order.read.all");
        if (!own && !platform) {
            throw new PlanResourceNotFoundException();
        }
        StoredDelivery delivery = store.findDeliveryByOrder(serviceOrderId)
                .orElseThrow(PlanResourceNotFoundException::new);
        return new VisibleDelivery(order, delivery);
    }

    private VisibleDelivery visibleDownload(
            CurrentSubject subject,
            UUID serviceOrderId
    ) {
        VisibleDelivery visible = visibleDelivery(subject, serviceOrderId);
        boolean own = subject.roles().contains("CUSTOMER")
                && subject.permissions().contains("inspection.delivery.download.own");
        boolean platform = subject.roles().contains("PLATFORM_OPERATOR")
                && subject.permissions().contains("inspection.delivery.download.all");
        if (!own && !platform) {
            throw new PlanResourceNotFoundException();
        }
        return visible;
    }

    private List<StoredDeliveryFile> validatedFiles(
            StoredServiceOrder order,
            UUID deliveryId,
            List<DeliveryFileInput> inputs,
            int towerCount
    ) {
        if (inputs == null || inputs.size() != DeliveryFileType.values().length) {
            throw new DeliveryFilesInvalidException();
        }
        Set<UUID> ids = new HashSet<>();
        EnumSet<DeliveryFileType> types = EnumSet.noneOf(DeliveryFileType.class);
        Set<String> archiveNames = new HashSet<>();
        List<StoredDeliveryFile> result = inputs.stream().map(input -> {
            String displayName = input == null ? null : normalize(input.displayName());
            if (input == null
                    || input.fileId() == null
                    || input.type() == null
                    || !validText(input.displayName(), 1, 120)
                    || displayName.contains("/")
                    || displayName.contains("\\")
                    || ".".equals(displayName)
                    || "..".equals(displayName)
                    || !archiveNames.add(displayName.toLowerCase(Locale.ROOT))
                    || !ids.add(input.fileId())
                    || !types.add(input.type())
                    || input.pointCount() != null && input.pointCount() < 0) {
                throw new DeliveryFilesInvalidException();
            }
            DeliveryFileObject file = store.findFileObject(input.fileId())
                    .orElseThrow(DeliveryFilesInvalidException::new);
            String expectedKey = DeliveryObjectKeys.artifact(
                    order.providerId(),
                    order.id(),
                    deliveryId,
                    input.fileId());
            if (!file.serviceOrderId().equals(order.id())
                    || !file.providerId().equals(order.providerId())
                    || file.type() != input.type()
                    || !file.contentType().equalsIgnoreCase(input.type().mimeType())
                    || file.sizeBytes() <= 0
                    || file.status() != FileObjectStatus.READY
                    || !file.objectKey().equals(expectedKey)
                    || input.type() == DeliveryFileType.POINT_KML
                            && !Integer.valueOf(towerCount).equals(input.pointCount())
                    || input.type() != DeliveryFileType.POINT_KML
                            && input.pointCount() != null) {
                throw new DeliveryFilesInvalidException();
            }
            return new StoredDeliveryFile(
                    file.id(),
                    input.type(),
                    displayName,
                    input.pointCount(),
                    file.contentType(),
                    file.sizeBytes(),
                    file.status(),
                    file.bucket(),
                    file.objectKey());
        }).toList();
        if (!types.equals(EnumSet.allOf(DeliveryFileType.class))) {
            throw new DeliveryFilesInvalidException();
        }
        return result;
    }

    private static void validatePublish(
            UUID serviceOrderId,
            UUID idempotencyKey,
            int expectedVersion,
            PublishDeliveryCommand command,
            String requestId
    ) {
        if (serviceOrderId == null
                || idempotencyKey == null
                || expectedVersion < 0
                || command == null
                || command.towerCount() < 0
                || command.inspectedLengthMeters() < 0
                || command.issueCount() < 0
                || !validText(requestId, 1, 80)
                || !validIssues(command.issueSummary(), command.issueCount())) {
            throw new DeliveryFilesInvalidException();
        }
    }

    private static boolean validIssues(
            List<IssueSummaryInput> issues,
            int issueCount
    ) {
        if (issues == null || issues.size() > MAX_ISSUE_SUMMARY) {
            return false;
        }
        Set<String> codes = new HashSet<>();
        long total = 0;
        for (IssueSummaryInput issue : issues) {
            if (issue == null
                    || !validText(issue.severityCode(), 1, 32)
                    || !validText(issue.severityName(), 1, 40)
                    || issue.count() < 0
                    || !codes.add(issue.severityCode().trim())) {
                return false;
            }
            total += issue.count();
        }
        return total == issueCount;
    }

    private static void validateAccept(
            UUID serviceOrderId,
            UUID idempotencyKey,
            int expectedVersion,
            AcceptDeliveryCommand command,
            String requestId
    ) {
        if (serviceOrderId == null
                || idempotencyKey == null
                || expectedVersion < 0
                || command == null
                || !command.confirmed()
                || command.note() != null && command.note().length() > MAX_NOTE_LENGTH
                || !validText(requestId, 1, 80)) {
            throw new PlanValidationException();
        }
    }

    private static void requireProvider(CurrentSubject subject) {
        if (subject == null
                || !subject.roles().contains("PROVIDER_OPERATOR")
                || !subject.permissions().contains("inspection.delivery.publish.assigned")
                || subject.organizationId() == null) {
            throw new PlanForbiddenException();
        }
    }

    private static void requireProviderOwns(
            StoredServiceOrder order,
            CurrentSubject subject
    ) {
        if (!order.providerId().equals(subject.organizationId())) {
            throw new PlanResourceNotFoundException();
        }
    }

    private static void requireCustomerAcceptor(CurrentSubject subject) {
        if (subject == null
                || !subject.roles().contains("CUSTOMER")
                || !subject.permissions().contains("inspection.acceptance.create.own")) {
            throw new PlanForbiddenException();
        }
    }

    private DeliveryView replayDelivery(
            DeliveryIdempotencyEntry prior,
            String requestDigest
    ) {
        requireSameDigest(prior, requestDigest);
        return toView(store.findDelivery(prior.resourceId())
                .orElseThrow(PlanResourceNotFoundException::new));
    }

    private AcceptanceView replayAcceptance(
            DeliveryIdempotencyEntry prior,
            String requestDigest
    ) {
        requireSameDigest(prior, requestDigest);
        return toView(store.findAcceptance(prior.resourceId())
                .orElseThrow(PlanResourceNotFoundException::new));
    }

    private static void requireSameDigest(
            DeliveryIdempotencyEntry entry,
            String digest
    ) {
        if (!entry.requestDigest().equals(digest)) {
            throw new PlanIdempotencyConflictException();
        }
    }

    private String encryptNote(String note) {
        String normalized = normalize(note);
        return normalized == null ? null : cipher.encrypt(normalized);
    }

    private static StoredServiceOrder copyOrder(
            StoredServiceOrder source,
            ServiceOrderStatus status,
            Instant deliveredAt,
            Instant acceptedAt,
            int version
    ) {
        return new StoredServiceOrder(
                source.id(),
                source.serviceOrderNo(),
                source.reservationId(),
                source.planId(),
                source.customerId(),
                source.providerId(),
                status,
                source.serviceSnapshot(),
                source.plannedStartAt(),
                source.assignments(),
                source.progress(),
                source.createdAt(),
                deliveredAt,
                acceptedAt,
                version);
    }

    private static DeliveryView toView(StoredDelivery delivery) {
        return new DeliveryView(
                delivery.id(),
                delivery.serviceOrderId(),
                delivery.status(),
                delivery.towerCount(),
                delivery.inspectedLengthMeters(),
                delivery.issueCount(),
                delivery.issueSummary(),
                delivery.files().stream()
                        .map(file -> new DeliveryFile(
                                file.fileId(),
                                file.type(),
                                file.displayName(),
                                file.pointCount(),
                                file.contentType(),
                                file.sizeBytes(),
                                file.status()))
                        .toList(),
                delivery.publishedAt(),
                delivery.acceptedAt(),
                delivery.version());
    }

    private AcceptanceView toView(StoredAcceptance acceptance) {
        return new AcceptanceView(
                acceptance.id(),
                acceptance.serviceOrderId(),
                acceptance.deliveryId(),
                acceptance.acceptedBy(),
                acceptance.acceptedAt(),
                ServiceOrderStatus.ACCEPTED,
                acceptance.orderVersion());
    }

    private static String digestPublish(PublishDeliveryCommand command) {
        java.util.ArrayList<Object> values = new java.util.ArrayList<>();
        values.add(command.towerCount());
        values.add(command.inspectedLengthMeters());
        values.add(command.issueCount());
        command.issueSummary().forEach(item -> {
            values.add(item.severityCode().trim());
            values.add(item.severityName().trim());
            values.add(item.count());
        });
        command.files().forEach(file -> {
            values.add(file.fileId());
            values.add(file.type());
            values.add(file.displayName().trim());
            values.add(file.pointCount());
        });
        return digest(values);
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

    private static boolean validText(String value, int min, int max) {
        if (value == null) {
            return false;
        }
        int length = value.trim().length();
        return length >= min && length <= max;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

record PublishDeliveryCommand(
        int towerCount,
        long inspectedLengthMeters,
        int issueCount,
        List<IssueSummaryInput> issueSummary,
        List<DeliveryFileInput> files
) {
}

record IssueSummaryInput(String severityCode, String severityName, int count) {
}

record DeliveryFileInput(
        UUID fileId,
        DeliveryFileType type,
        String displayName,
        Integer pointCount
) {
}

record AcceptDeliveryCommand(boolean confirmed, String note) {
}

enum DeliveryStatus {
    PUBLISHED,
    ACCEPTED
}

record IssueSummary(String severityCode, String severityName, int count) {
}

record DeliveryFile(
        UUID fileId,
        DeliveryFileType type,
        String displayName,
        Integer pointCount,
        String contentType,
        long sizeBytes,
        FileObjectStatus status
) {
}

record DeliveryView(
        UUID id,
        UUID serviceOrderId,
        DeliveryStatus status,
        int towerCount,
        long inspectedLengthMeters,
        int issueCount,
        List<IssueSummary> issueSummary,
        List<DeliveryFile> files,
        Instant publishedAt,
        Instant acceptedAt,
        int version
) {
}

record DownloadIntentView(
        String method,
        URI downloadUrl,
        String suggestedFileName,
        Instant expiresAt
) {
}

record AcceptanceView(
        UUID id,
        UUID serviceOrderId,
        UUID deliveryId,
        UUID acceptedBy,
        Instant acceptedAt,
        ServiceOrderStatus orderStatus,
        int orderVersion
) {
}

record StoredDelivery(
        UUID id,
        UUID serviceOrderId,
        DeliveryStatus status,
        int towerCount,
        long inspectedLengthMeters,
        int issueCount,
        List<IssueSummary> issueSummary,
        List<StoredDeliveryFile> files,
        Instant publishedAt,
        Instant acceptedAt,
        int version
) {
}

record StoredDeliveryFile(
        UUID fileId,
        DeliveryFileType type,
        String displayName,
        Integer pointCount,
        String contentType,
        long sizeBytes,
        FileObjectStatus status,
        String bucket,
        String objectKey
) {
}

record DeliveryFileObject(
        UUID id,
        UUID serviceOrderId,
        UUID providerId,
        DeliveryFileType type,
        String contentType,
        long sizeBytes,
        String bucket,
        String objectKey,
        FileObjectStatus status
) {
}

record StoredAcceptance(
        UUID id,
        UUID serviceOrderId,
        UUID deliveryId,
        UUID acceptedBy,
        Instant acceptedAt,
        String noteCiphertext,
        int orderVersion
) {
}

record DeliveryIdempotencyEntry(
        UUID actorId,
        String operation,
        UUID key,
        String requestDigest,
        UUID resourceId
) {
}

enum DeliveryWriteResult {
    SAVED,
    IDEMPOTENCY_EXISTS,
    VERSION_CONFLICT,
    STATE_CONFLICT
}

interface DeliveryStore {

    Optional<StoredDelivery> findDelivery(UUID deliveryId);

    Optional<StoredDelivery> findDeliveryByOrder(UUID serviceOrderId);

    Optional<DeliveryFileObject> findFileObject(UUID fileId);

    Optional<StoredAcceptance> findAcceptance(UUID acceptanceId);

    Optional<StoredAcceptance> findAcceptanceByOrder(UUID serviceOrderId);

    Optional<DeliveryIdempotencyEntry> findDeliveryIdempotency(
            UUID actorId,
            String operation,
            UUID key);

    DeliveryWriteResult publishDelivery(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder deliveredOrder,
            StoredDelivery delivery,
            DeliveryIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId);

    DeliveryWriteResult acceptDelivery(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder acceptedOrder,
            StoredDelivery expectedDelivery,
            StoredDelivery acceptedDelivery,
            StoredAcceptance acceptance,
            DeliveryIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId);

    boolean claimDeliveryIdempotency(
            DeliveryIdempotencyEntry entry,
            String resourceType,
            int responseStatus);
}

record VisibleDelivery(StoredServiceOrder order, StoredDelivery delivery) {
}

final class DeliveryStateInvalidException extends PlanFlowException {
    DeliveryStateInvalidException() {
        super(PlanFlowError.DELIVERY_STATE_INVALID);
    }
}

final class DeliveryFilesInvalidException extends PlanFlowException {
    DeliveryFilesInvalidException() {
        super(PlanFlowError.DELIVERY_FILES_INVALID);
    }
}
