package com.lowaltitude.inspection.application.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.application.reservation.AesGcmSensitiveDataCipher;
import com.lowaltitude.inspection.domain.files.BundleDownloadRequest;
import com.lowaltitude.inspection.domain.files.DeliveryFileType;
import com.lowaltitude.inspection.domain.files.DeliveryIdentity;
import com.lowaltitude.inspection.domain.files.DeliveryObjectKeys;
import com.lowaltitude.inspection.domain.files.DownloadObjectRequest;
import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.domain.files.ObjectStoragePort;
import com.lowaltitude.inspection.domain.files.PresignedObjectIntent;
import com.lowaltitude.inspection.domain.files.PreparedObjectUpload;
import com.lowaltitude.inspection.domain.files.UploadObjectRequest;
import com.lowaltitude.inspection.domain.files.UploadVerificationResult;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T05:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("60000000-0000-4000-8000-000000000001");

    private InMemoryDeliveryStore store;
    private RecordingStorage storage;
    private DeliveryApplicationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryStore(order(), fileObjects());
        storage = new RecordingStorage();
        service = new DeliveryApplicationService(
                store,
                store,
                storage,
                new AesGcmSensitiveDataCipher(
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void providerPublishesFourReadyArtifactsIdempotentlyAndAdvancesOrder() {
        UUID key = UUID.randomUUID();
        DeliveryView published = service.publish(
                provider(Set.of("inspection.delivery.publish.assigned")),
                ORDER,
                key,
                4,
                command(),
                "delivery-publish");
        DeliveryView replay = service.publish(
                provider(Set.of("inspection.delivery.publish.assigned")),
                ORDER,
                key,
                4,
                command(),
                "delivery-replay");

        assertThat(replay.id()).isEqualTo(published.id());
        assertThat(published.status()).isEqualTo(DeliveryStatus.PUBLISHED);
        assertThat(published.files()).extracting(DeliveryFile::type)
                .containsExactlyInAnyOrder(DeliveryFileType.values());
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.DELIVERED);
        assertThat(store.order.version()).isEqualTo(5);
        assertThat(store.publishCount).isEqualTo(1);
    }

    @Test
    void publicationRejectsQuantityMismatchUnreadyFileAndIllegalOrderState() {
        PublishDeliveryCommand mismatched = new PublishDeliveryCommand(
                24,
                18_600,
                4,
                List.of(
                        new IssueSummaryInput("GENERAL", "一般缺陷", 2),
                        new IssueSummaryInput("WATCH", "关注项", 1)),
                command().files());
        assertThatThrownBy(() -> service.publish(
                provider(Set.of("inspection.delivery.publish.assigned")),
                ORDER,
                UUID.randomUUID(),
                4,
                mismatched,
                "bad-count"))
                .isInstanceOf(DeliveryFilesInvalidException.class);

        List<DeliveryFileInput> unsafeFiles = new ArrayList<>(command().files());
        DeliveryFileInput first = unsafeFiles.get(0);
        unsafeFiles.set(0, new DeliveryFileInput(
                first.fileId(),
                first.type(),
                "../site-images.zip",
                first.pointCount()));
        PublishDeliveryCommand unsafeNames = new PublishDeliveryCommand(
                24,
                18_600,
                3,
                command().issueSummary(),
                unsafeFiles);
        assertThatThrownBy(() -> service.publish(
                provider(Set.of("inspection.delivery.publish.assigned")),
                ORDER,
                UUID.randomUUID(),
                4,
                unsafeNames,
                "unsafe-name"))
                .isInstanceOf(DeliveryFilesInvalidException.class);

        store.files.get(command().files().get(0).fileId()).status =
                FileObjectStatus.REJECTED;
        assertThatThrownBy(() -> service.publish(
                provider(Set.of("inspection.delivery.publish.assigned")),
                ORDER,
                UUID.randomUUID(),
                4,
                command(),
                "bad-file"))
                .isInstanceOf(DeliveryFilesInvalidException.class);
        store.files.get(command().files().get(0).fileId()).status =
                FileObjectStatus.READY;

        store.order = withStatus(store.order, ServiceOrderStatus.ARRANGING, 4);
        assertThatThrownBy(() -> service.publish(
                provider(Set.of("inspection.delivery.publish.assigned")),
                ORDER,
                UUID.randomUUID(),
                4,
                command(),
                "bad-state"))
                .isInstanceOf(DeliveryStateInvalidException.class);
    }

    @Test
    void customerAndPlatformReadAndDownloadButCrossTenantFailsClosed() {
        DeliveryView delivery = publish();

        assertThat(service.read(customer(CUSTOMER), ORDER).id()).isEqualTo(delivery.id());
        assertThat(service.read(platform(), ORDER).id()).isEqualTo(delivery.id());
        assertThatThrownBy(() -> service.read(customer(UUID.randomUUID()), ORDER))
                .isInstanceOf(PlanResourceNotFoundException.class);

        DownloadIntentView single = service.download(
                customer(CUSTOMER),
                ORDER,
                delivery.files().get(0).fileId(),
                UUID.randomUUID());
        DownloadIntentView bundle = service.downloadAll(
                customer(CUSTOMER),
                ORDER,
                UUID.randomUUID());

        assertThat(single.downloadUrl()).isEqualTo(URI.create("https://storage.test/file"));
        assertThat(bundle.downloadUrl()).isEqualTo(URI.create("https://storage.test/bundle"));
        assertThat(storage.downloadCount).isEqualTo(1);
        assertThat(storage.bundleCount).isEqualTo(1);
        assertThatThrownBy(() -> service.download(
                customer(UUID.randomUUID()),
                ORDER,
                delivery.files().get(0).fileId(),
                UUID.randomUUID()))
                .isInstanceOf(PlanResourceNotFoundException.class);
    }

    @Test
    void customerAcceptanceIsIdempotentAndCannotBePerformedByPlatform() {
        publish();
        UUID key = UUID.randomUUID();
        AcceptanceView accepted = service.accept(
                customer(CUSTOMER),
                ORDER,
                key,
                5,
                new AcceptDeliveryCommand(true, "结果符合本次服务范围"),
                "accept");
        AcceptanceView replay = service.accept(
                customer(CUSTOMER),
                ORDER,
                key,
                5,
                new AcceptDeliveryCommand(true, "结果符合本次服务范围"),
                "accept-replay");

        assertThat(replay.id()).isEqualTo(accepted.id());
        assertThat(accepted.orderStatus()).isEqualTo(ServiceOrderStatus.ACCEPTED);
        assertThat(accepted.orderVersion()).isEqualTo(6);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.ACCEPTED);
        assertThat(store.acceptCount).isEqualTo(1);
        assertThatThrownBy(() -> service.accept(
                platform(),
                ORDER,
                UUID.randomUUID(),
                6,
                new AcceptDeliveryCommand(true, null),
                "platform-accept"))
                .isInstanceOf(PlanForbiddenException.class);
    }

    @Test
    void staleVersionAndOtherProviderCannotPublish() {
        assertThatThrownBy(() -> service.publish(
                provider(Set.of("inspection.delivery.publish.assigned")),
                ORDER,
                UUID.randomUUID(),
                3,
                command(),
                "stale"))
                .isInstanceOf(PlanVersionConflictException.class);
        assertThatThrownBy(() -> service.publish(
                otherProvider(),
                ORDER,
                UUID.randomUUID(),
                4,
                command(),
                "other-provider"))
                .isInstanceOf(PlanResourceNotFoundException.class);
    }

    private DeliveryView publish() {
        return service.publish(
                provider(Set.of("inspection.delivery.publish.assigned")),
                ORDER,
                UUID.randomUUID(),
                4,
                command(),
                "publish");
    }

    private static PublishDeliveryCommand command() {
        List<UUID> ids = fileIds();
        return new PublishDeliveryCommand(
                24,
                18_600,
                3,
                List.of(
                        new IssueSummaryInput("GENERAL", "一般缺陷", 2),
                        new IssueSummaryInput("WATCH", "关注项", 1)),
                List.of(
                        new DeliveryFileInput(ids.get(0), DeliveryFileType.IMAGE_ARCHIVE,
                                "高清巡检影像.zip", null),
                        new DeliveryFileInput(ids.get(1), DeliveryFileType.DEFECT_LIST,
                                "缺陷清单.xlsx", null),
                        new DeliveryFileInput(ids.get(2), DeliveryFileType.POINT_KML,
                                "巡检点位标注.kml", 24),
                        new DeliveryFileInput(ids.get(3), DeliveryFileType.INSPECTION_REPORT,
                                "输电线路无人机巡检报告.pdf", null)));
    }

    private static StoredServiceOrder order() {
        return new StoredServiceOrder(
                ORDER,
                "FW202607280001",
                UUID.randomUUID(),
                UUID.randomUUID(),
                CUSTOMER,
                PROVIDER,
                ServiceOrderStatus.IN_PROGRESS,
                new ServiceSnapshot(
                        UUID.randomUUID(),
                        "输电线路无人机巡检",
                        null,
                        "370500",
                        "济南市历城区",
                        117.1,
                        36.7,
                        18_600L,
                        LocalDate.of(2026, 8, 2),
                        List.of(new CodeLabel("POWER_LINE", "线路本体巡检"))),
                Instant.parse("2026-08-02T01:00:00Z"),
                List.of(
                        new OrderAssignment(
                                AssignmentType.TEAM_MEMBER,
                                UUID.randomUUID(),
                                "巡检一组",
                                "负责人"),
                        new OrderAssignment(
                                AssignmentType.DEVICE,
                                UUID.randomUUID(),
                                "M350 RTK",
                                null)),
                List.of(
                        new OrderProgressEvent(
                                UUID.randomUUID(),
                                OrderProgressCode.ARRANGEMENT_READY,
                                NOW.minusSeconds(300),
                                "资源已安排"),
                        new OrderProgressEvent(
                                UUID.randomUUID(),
                                OrderProgressCode.SERVICE_STARTED,
                                NOW.minusSeconds(200),
                                "开始作业"),
                        new OrderProgressEvent(
                                UUID.randomUUID(),
                                OrderProgressCode.SERVICE_COMPLETED,
                                NOW.minusSeconds(100),
                                "现场完成")),
                NOW.minusSeconds(1_000),
                null,
                null,
                4);
    }

    private static Map<UUID, MutableFile> fileObjects() {
        Map<UUID, MutableFile> files = new HashMap<>();
        List<UUID> ids = fileIds();
        DeliveryFileType[] types = DeliveryFileType.values();
        UUID deliveryId = DeliveryIdentity.forServiceOrder(ORDER);
        for (int index = 0; index < types.length; index++) {
            DeliveryFileType type = types[index];
            UUID id = ids.get(index);
            files.put(id, new MutableFile(
                    id,
                    type,
                    type.mimeType(),
                    1024L + index,
                    "dev-inspection-private",
                    DeliveryObjectKeys.artifact(PROVIDER, ORDER, deliveryId, id),
                    FileObjectStatus.READY));
        }
        return files;
    }

    private static List<UUID> fileIds() {
        return List.of(
                UUID.fromString("80000000-0000-4000-8000-000000000001"),
                UUID.fromString("80000000-0000-4000-8000-000000000002"),
                UUID.fromString("80000000-0000-4000-8000-000000000003"),
                UUID.fromString("80000000-0000-4000-8000-000000000004"));
    }

    private static CurrentSubject customer(UUID id) {
        return new CurrentSubject(
                id,
                Set.of("CUSTOMER"),
                UUID.randomUUID(),
                Set.of(
                        "inspection.order.read.own",
                        "inspection.delivery.download.own",
                        "inspection.acceptance.create.own"),
                0);
    }

    private static CurrentSubject provider(Set<String> permissions) {
        return new CurrentSubject(
                UUID.fromString("30000000-0000-4000-8000-000000000002"),
                Set.of("PROVIDER_OPERATOR"),
                PROVIDER,
                permissions,
                0);
    }

    private static CurrentSubject otherProvider() {
        return new CurrentSubject(
                UUID.randomUUID(),
                Set.of("PROVIDER_OPERATOR"),
                UUID.randomUUID(),
                Set.of("inspection.delivery.publish.assigned"),
                0);
    }

    private static CurrentSubject platform() {
        return new CurrentSubject(
                UUID.randomUUID(),
                Set.of("PLATFORM_OPERATOR"),
                UUID.randomUUID(),
                Set.of(
                        "inspection.order.read.all",
                        "inspection.delivery.download.all"),
                0);
    }

    private static StoredServiceOrder withStatus(
            StoredServiceOrder source,
            ServiceOrderStatus status,
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
                status == ServiceOrderStatus.DELIVERED ? NOW : source.deliveredAt(),
                status == ServiceOrderStatus.ACCEPTED ? NOW : source.acceptedAt(),
                version);
    }
}

