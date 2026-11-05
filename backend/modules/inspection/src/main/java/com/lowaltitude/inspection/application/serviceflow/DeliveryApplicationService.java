package com.lowaltitude.inspection.application.serviceflow;

import java.math.BigDecimal;
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
import java.util.Objects;
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
    private static final int MAX_METRICS = 12;
    private static final BigDecimal MAX_METRIC_VALUE = new BigDecimal("999999999999999");
    private static final int MAX_METRIC_SCALE = 6;
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
            UUID visitId,
            UUID idempotencyKey,
            int expectedVisitVersion,
            PublishDeliveryCommand command,
            String requestId
    ) {
        requireProvider(subject);
        validatePublish(
                serviceOrderId,
                visitId,
                idempotencyKey,
                expectedVisitVersion,
                command,
                requestId);
        orderStore.lockOrder(serviceOrderId);
        StoredServiceOrder order = orderStore.findOrder(serviceOrderId)
                .orElseThrow(PlanResourceNotFoundException::new);
        requireProviderOwns(order, subject);
        StoredServiceVisit visit =
                order.visit(visitId).orElseThrow(PlanResourceNotFoundException::new);

        String requestDigest = digestPublish(visitId, command);
        Optional<DeliveryIdempotencyEntry> prior = store.findDeliveryIdempotency(
                subject.subjectId(),
                PUBLISH_OPERATION,
                idempotencyKey);
        if (prior.isPresent()) {
            return replayDelivery(prior.get(), requestDigest);
        }
        if (visit.version() != expectedVisitVersion) {
            throw new PlanVersionConflictException();
        }
        if (visit.status() != ServiceVisitStatus.IN_PROGRESS
                || !visit.serviceCompleted()
                || store.findDeliveryByVisit(visitId).isPresent()) {
            throw new DeliveryStateInvalidException();
        }

        UUID deliveryId = DeliveryIdentity.forVisit(visitId);
        List<StoredDeliveryFile> files = validatedFiles(
                order,
                visit,
                deliveryId,
                command.files(),
                requiredFileTypes(order),
                command.towerCount());
        Instant now = clock.instant();
        List<IssueSummary> issues = command.issueSummary().stream()
                .map(item -> new IssueSummary(
                        item.groupCode().trim(),
                        item.groupName().trim(),
                        item.count()))
                .toList();
        StoredDelivery delivery = new StoredDelivery(
                deliveryId,
                serviceOrderId,
                visitId,
                DeliveryStatus.PUBLISHED,
                command.towerCount(),
                command.inspectedLengthMeters(),
                command.metrics().stream()
                        .map(item -> new DeliveryMetric(
                                item.code().trim(),
                                item.label().trim(),
                                item.value(),
                                item.unit().trim()))
                        .toList(),
                command.issueCount(),
                command.issueGroupBy(),
                issues,
                files,
                now,
                null,
                0);
        StoredServiceVisit deliveredVisit = visit.delivered(now);
        ServiceOrderStatus nextOrderStatus = ServiceOrderProgression.derive(
                ServiceOrderProgression.replace(order.visits(), deliveredVisit));
        DeliveryIdempotencyEntry entry = new DeliveryIdempotencyEntry(
                subject.subjectId(),
                PUBLISH_OPERATION,
                idempotencyKey,
                requestDigest,
                delivery.id());
        DeliveryWriteResult result = store.publishDelivery(
                order,
                nextOrderStatus,
                visit,
                deliveredVisit,
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

    public DeliveryView read(CurrentSubject subject, UUID serviceOrderId, UUID visitId) {
        VisibleDelivery visible = visibleDelivery(subject, serviceOrderId, visitId);
        return toView(visible.delivery());
    }

    private String acceptedByName(StoredDelivery delivery) {
        return delivery.acceptedAt() == null
                ? null
                : store.findAcceptanceByVisit(delivery.visitId())
                        .map(StoredAcceptance::acceptedByName)
                        .orElse(null);
    }

    @Transactional
    public DownloadIntentView download(
            CurrentSubject subject,
            UUID serviceOrderId,
            UUID visitId,
            UUID fileId,
            UUID idempotencyKey
    ) {
        if (fileId == null || idempotencyKey == null) {
            throw new PlanValidationException();
        }
        VisibleDelivery visible = visibleDownload(subject, serviceOrderId, visitId);
        StoredDeliveryFile file = visible.delivery().files().stream()
                .filter(item -> item.fileId().equals(fileId))
                .findFirst()
                .orElseThrow(PlanResourceNotFoundException::new);
        String requestDigest = digest(List.of(serviceOrderId, visitId, fileId));
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
            UUID visitId,
            UUID idempotencyKey
    ) {
        if (idempotencyKey == null) {
            throw new PlanValidationException();
        }
        VisibleDelivery visible = visibleDownload(subject, serviceOrderId, visitId);
        StoredDelivery delivery = visible.delivery();
        String requestDigest = digest(List.of(serviceOrderId, visitId, delivery.id()));
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
                bundleFileName(visible.order(), visitId),
                intent.expiresAt());
    }

    @Transactional
    public AcceptanceView accept(
            CurrentSubject subject,
            UUID serviceOrderId,
            UUID visitId,
            UUID idempotencyKey,
            int expectedVisitVersion,
            AcceptDeliveryCommand command,
            String requestId
    ) {
        requireCustomerAcceptor(subject);
        validateAccept(
                serviceOrderId,
                visitId,
                idempotencyKey,
                expectedVisitVersion,
                command,
                requestId);
        orderStore.lockOrder(serviceOrderId);
        StoredServiceOrder order = orderStore.findOrder(serviceOrderId)
                .orElseThrow(PlanResourceNotFoundException::new);
        if (!order.customerId().equals(subject.subjectId())) {
            throw new PlanResourceNotFoundException();
        }
        StoredServiceVisit visit =
                order.visit(visitId).orElseThrow(PlanResourceNotFoundException::new);

        String requestDigest = digest(java.util.Arrays.asList(
                visitId,
                command.confirmed(),
                normalize(command.note())));
        Optional<DeliveryIdempotencyEntry> prior = store.findDeliveryIdempotency(
                subject.subjectId(),
                ACCEPT_OPERATION,
                idempotencyKey);
        if (prior.isPresent()) {
            return replayAcceptance(prior.get(), requestDigest);
        }
        if (visit.version() != expectedVisitVersion) {
            throw new PlanVersionConflictException();
        }
        if (visit.status() == ServiceVisitStatus.ACCEPTED) {
            return store.findAcceptanceByVisit(visitId)
                    .map(this::toView)
                    .orElseThrow(DeliveryStateInvalidException::new);
        }
        StoredDelivery delivery = store.findDeliveryByVisit(visitId)
                .orElseThrow(PlanResourceNotFoundException::new);
        if (visit.status() != ServiceVisitStatus.DELIVERED
                || delivery.status() != DeliveryStatus.PUBLISHED) {
            throw new DeliveryStateInvalidException();
        }

        Instant now = clock.instant();
        StoredServiceVisit acceptedVisit = visit.accepted(now);
        ServiceOrderStatus nextOrderStatus = ServiceOrderProgression.derive(
                ServiceOrderProgression.replace(order.visits(), acceptedVisit));
        StoredAcceptance acceptance = new StoredAcceptance(
                UUID.randomUUID(),
                serviceOrderId,
                visitId,
                delivery.id(),
                subject.subjectId(),
                orderStore.findDisplayName(subject.subjectId()).orElse(null),
                now,
                encryptNote(command.note()),
                acceptedVisit.version(),
                nextOrderStatus);
        StoredDelivery acceptedDelivery = new StoredDelivery(
                delivery.id(),
                delivery.serviceOrderId(),
                delivery.visitId(),
                DeliveryStatus.ACCEPTED,
                delivery.towerCount(),
                delivery.inspectedLengthMeters(),
                delivery.metrics(),
                delivery.issueCount(),
                delivery.issueGroupBy(),
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
                nextOrderStatus,
                visit,
                acceptedVisit,
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
            UUID serviceOrderId,
            UUID visitId
    ) {
        if (subject == null || serviceOrderId == null || visitId == null) {
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
        order.visit(visitId).orElseThrow(PlanResourceNotFoundException::new);
        StoredDelivery delivery = store.findDeliveryByVisit(visitId)
                .orElseThrow(PlanResourceNotFoundException::new);
        return new VisibleDelivery(order, delivery);
    }

    private VisibleDelivery visibleDownload(
            CurrentSubject subject,
            UUID serviceOrderId,
            UUID visitId
    ) {
        VisibleDelivery visible = visibleDelivery(subject, serviceOrderId, visitId);
        boolean own = subject.roles().contains("CUSTOMER")
                && subject.permissions().contains("inspection.delivery.download.own");
        boolean platform = subject.roles().contains("PLATFORM_OPERATOR")
                && subject.permissions().contains("inspection.delivery.download.all");
        if (!own && !platform) {
            throw new PlanResourceNotFoundException();
        }
        return visible;
    }

    /** Plans without file types predate per-plan deliverables and keep all four types. */
    private EnumSet<DeliveryFileType> requiredFileTypes(StoredServiceOrder order) {
        EnumSet<DeliveryFileType> required = EnumSet.noneOf(DeliveryFileType.class);
        orderStore.findPlan(order.planId())
                .orElseThrow(PlanResourceNotFoundException::new)
                .deliverables()
                .stream()
                .map(PlanDeliverable::fileType)
                .filter(Objects::nonNull)
                .forEach(required::add);
        return required.isEmpty() ? EnumSet.allOf(DeliveryFileType.class) : required;
    }

    private static String bundleFileName(StoredServiceOrder order, UUID visitId) {
        if (order.visits().size() <= 1) {
            return order.serviceOrderNo() + "-交付文件.zip";
        }
        int visitNo = order.visit(visitId)
                .orElseThrow(PlanResourceNotFoundException::new)
                .visitNo();
        return order.serviceOrderNo() + "-第" + visitNo + "次交付文件.zip";
    }

    private List<StoredDeliveryFile> validatedFiles(
            StoredServiceOrder order,
            StoredServiceVisit visit,
            UUID deliveryId,
            List<DeliveryFileInput> inputs,
            EnumSet<DeliveryFileType> requiredTypes,
            Integer towerCount
    ) {
        if (inputs == null || inputs.size() != requiredTypes.size()) {
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
                    || input.pointCount() != null && input.pointCount() < 0
                    || input.itemCount() != null && input.itemCount() < 0) {
                throw new DeliveryFilesInvalidException();
            }
            DeliveryFileObject file = store.findFileObject(input.fileId())
                    .orElseThrow(DeliveryFilesInvalidException::new);
            String expectedKey = DeliveryObjectKeys.artifact(
                    order.providerId(),
                    order.id(),
                    deliveryId,
                    input.fileId());
            if (!file.visitId().equals(visit.id())
                    || !file.providerId().equals(order.providerId())
                    || file.type() != input.type()
                    || !file.contentType().equalsIgnoreCase(input.type().mimeType())
                    || file.sizeBytes() <= 0
                    || file.status() != FileObjectStatus.READY
                    || !file.objectKey().equals(expectedKey)
                    || input.type() == DeliveryFileType.POINT_KML
                            && towerCount != null
                            && !towerCount.equals(input.pointCount())
                    || input.type() != DeliveryFileType.POINT_KML
                            && input.pointCount() != null) {
                throw new DeliveryFilesInvalidException();
            }
            return new StoredDeliveryFile(
                    file.id(),
                    input.type(),
                    displayName,
                    input.pointCount(),
                    input.itemCount(),
                    file.contentType(),
                    file.sizeBytes(),
                    file.status(),
                    file.bucket(),
                    file.objectKey());
        }).toList();
        if (!types.equals(requiredTypes)) {
            throw new DeliveryFilesInvalidException();
        }
        return result;
    }

    private static void validatePublish(
            UUID serviceOrderId,
            UUID visitId,
            UUID idempotencyKey,
            int expectedVersion,
            PublishDeliveryCommand command,
            String requestId
    ) {
        if (serviceOrderId == null
                || visitId == null
                || idempotencyKey == null
                || expectedVersion < 0
                || command == null
                || command.towerCount() != null && command.towerCount() < 0
                || command.inspectedLengthMeters() != null
                        && command.inspectedLengthMeters() < 0
                || !validMetrics(command.metrics())
                || command.metrics().isEmpty()
                        && command.towerCount() == null
                        && command.inspectedLengthMeters() == null
                || command.issueCount() < 0
                || command.issueGroupBy() == null
                || !validText(requestId, 1, 80)
                || !validIssues(command.issueSummary(), command.issueCount())
                || !digestableFiles(command.files())) {
            throw new DeliveryFilesInvalidException();
        }
    }

    /** The idempotency digest reads these fields before the files are checked in detail. */
    private static boolean digestableFiles(List<DeliveryFileInput> files) {
        return files != null
                && files.stream().allMatch(file -> file != null && file.displayName() != null);
    }

    private static boolean validMetrics(List<DeliveryMetricInput> metrics) {
        if (metrics == null || metrics.size() > MAX_METRICS) {
            return false;
        }
        Set<String> codes = new HashSet<>();
        return metrics.stream().allMatch(metric ->
                metric != null
                        && validText(metric.code(), 1, 32)
                        && validText(metric.label(), 1, 40)
                        && validText(metric.unit(), 1, 16)
                        && metric.value() != null
                        && metric.value().signum() >= 0
                        && metric.value().compareTo(MAX_METRIC_VALUE) <= 0
                        && metric.value().scale() <= MAX_METRIC_SCALE
                        && codes.add(metric.code().trim()));
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
                    || !validText(issue.groupCode(), 1, 32)
                    || !validText(issue.groupName(), 1, 40)
                    || issue.count() < 0
                    || !codes.add(issue.groupCode().trim())) {
                return false;
            }
            total += issue.count();
        }
        return total == issueCount;
    }

    private static void validateAccept(
            UUID serviceOrderId,
            UUID visitId,
            UUID idempotencyKey,
            int expectedVersion,
            AcceptDeliveryCommand command,
            String requestId
    ) {
        if (serviceOrderId == null
                || visitId == null
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

    private DeliveryView toView(StoredDelivery delivery) {
        return new DeliveryView(
                delivery.id(),
                delivery.serviceOrderId(),
                delivery.visitId(),
                delivery.status(),
                delivery.towerCount(),
                delivery.inspectedLengthMeters(),
                delivery.metrics(),
                delivery.issueCount(),
                delivery.issueGroupBy(),
                delivery.issueSummary(),
                delivery.files().stream()
                        .map(file -> new DeliveryFile(
                                file.fileId(),
                                file.type(),
                                file.displayName(),
                                file.pointCount(),
                                file.itemCount(),
                                file.contentType(),
                                file.sizeBytes(),
                                file.status()))
                        .toList(),
                delivery.publishedAt(),
                delivery.acceptedAt(),
                acceptedByName(delivery),
                delivery.version());
    }

    private AcceptanceView toView(StoredAcceptance acceptance) {
        return new AcceptanceView(
                acceptance.id(),
                acceptance.serviceOrderId(),
                acceptance.visitId(),
                acceptance.deliveryId(),
                acceptance.acceptedBy(),
                acceptance.acceptedByName(),
                acceptance.acceptedAt(),
                ServiceVisitStatus.ACCEPTED,
                acceptance.orderStatus(),
                acceptance.visitVersion());
    }

    private static String digestPublish(UUID visitId, PublishDeliveryCommand command) {
        java.util.ArrayList<Object> values = new java.util.ArrayList<>();
        values.add(visitId);
        values.add(command.towerCount());
        values.add(command.inspectedLengthMeters());
        command.metrics().forEach(item -> {
            values.add(item.code().trim());
            values.add(item.label().trim());
            values.add(item.value().stripTrailingZeros().toPlainString());
            values.add(item.unit().trim());
        });
        values.add(command.issueCount());
        values.add(command.issueGroupBy());
        command.issueSummary().forEach(item -> {
            values.add(item.groupCode().trim());
            values.add(item.groupName().trim());
            values.add(item.count());
        });
        command.files().forEach(file -> {
            values.add(file.fileId());
            values.add(file.type());
            values.add(file.displayName().trim());
            values.add(file.pointCount());
            values.add(file.itemCount());
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
        Integer towerCount,
        Long inspectedLengthMeters,
        List<DeliveryMetricInput> metrics,
        int issueCount,
        IssueGroupBy issueGroupBy,
        List<IssueSummaryInput> issueSummary,
        List<DeliveryFileInput> files
) {
}

record DeliveryMetricInput(String code, String label, BigDecimal value, String unit) {
}

record IssueSummaryInput(String groupCode, String groupName, int count) {
}

record DeliveryFileInput(
        UUID fileId,
        DeliveryFileType type,
        String displayName,
        Integer pointCount,
        Integer itemCount
) {
}

record AcceptDeliveryCommand(boolean confirmed, String note) {
}

enum DeliveryStatus {
    PUBLISHED,
    ACCEPTED
}

enum IssueGroupBy {
    SEVERITY,
    CATEGORY
}

record DeliveryMetric(String code, String label, BigDecimal value, String unit) {
}

record IssueSummary(String groupCode, String groupName, int count) {
}

record DeliveryFile(
        UUID fileId,
        DeliveryFileType type,
        String displayName,
        Integer pointCount,
        Integer itemCount,
        String contentType,
        long sizeBytes,
        FileObjectStatus status
) {
}

record DeliveryView(
        UUID id,
        UUID serviceOrderId,
        UUID visitId,
        DeliveryStatus status,
        Integer towerCount,
        Long inspectedLengthMeters,
        List<DeliveryMetric> metrics,
        int issueCount,
        IssueGroupBy issueGroupBy,
        List<IssueSummary> issueSummary,
        List<DeliveryFile> files,
        Instant publishedAt,
        Instant acceptedAt,
        String acceptedByName,
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
        UUID visitId,
        UUID deliveryId,
        UUID acceptedBy,
        String acceptedByName,
        Instant acceptedAt,
        ServiceVisitStatus visitStatus,
        ServiceOrderStatus orderStatus,
        int visitVersion
) {
}

record StoredDelivery(
        UUID id,
        UUID serviceOrderId,
        UUID visitId,
        DeliveryStatus status,
        Integer towerCount,
        Long inspectedLengthMeters,
        List<DeliveryMetric> metrics,
        int issueCount,
        IssueGroupBy issueGroupBy,
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
        Integer itemCount,
        String contentType,
        long sizeBytes,
        FileObjectStatus status,
        String bucket,
        String objectKey
) {
}

record DeliveryFileObject(
        UUID id,
        UUID visitId,
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
        UUID visitId,
        UUID deliveryId,
        UUID acceptedBy,
        String acceptedByName,
        Instant acceptedAt,
        String noteCiphertext,
        int visitVersion,
        ServiceOrderStatus orderStatus
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

    Optional<StoredDelivery> findDeliveryByVisit(UUID visitId);

    Optional<DeliveryFileObject> findFileObject(UUID fileId);

    Optional<StoredAcceptance> findAcceptance(UUID acceptanceId);

    Optional<StoredAcceptance> findAcceptanceByVisit(UUID visitId);

    Optional<DeliveryIdempotencyEntry> findDeliveryIdempotency(
            UUID actorId,
            String operation,
            UUID key);

    DeliveryWriteResult publishDelivery(
            StoredServiceOrder expectedOrder,
            ServiceOrderStatus nextOrderStatus,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit deliveredVisit,
            StoredDelivery delivery,
            DeliveryIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId);

    DeliveryWriteResult acceptDelivery(
            StoredServiceOrder expectedOrder,
            ServiceOrderStatus nextOrderStatus,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit acceptedVisit,
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
