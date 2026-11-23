package com.lowaltitude.inspection.application.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final UUID VISIT_1 = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID VISIT_2 = UUID.fromString("61000000-0000-4000-8000-000000000002");
    private static final UUID PLAN = UUID.fromString("62000000-0000-4000-8000-000000000001");
    private static final int READY_VERSION = 3;

    private InMemoryDeliveryStore store;
    private RecordingStorage storage;
    private DeliveryApplicationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryStore(order(List.of(completedVisit(VISIT_1, 1))));
        store.addFiles(fileObjects(VISIT_1, 1));
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
                publisher(),
                ORDER,
                VISIT_1,
                key,
                READY_VERSION,
                command(1),
                "delivery-publish");
        DeliveryView replay = service.publish(
                publisher(),
                ORDER,
                VISIT_1,
                key,
                READY_VERSION,
                command(1),
                "delivery-replay");

        assertThat(replay.id()).isEqualTo(published.id());
        assertThat(published.status()).isEqualTo(DeliveryStatus.PUBLISHED);
        assertThat(published.visitId()).isEqualTo(VISIT_1);
        assertThat(published.id()).isEqualTo(DeliveryIdentity.forVisit(VISIT_1));
        assertThat(published.files()).extracting(DeliveryFile::type)
                .containsExactlyInAnyOrder(DeliveryFileType.values());
        assertThat(store.visit(VISIT_1).status()).isEqualTo(ServiceVisitStatus.DELIVERED);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.DELIVERED);
        assertThat(store.publishCount).isEqualTo(1);
    }

    @Test
    void publicationRejectsQuantityMismatchUnreadyFileAndIllegalVisitState() {
        PublishDeliveryCommand mismatched = new PublishDeliveryCommand(
                24,
                18_600L,
                List.of(),
                4,
                IssueGroupBy.SEVERITY,
                List.of(
                        new IssueSummaryInput("GENERAL", "一般缺陷", 2),
                        new IssueSummaryInput("WATCH", "关注项", 1)),
                command(1).files());
        assertThatThrownBy(() -> publish(VISIT_1, mismatched))
                .isInstanceOf(DeliveryFilesInvalidException.class);

        List<DeliveryFileInput> unsafeFiles = new ArrayList<>(command(1).files());
        DeliveryFileInput first = unsafeFiles.get(0);
        unsafeFiles.set(0, new DeliveryFileInput(
                first.fileId(),
                first.type(),
                "../site-images.zip",
                first.pointCount(),
                first.itemCount()));
        PublishDeliveryCommand unsafeNames = new PublishDeliveryCommand(
                24,
                18_600L,
                List.of(),
                3,
                IssueGroupBy.SEVERITY,
                command(1).issueSummary(),
                unsafeFiles);
        assertThatThrownBy(() -> publish(VISIT_1, unsafeNames))
                .isInstanceOf(DeliveryFilesInvalidException.class);

        store.files.get(command(1).files().get(0).fileId()).status = FileObjectStatus.REJECTED;
        assertThatThrownBy(() -> publish(VISIT_1, command(1)))
                .isInstanceOf(DeliveryFilesInvalidException.class);
        store.files.get(command(1).files().get(0).fileId()).status = FileObjectStatus.READY;

        store.replaceVisit(visitWithStatus(
                store.visit(VISIT_1),
                ServiceVisitStatus.SCHEDULED));
        assertThatThrownBy(() -> publish(VISIT_1, command(1)))
                .isInstanceOf(DeliveryStateInvalidException.class);
    }

    @Test
    void visitMustFinishFieldWorkBeforeDelivery() {
        store.replaceVisit(new StoredServiceVisit(
                VISIT_1,
                ORDER,
                1,
                LocalDate.of(2026, 8, 2),
                null,
                ServiceVisitStatus.IN_PROGRESS,
                List.of(event(OrderProgressCode.SERVICE_STARTED)),
                NOW,
                null,
                null,
                READY_VERSION));

        assertThatThrownBy(() -> publish(VISIT_1, command(1)))
                .isInstanceOf(DeliveryStateInvalidException.class);
    }

    @Test
    void customerAndPlatformReadAndDownloadButCrossTenantFailsClosed() {
        DeliveryView delivery = publish(VISIT_1, command(1));

        assertThat(service.read(customer(CUSTOMER), ORDER, VISIT_1).id())
                .isEqualTo(delivery.id());
        assertThat(service.read(platform(), ORDER, VISIT_1).id()).isEqualTo(delivery.id());
        assertThatThrownBy(() -> service.read(customer(UUID.randomUUID()), ORDER, VISIT_1))
                .isInstanceOf(PlanResourceNotFoundException.class);

        DownloadIntentView single = service.download(
                customer(CUSTOMER),
                ORDER,
                VISIT_1,
                delivery.files().get(0).fileId(),
                UUID.randomUUID());
        DownloadIntentView bundle = service.downloadAll(
                customer(CUSTOMER),
                ORDER,
                VISIT_1,
                UUID.randomUUID());

        assertThat(single.downloadUrl()).isEqualTo(URI.create("https://storage.test/file"));
        assertThat(bundle.downloadUrl()).isEqualTo(URI.create("https://storage.test/bundle"));
        assertThat(bundle.suggestedFileName()).isEqualTo("FW202607280001-交付文件.zip");
        assertThat(storage.downloadCount).isEqualTo(1);
        assertThat(storage.bundleCount).isEqualTo(1);
        assertThatThrownBy(() -> service.download(
                customer(UUID.randomUUID()),
                ORDER,
                VISIT_1,
                delivery.files().get(0).fileId(),
                UUID.randomUUID()))
                .isInstanceOf(PlanResourceNotFoundException.class);
    }

    @Test
    void visitFromAnotherOrderIsNotFoundEverywhere() {
        publish(VISIT_1, command(1));
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.read(customer(CUSTOMER), ORDER, stranger))
                .isInstanceOf(PlanResourceNotFoundException.class);
        assertThatThrownBy(() -> service.downloadAll(
                customer(CUSTOMER),
                ORDER,
                stranger,
                UUID.randomUUID()))
                .isInstanceOf(PlanResourceNotFoundException.class);
        assertThatThrownBy(() -> service.accept(
                customer(CUSTOMER),
                ORDER,
                stranger,
                UUID.randomUUID(),
                0,
                new AcceptDeliveryCommand(true, null),
                "accept-stranger"))
                .isInstanceOf(PlanResourceNotFoundException.class);
        assertThatThrownBy(() -> publish(stranger, command(1)))
                .isInstanceOf(PlanResourceNotFoundException.class);
    }

    @Test
    void customerAcceptanceIsIdempotentAndCannotBePerformedByPlatform() {
        publish(VISIT_1, command(1));
        int deliveredVersion = store.visit(VISIT_1).version();
        UUID key = UUID.randomUUID();
        AcceptanceView accepted = service.accept(
                customer(CUSTOMER),
                ORDER,
                VISIT_1,
                key,
                deliveredVersion,
                new AcceptDeliveryCommand(true, "结果符合本次服务范围"),
                "accept");
        AcceptanceView replay = service.accept(
                customer(CUSTOMER),
                ORDER,
                VISIT_1,
                key,
                deliveredVersion,
                new AcceptDeliveryCommand(true, "结果符合本次服务范围"),
                "accept-replay");

        assertThat(replay.id()).isEqualTo(accepted.id());
        assertThat(accepted.visitStatus()).isEqualTo(ServiceVisitStatus.ACCEPTED);
        assertThat(accepted.orderStatus()).isEqualTo(ServiceOrderStatus.ACCEPTED);
        assertThat(accepted.visitVersion()).isEqualTo(deliveredVersion + 1);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.ACCEPTED);
        assertThat(store.acceptCount).isEqualTo(1);
        assertThatThrownBy(() -> service.accept(
                platform(),
                ORDER,
                VISIT_1,
                UUID.randomUUID(),
                deliveredVersion + 1,
                new AcceptDeliveryCommand(true, null),
                "platform-accept"))
                .isInstanceOf(PlanForbiddenException.class);
    }

    @Test
    void readingAnAcceptedDeliveryNamesWhoAcceptedIt() {
        store.displayName = "王女士";
        publish(VISIT_1, command(1));
        assertThat(service.read(customer(CUSTOMER), ORDER, VISIT_1).acceptedByName()).isNull();

        AcceptanceView accepted = accept(VISIT_1);

        DeliveryView delivery = service.read(customer(CUSTOMER), ORDER, VISIT_1);
        assertThat(delivery.acceptedAt()).isEqualTo(accepted.acceptedAt());
        assertThat(delivery.acceptedByName()).isEqualTo("王女士");
    }

    @Test
    void customerAcceptanceAllowsAnEmptyNote() {
        publish(VISIT_1, command(1));

        AcceptanceView accepted = accept(VISIT_1);

        assertThat(accepted.orderStatus()).isEqualTo(ServiceOrderStatus.ACCEPTED);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.ACCEPTED);
        assertThat(store.acceptCount).isEqualTo(1);
    }

    @Test
    void acceptingAnAlreadyAcceptedVisitReturnsTheSameResult() {
        publish(VISIT_1, command(1));
        AcceptanceView first = accept(VISIT_1);

        AcceptanceView again = accept(VISIT_1);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(store.acceptCount).isEqualTo(1);
    }

    @Test
    void staleVersionAndOtherProviderCannotPublish() {
        assertThatThrownBy(() -> service.publish(
                publisher(),
                ORDER,
                VISIT_1,
                UUID.randomUUID(),
                READY_VERSION - 1,
                command(1),
                "stale"))
                .isInstanceOf(PlanVersionConflictException.class);
        assertThatThrownBy(() -> service.publish(
                otherProvider(),
                ORDER,
                VISIT_1,
                UUID.randomUUID(),
                READY_VERSION,
                command(1),
                "other-provider"))
                .isInstanceOf(PlanResourceNotFoundException.class);
    }

    @Test
    void acceptingOneVisitKeepsTheOrderInProgressUntilTheLastOne() {
        useTwoVisitOrder();

        publish(VISIT_1, command(1));
        assertThat(store.visit(VISIT_1).status()).isEqualTo(ServiceVisitStatus.DELIVERED);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.IN_PROGRESS);

        AcceptanceView first = accept(VISIT_1);
        assertThat(first.visitStatus()).isEqualTo(ServiceVisitStatus.ACCEPTED);
        assertThat(first.orderStatus()).isEqualTo(ServiceOrderStatus.IN_PROGRESS);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.IN_PROGRESS);

        publish(VISIT_2, command(2));
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.DELIVERED);

        AcceptanceView last = accept(VISIT_2);
        assertThat(last.orderStatus()).isEqualTo(ServiceOrderStatus.ACCEPTED);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.ACCEPTED);
        assertThat(store.acceptCount).isEqualTo(2);
    }

    @Test
    void lastVisitCanBeDeliveredBeforeTheFirstIsAccepted() {
        useTwoVisitOrder();
        publish(VISIT_1, command(1));
        publish(VISIT_2, command(2));

        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.DELIVERED);

        accept(VISIT_2);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.DELIVERED);

        accept(VISIT_1);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.ACCEPTED);
    }

    @Test
    void acceptingAVisitDoesNotNeedTheOrderVersion() {
        useTwoVisitOrder();
        publish(VISIT_1, command(1));
        int orderVersion = store.order.version();
        publish(VISIT_2, command(2));

        assertThat(store.order.version()).isGreaterThan(orderVersion);
        assertThat(accept(VISIT_1).visitStatus()).isEqualTo(ServiceVisitStatus.ACCEPTED);
    }

    @Test
    void filesOfOneVisitCannotBePublishedOnAnother() {
        useTwoVisitOrder();

        assertThatThrownBy(() -> publish(VISIT_2, command(1)))
                .isInstanceOf(DeliveryFilesInvalidException.class);
        assertThat(store.visit(VISIT_2).status()).isEqualTo(ServiceVisitStatus.IN_PROGRESS);
    }

    @Test
    void reusingAnIdempotencyKeyOnAnotherVisitConflicts() {
        useTwoVisitOrder();
        publish(VISIT_1, command(1));
        publish(VISIT_2, command(2));
        UUID key = UUID.randomUUID();
        service.accept(
                customer(CUSTOMER),
                ORDER,
                VISIT_1,
                key,
                store.visit(VISIT_1).version(),
                new AcceptDeliveryCommand(true, null),
                "accept-first");

        assertThatThrownBy(() -> service.accept(
                customer(CUSTOMER),
                ORDER,
                VISIT_2,
                key,
                store.visit(VISIT_2).version(),
                new AcceptDeliveryCommand(true, null),
                "accept-other-visit"))
                .isInstanceOf(PlanIdempotencyConflictException.class);
    }

    @Test
    void multiVisitBundleNameSaysWhichVisitItIs() {
        useTwoVisitOrder();
        publish(VISIT_2, command(2));

        assertThat(service.downloadAll(
                customer(CUSTOMER),
                ORDER,
                VISIT_2,
                UUID.randomUUID()).suggestedFileName())
                .isEqualTo("FW202607280001-第2次交付文件.zip");
    }

    @Test
    void planDecidesWhichFileTypesEachDeliveryNeeds() {
        store.deliverables = List.of(
                new PlanDeliverable("REPORT", "巡检报告", DeliveryFileType.INSPECTION_REPORT),
                new PlanDeliverable("DEFECT_LIST", "隐患清单", DeliveryFileType.DEFECT_LIST),
                new PlanDeliverable("MEDIA", "影像存档", DeliveryFileType.IMAGE_ARCHIVE),
                new PlanDeliverable("BRIEFING", "现场说明", null));
        List<DeliveryFileInput> withoutPoints = command(1).files().stream()
                .filter(file -> file.type() != DeliveryFileType.POINT_KML)
                .toList();

        DeliveryView published = publish(VISIT_1, new PublishDeliveryCommand(
                null,
                null,
                List.of(new DeliveryMetricInput(
                        "COVERED_AREA",
                        "巡检覆盖",
                        new BigDecimal("420000"),
                        "㎡")),
                3,
                IssueGroupBy.CATEGORY,
                List.of(
                        new IssueSummaryInput("FENCE", "围栏破损", 1),
                        new IssueSummaryInput("LIGHT", "照明故障", 1),
                        new IssueSummaryInput("FIRE_LANE", "消防通道占用", 1)),
                withoutPoints));

        assertThat(published.files()).hasSize(3);
        assertThat(published.towerCount()).isNull();
        assertThat(published.metrics()).extracting(DeliveryMetric::code)
                .containsExactly("COVERED_AREA");
        assertThat(published.issueGroupBy()).isEqualTo(IssueGroupBy.CATEGORY);
    }

    @Test
    void deliveryMustMatchThePlanFileTypesExactly() {
        store.deliverables = List.of(
                new PlanDeliverable("REPORT", "巡检报告", DeliveryFileType.INSPECTION_REPORT));

        assertThatThrownBy(() -> publish(VISIT_1, command(1)))
                .isInstanceOf(DeliveryFilesInvalidException.class);

        DeliveryFileInput report = command(1).files().stream()
                .filter(file -> file.type() == DeliveryFileType.INSPECTION_REPORT)
                .findFirst()
                .orElseThrow();
        assertThat(publish(VISIT_1, withFiles(command(1), List.of(report))).files()).hasSize(1);
    }

    @Test
    void plansWithoutFileTypesKeepAllFourRequired() {
        store.deliverables = List.of(new PlanDeliverable("REPORT", "巡检报告", null));
        List<DeliveryFileInput> onlyThree = command(1).files().subList(0, 3);

        assertThatThrownBy(() -> publish(VISIT_1, withFiles(command(1), onlyThree)))
                .isInstanceOf(DeliveryFilesInvalidException.class);
    }

    @Test
    void deliveryNeedsAtLeastOneResultFigureAndValidMetrics() {
        PublishDeliveryCommand noFigures = new PublishDeliveryCommand(
                null,
                null,
                List.of(),
                3,
                IssueGroupBy.SEVERITY,
                command(1).issueSummary(),
                command(1).files());
        assertThatThrownBy(() -> publish(VISIT_1, noFigures))
                .isInstanceOf(DeliveryFilesInvalidException.class);

        PublishDeliveryCommand negativeMetric = new PublishDeliveryCommand(
                null,
                null,
                List.of(new DeliveryMetricInput(
                        "COVERED_AREA",
                        "巡检覆盖",
                        new BigDecimal("-1"),
                        "㎡")),
                3,
                IssueGroupBy.SEVERITY,
                command(1).issueSummary(),
                command(1).files());
        assertThatThrownBy(() -> publish(VISIT_1, negativeMetric))
                .isInstanceOf(DeliveryFilesInvalidException.class);
    }

    @Test
    void missingFilesAndFileNamesAreAnInvalidDeliveryNotACrash() {
        PublishDeliveryCommand base = command(1);
        List<UUID> ids = fileIds(1);

        for (List<DeliveryFileInput> invalid : Arrays.asList(
                null,
                Arrays.asList((DeliveryFileInput) null),
                List.of(new DeliveryFileInput(
                        ids.get(3),
                        DeliveryFileType.INSPECTION_REPORT,
                        null,
                        null,
                        null)))) {
            PublishDeliveryCommand command = new PublishDeliveryCommand(
                    base.towerCount(),
                    base.inspectedLengthMeters(),
                    base.metrics(),
                    base.issueCount(),
                    base.issueGroupBy(),
                    base.issueSummary(),
                    invalid);

            assertThatThrownBy(() -> publish(VISIT_1, command))
                    .isInstanceOf(DeliveryFilesInvalidException.class);
        }
    }

    @Test
    void metricValuesStayWithinTheContractLimits() {
        for (String invalid : List.of(
                "1e200000",
                "1e2147483647",
                "1000000000000000",
                "0.1234567")) {
            PublishDeliveryCommand command = withMetricValue(new BigDecimal(invalid));

            assertThatThrownBy(() -> publish(VISIT_1, command))
                    .isInstanceOf(DeliveryFilesInvalidException.class);
        }

        DeliveryView published = publish(
                VISIT_1,
                withMetricValue(new BigDecimal("999999999999999")));

        assertThat(published.metrics().get(0).value())
                .isEqualByComparingTo("999999999999999");
    }

    @Test
    void pointCountOnlyHasToMatchWhenATowerCountIsGiven() {
        List<DeliveryFileInput> files = new ArrayList<>(command(1).files());
        DeliveryFileInput points = files.get(2);
        files.set(2, new DeliveryFileInput(
                points.fileId(),
                points.type(),
                points.displayName(),
                7,
                null));

        assertThatThrownBy(() -> publish(VISIT_1, withFiles(command(1), files)))
                .isInstanceOf(DeliveryFilesInvalidException.class);

        DeliveryView published = publish(VISIT_1, new PublishDeliveryCommand(
                null,
                18_600L,
                List.of(),
                3,
                IssueGroupBy.SEVERITY,
                command(1).issueSummary(),
                files));
        assertThat(published.towerCount()).isNull();
    }

    private void useTwoVisitOrder() {
        store.order = order(List.of(
                completedVisit(VISIT_1, 1),
                completedVisit(VISIT_2, 2)));
        store.addFiles(fileObjects(VISIT_2, 2));
    }

    private DeliveryView publish(UUID visitId, PublishDeliveryCommand command) {
        return service.publish(
                publisher(),
                ORDER,
                visitId,
                UUID.randomUUID(),
                store.order.visit(visitId).map(StoredServiceVisit::version).orElse(0),
                command,
                "publish");
    }

    private AcceptanceView accept(UUID visitId) {
        return service.accept(
                customer(CUSTOMER),
                ORDER,
                visitId,
                UUID.randomUUID(),
                store.visit(visitId).version(),
                new AcceptDeliveryCommand(true, null),
                "accept");
    }

    private static PublishDeliveryCommand withFiles(
            PublishDeliveryCommand source,
            List<DeliveryFileInput> files
    ) {
        return new PublishDeliveryCommand(
                source.towerCount(),
                source.inspectedLengthMeters(),
                source.metrics(),
                source.issueCount(),
                source.issueGroupBy(),
                source.issueSummary(),
                files);
    }

    private static PublishDeliveryCommand withMetricValue(BigDecimal value) {
        PublishDeliveryCommand base = command(1);
        return new PublishDeliveryCommand(
                base.towerCount(),
                base.inspectedLengthMeters(),
                List.of(new DeliveryMetricInput("COVERED_AREA", "巡检覆盖", value, "㎡")),
                base.issueCount(),
                base.issueGroupBy(),
                base.issueSummary(),
                base.files());
    }

    private static PublishDeliveryCommand command(int visitNo) {
        List<UUID> ids = fileIds(visitNo);
        return new PublishDeliveryCommand(
                24,
                18_600L,
                List.of(),
                3,
                IssueGroupBy.SEVERITY,
                List.of(
                        new IssueSummaryInput("GENERAL", "一般缺陷", 2),
                        new IssueSummaryInput("WATCH", "关注项", 1)),
                List.of(
                        new DeliveryFileInput(ids.get(0), DeliveryFileType.IMAGE_ARCHIVE,
                                "高清巡检影像.zip", null, 36),
                        new DeliveryFileInput(ids.get(1), DeliveryFileType.DEFECT_LIST,
                                "缺陷清单.xlsx", null, null),
                        new DeliveryFileInput(ids.get(2), DeliveryFileType.POINT_KML,
                                "巡检点位标注.kml", 24, null),
                        new DeliveryFileInput(ids.get(3), DeliveryFileType.INSPECTION_REPORT,
                                "输电线路无人机巡检报告.pdf", null, null)));
    }

    private static OrderProgressEvent event(OrderProgressCode code) {
        return new OrderProgressEvent(UUID.randomUUID(), code, NOW.minusSeconds(200), code.name());
    }

    private static StoredServiceVisit completedVisit(UUID id, int visitNo) {
        return new StoredServiceVisit(
                id,
                ORDER,
                visitNo,
                LocalDate.of(2026, 8, 2).plusDays(visitNo),
                Instant.parse("2026-08-02T01:00:00Z"),
                ServiceVisitStatus.IN_PROGRESS,
                List.of(
                        event(OrderProgressCode.SERVICE_STARTED),
                        event(OrderProgressCode.SERVICE_COMPLETED)),
                NOW.minusSeconds(200),
                null,
                null,
                READY_VERSION);
    }

    private static StoredServiceVisit visitWithStatus(
            StoredServiceVisit source,
            ServiceVisitStatus status
    ) {
        return new StoredServiceVisit(
                source.id(),
                source.serviceOrderId(),
                source.visitNo(),
                source.plannedDate(),
                source.plannedStartAt(),
                status,
                source.progress(),
                source.startedAt(),
                source.deliveredAt(),
                source.acceptedAt(),
                source.version());
    }

    private static StoredServiceOrder order(List<StoredServiceVisit> visits) {
        return new StoredServiceOrder(
                ORDER,
                "FW202607280001",
                UUID.randomUUID(),
                PLAN,
                CUSTOMER,
                PROVIDER,
                PaymentMode.NONE,
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
                List.of(
                        new OrderAssignment(
                                AssignmentType.TEAM_MEMBER,
                                UUID.randomUUID(),
                                "巡检一组",
                                "负责人",
                                null,
                                false),
                        new OrderAssignment(
                                AssignmentType.DEVICE,
                                UUID.randomUUID(),
                                "M350 RTK",
                                null,
                                null,
                                false)),
                List.of(new OrderProgressEvent(
                        UUID.randomUUID(),
                        OrderProgressCode.ARRANGEMENT_READY,
                        NOW.minusSeconds(300),
                        "资源已安排")),
                visits,
                List.of(),
                NOW.minusSeconds(1_000),
                null,
                null,
                4);
    }

    private static Map<UUID, MutableFile> fileObjects(UUID visitId, int visitNo) {
        Map<UUID, MutableFile> files = new HashMap<>();
        List<UUID> ids = fileIds(visitNo);
        DeliveryFileType[] types = DeliveryFileType.values();
        UUID deliveryId = DeliveryIdentity.forVisit(visitId);
        for (int index = 0; index < types.length; index++) {
            DeliveryFileType type = types[index];
            UUID id = ids.get(index);
            files.put(id, new MutableFile(
                    id,
                    visitId,
                    type,
                    type.mimeType(),
                    1024L + index,
                    "dev-inspection-private",
                    DeliveryObjectKeys.artifact(PROVIDER, ORDER, deliveryId, id),
                    FileObjectStatus.READY));
        }
        return files;
    }

    private static List<UUID> fileIds(int visitNo) {
        return List.of(
                UUID.fromString("8000000" + visitNo + "-0000-4000-8000-000000000001"),
                UUID.fromString("8000000" + visitNo + "-0000-4000-8000-000000000002"),
                UUID.fromString("8000000" + visitNo + "-0000-4000-8000-000000000003"),
                UUID.fromString("8000000" + visitNo + "-0000-4000-8000-000000000004"));
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

    private static CurrentSubject publisher() {
        return new CurrentSubject(
                UUID.fromString("30000000-0000-4000-8000-000000000002"),
                Set.of("PROVIDER_OPERATOR"),
                PROVIDER,
                Set.of("inspection.delivery.publish.assigned"),
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
}

final class InMemoryDeliveryStore implements PlanStore, DeliveryStore {

    String displayName;

    StoredServiceOrder order;
    List<PlanDeliverable> deliverables = List.of();
    final Map<UUID, MutableFile> files = new HashMap<>();
    private final Map<UUID, StoredDelivery> deliveries = new HashMap<>();
    private final Map<UUID, StoredAcceptance> acceptances = new HashMap<>();
    private final Map<String, DeliveryIdempotencyEntry> idempotency = new HashMap<>();
    int publishCount;
    int acceptCount;

    InMemoryDeliveryStore(StoredServiceOrder order) {
        this.order = order;
    }

    void addFiles(Map<UUID, MutableFile> added) {
        files.putAll(added);
    }

    StoredServiceVisit visit(UUID visitId) {
        return order.visit(visitId).orElseThrow();
    }

    void replaceVisit(StoredServiceVisit updated) {
        applyVisit(updated, order.status(), null, null);
    }

    private void applyVisit(
            StoredServiceVisit updated,
            ServiceOrderStatus status,
            Instant deliveredAt,
            Instant acceptedAt
    ) {
        boolean changed = status != order.status();
        order = new StoredServiceOrder(
                order.id(),
                order.serviceOrderNo(),
                order.reservationId(),
                order.planId(),
                order.customerId(),
                order.providerId(),
                order.paymentMode(),
                status,
                order.serviceSnapshot(),
                order.assignments(),
                order.progress(),
                ServiceOrderProgression.replace(order.visits(), updated),
                order.paymentNodes(),
                order.createdAt(),
                deliveredAt == null ? order.deliveredAt() : deliveredAt,
                acceptedAt == null ? order.acceptedAt() : acceptedAt,
                changed ? order.version() + 1 : order.version());
    }

    @Override
    public Optional<StoredServiceOrder> findOrder(UUID orderId) {
        return order.id().equals(orderId) ? Optional.of(order) : Optional.empty();
    }

    @Override
    public Optional<StoredServicePlan> findPlan(UUID id) {
        return Optional.of(new StoredServicePlan(
                id,
                "FA202607280001",
                null,
                order.providerId(),
                PlanStatus.CONFIRMED,
                1,
                Instant.parse("2026-08-01T00:00:00Z"),
                LocalDate.of(2026, 8, 2),
                null,
                new PlanTerms(List.of(), null, List.of(), null, null, List.of()),
                List.of(),
                deliverables,
                0,
                "CNY",
                Instant.parse("2026-07-27T00:00:00Z"),
                null,
                null,
                order.id(),
                1));
    }

    @Override
    public Optional<StoredDelivery> findDelivery(UUID deliveryId) {
        return deliveries.values().stream()
                .filter(value -> value.id().equals(deliveryId))
                .findFirst();
    }

    @Override
    public Optional<StoredDelivery> findDeliveryByVisit(UUID visitId) {
        return Optional.ofNullable(deliveries.get(visitId));
    }

    @Override
    public Optional<DeliveryFileObject> findFileObject(UUID fileId) {
        MutableFile file = files.get(fileId);
        return file == null ? Optional.empty() : Optional.of(file.toView());
    }

    @Override
    public Optional<StoredAcceptance> findAcceptance(UUID acceptanceId) {
        return acceptances.values().stream()
                .filter(value -> value.id().equals(acceptanceId))
                .findFirst();
    }

    @Override
    public Optional<StoredAcceptance> findAcceptanceByVisit(UUID visitId) {
        return Optional.ofNullable(acceptances.get(visitId));
    }

    @Override
    public Optional<String> findDisplayName(UUID subjectId) {
        return Optional.ofNullable(displayName);
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
            ServiceOrderStatus nextOrderStatus,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit deliveredVisit,
            StoredDelivery value,
            DeliveryIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        if (idempotency.containsKey(key(entry))) return DeliveryWriteResult.IDEMPOTENCY_EXISTS;
        if (visit(expectedVisit.id()).version() != expectedVisit.version()) {
            return DeliveryWriteResult.VERSION_CONFLICT;
        }
        applyVisit(deliveredVisit, nextOrderStatus, deliveredVisit.deliveredAt(), null);
        deliveries.put(value.visitId(), value);
        idempotency.put(key(entry), entry);
        publishCount++;
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
            StoredAcceptance value,
            DeliveryIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        if (idempotency.containsKey(key(entry))) return DeliveryWriteResult.IDEMPOTENCY_EXISTS;
        if (visit(expectedVisit.id()).version() != expectedVisit.version()) {
            return DeliveryWriteResult.VERSION_CONFLICT;
        }
        applyVisit(acceptedVisit, nextOrderStatus, null, acceptedVisit.acceptedAt());
        deliveries.put(acceptedDelivery.visitId(), acceptedDelivery);
        acceptances.put(value.visitId(), value);
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
    @Override public Optional<PlanIdempotencyEntry> findIdempotency(UUID actorId, String operation, UUID key) { return Optional.empty(); }
    @Override public int nextPlanVersion(UUID id) { return 1; }
    @Override public PlanWriteResult publish(PlanReservation reservation, StoredServicePlan plan, PlanIdempotencyEntry entry, CurrentSubject actor, String requestId) { return PlanWriteResult.STATE_CONFLICT; }
    @Override public PlanWriteResult confirm(StoredServicePlan plan, StoredServiceOrder order, PlanIdempotencyEntry entry, String agreementVersion, CurrentSubject actor, String requestId) { return PlanWriteResult.STATE_CONFLICT; }
}

final class MutableFile {
    final UUID id;
    final UUID visitId;
    final DeliveryFileType type;
    final String contentType;
    final long sizeBytes;
    final String bucket;
    final String objectKey;
    FileObjectStatus status;

    MutableFile(
            UUID id,
            UUID visitId,
            DeliveryFileType type,
            String contentType,
            long sizeBytes,
            String bucket,
            String objectKey,
            FileObjectStatus status
    ) {
        this.id = id;
        this.visitId = visitId;
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
                visitId,
                PROVIDER_ID(),
                type,
                contentType,
                sizeBytes,
                bucket,
                objectKey,
                status);
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