final class InMemoryDeliveryStore implements PlanStore, DeliveryStore {

    StoredServiceOrder order;
    final Map<UUID, MutableFile> files;
    private StoredDelivery delivery;
    private StoredAcceptance acceptance;
    private final Map<String, DeliveryIdempotencyEntry> idempotency = new HashMap<>();
    int publishCount;
    int acceptCount;

    InMemoryDeliveryStore(StoredServiceOrder order, Map<UUID, MutableFile> files) {
        this.order = order;
        this.files = files;
    }

    @Override
    public Optional<StoredServiceOrder> findOrder(UUID orderId) {
        return order.id().equals(orderId) ? Optional.of(order) : Optional.empty();
    }

    @Override
    public Optional<StoredDelivery> findDelivery(UUID deliveryId) {
        return Optional.ofNullable(delivery).filter(value -> value.id().equals(deliveryId));
    }

    @Override
    public Optional<StoredDelivery> findDeliveryByOrder(UUID orderId) {
        return Optional.ofNullable(delivery)
                .filter(value -> value.serviceOrderId().equals(orderId));
    }

    @Override
    public Optional<DeliveryFileObject> findFileObject(UUID fileId) {
        MutableFile file = files.get(fileId);
        return file == null ? Optional.empty() : Optional.of(file.toView());
    }

    @Override
    public Optional<StoredAcceptance> findAcceptance(UUID acceptanceId) {
        return Optional.ofNullable(acceptance)
                .filter(value -> value.id().equals(acceptanceId));
    }

    @Override
    public Optional<StoredAcceptance> findAcceptanceByOrder(UUID orderId) {
        return Optional.ofNullable(acceptance)
                .filter(value -> value.serviceOrderId().equals(orderId));
    }

    @Override
    public Optional<DeliveryIdempotencyEntry> findDeliveryIdempotency(
            UUID actorId,
            String operation,
            UUID key
    ) {
        return Optional.ofNullable(idempotency.get(actorId + ":" + operation + ":" + key));
    }

    @Override
    public DeliveryWriteResult publishDelivery(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder deliveredOrder,
            StoredDelivery value,
            DeliveryIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        if (idempotency.containsKey(key(entry))) return DeliveryWriteResult.IDEMPOTENCY_EXISTS;
        if (order.version() != expectedOrder.version()) return DeliveryWriteResult.VERSION_CONFLICT;
        order = deliveredOrder;
        delivery = value;
        idempotency.put(key(entry), entry);
        publishCount++;
        return DeliveryWriteResult.SAVED;
    }

    @Override
    public DeliveryWriteResult acceptDelivery(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder acceptedOrder,
            StoredDelivery expectedDelivery,
            StoredDelivery acceptedDelivery,
            StoredAcceptance value,
            DeliveryIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        if (idempotency.containsKey(key(entry))) return DeliveryWriteResult.IDEMPOTENCY_EXISTS;
        if (order.version() != expectedOrder.version()) return DeliveryWriteResult.VERSION_CONFLICT;
        order = acceptedOrder;
        delivery = acceptedDelivery;
        acceptance = value;
        idempotency.put(key(entry), entry);
        acceptCount++;
        return DeliveryWriteResult.SAVED;
    }

    @Override
    public boolean claimDeliveryIdempotency(
            DeliveryIdempotencyEntry entry,
            String resourceType,
            int responseStatus
    ) {
        if (idempotency.containsKey(key(entry))) return false;
        idempotency.put(key(entry), entry);
        return true;
    }

    private static String key(DeliveryIdempotencyEntry entry) {
        return entry.actorId() + ":" + entry.operation() + ":" + entry.key();
    }

    @Override public Optional<PlanReservation> findReservation(UUID id) { return Optional.empty(); }
    @Override public Optional<StoredServicePlan> findPlan(UUID id) { return Optional.empty(); }
    @Override public Optional<PlanIdempotencyEntry> findIdempotency(UUID actorId, String operation, UUID key) { return Optional.empty(); }
    @Override public int nextPlanVersion(UUID id) { return 1; }
    @Override public PlanWriteResult publish(PlanReservation reservation, StoredServicePlan plan, PlanIdempotencyEntry entry, CurrentSubject actor, String requestId) { return PlanWriteResult.STATE_CONFLICT; }
    @Override public PlanWriteResult confirm(StoredServicePlan plan, StoredServiceOrder order, PlanIdempotencyEntry entry, String agreementVersion, CurrentSubject actor, String requestId) { return PlanWriteResult.STATE_CONFLICT; }
}

final class MutableFile {
    final UUID id;
    final DeliveryFileType type;
    final String contentType;
    final long sizeBytes;
    final String bucket;
    final String objectKey;
    FileObjectStatus status;

    MutableFile(
            UUID id,
            DeliveryFileType type,
            String contentType,
            long sizeBytes,
            String bucket,
            String objectKey,
            FileObjectStatus status
    ) {
        this.id = id;
        this.type = type;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.status = status;
    }

    DeliveryFileObject toView() {
        return new DeliveryFileObject(
                id,
                ORDER_ID(),
                PROVIDER_ID(),
                type,
                contentType,
                sizeBytes,
                bucket,
                objectKey,
                status);
    }

    private static UUID ORDER_ID() {
        return UUID.fromString("60000000-0000-4000-8000-000000000001");
    }

    private static UUID PROVIDER_ID() {
        return UUID.fromString("30000000-0000-4000-8000-000000000001");
    }
}

final class RecordingStorage implements ObjectStoragePort {

    int downloadCount;
    int bundleCount;

    @Override
    public PreparedObjectUpload createUploadIntent(UploadObjectRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public UploadVerificationResult verifyUpload(UploadObjectRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PresignedObjectIntent createDownloadIntent(DownloadObjectRequest request) {
        downloadCount++;
        return new PresignedObjectIntent(
                "GET",
                URI.create("https://storage.test/file"),
                Map.of(),
                Instant.parse("2026-07-28T05:05:00Z"));
    }

    @Override
    public PresignedObjectIntent createBundleDownloadIntent(BundleDownloadRequest request) {
        bundleCount++;
        return new PresignedObjectIntent(
                "GET",
                URI.create("https://storage.test/bundle"),
                Map.of(),
                Instant.parse("2026-07-28T05:05:00Z"));
    }
}
