package com.lowaltitude.inspection.application.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
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

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T03:00:00Z");
    private static final Instant START = Instant.parse("2026-08-02T01:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID VISIT_1 = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID VISIT_2 = UUID.fromString("61000000-0000-4000-8000-000000000002");

    private InMemoryOrderStore store;
    private OrderApplicationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryOrderStore(order(ServiceOrderStatus.ARRANGING));
        service = new OrderApplicationService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void assignedProviderArrangesTeamAndDeviceIdempotently() {
        UUID key = UUID.randomUUID();
        PutArrangementCommand command = arrangement();

        ServiceOrderView arranged = service.arrange(
                arranger(),
                ORDER,
                key,
                0,
                command,
                "arrange-1");
        ServiceOrderView replay = service.arrange(
                arranger(),
                ORDER,
                key,
                0,
                command,
                "arrange-1-replay");

        assertThat(replay.id()).isEqualTo(arranged.id());
        assertThat(arranged.status()).isEqualTo(ServiceOrderStatus.ARRANGING);
        assertThat(arranged.assignments()).extracting(OrderAssignment::type)
                .containsExactly(AssignmentType.TEAM_MEMBER, AssignmentType.DEVICE);
        assertThat(arranged.progress()).extracting(OrderProgressEvent::eventCode)
                .containsExactly(OrderProgressCode.ARRANGEMENT_READY);
        assertThat(arranged.version()).isEqualTo(1);
        assertThat(store.arrangeCount).isEqualTo(1);
    }

    @Test
    void reusingAnArrangementKeyOnAnotherOrderConflicts() {
        UUID key = UUID.randomUUID();
        service.arrange(arranger(), ORDER, key, 0, arrangement(), "arrange-first");

        StoredServiceOrder source = order(ServiceOrderStatus.ARRANGING);
        UUID otherOrder = UUID.randomUUID();
        store.order = new StoredServiceOrder(
                otherOrder,
                "FW202607280002",
                source.reservationId(),
                source.planId(),
                source.customerId(),
                source.providerId(),
                source.paymentMode(),
                source.status(),
                source.serviceSnapshot(),
                source.visits(),
                source.paymentNodes(),
                source.createdAt(),
                source.version());

        assertThatThrownBy(() -> service.arrange(
                arranger(),
                otherOrder,
                key,
                0,
                arrangement(),
                "arrange-second"))
                .isInstanceOf(PlanIdempotencyConflictException.class);
    }

    @Test
    void contactMobileOnlyReachesTheStoreAndViewsCarryTheMask() {
        ServiceOrderView arranged = service.arrange(
                arranger(),
                ORDER,
                UUID.randomUUID(),
                0,
                arrangement(),
                "arrange-mobile");

        assertThat(arranged.assignments().get(0).contactMobileMasked())
                .isEqualTo("138****6621");
        assertThat(arranged.assignments().get(1).contactMobileMasked()).isNull();
        assertThat(store.lastAssignments.get(0).contactMobile()).isEqualTo("13812346621");
    }

    @Test
    void invalidContactMobileIsRejected() {
        PutArrangementCommand invalid = new PutArrangementCommand(List.of(
                new AssignmentInput(
                        AssignmentType.TEAM_MEMBER,
                        UUID.randomUUID(),
                        "王工",
                        "项目负责人",
                        "12345"),
                new AssignmentInput(
                        AssignmentType.DEVICE,
                        UUID.randomUUID(),
                        "M350 RTK",
                        null,
                        null)));

        assertThatThrownBy(() -> service.arrange(
                arranger(),
                ORDER,
                UUID.randomUUID(),
                0,
                invalid,
                "arrange-bad-mobile"))
                .isInstanceOf(PlanValidationException.class);
    }

    @Test
    void arrangementRequiresFreshVersionAndMatchingProvider() {
        assertThatThrownBy(() -> service.arrange(
                arranger(),
                ORDER,
                UUID.randomUUID(),
                3,
                arrangement(),
                "arrange-stale"))
                .isInstanceOf(PlanVersionConflictException.class);

        CurrentSubject otherProvider = new CurrentSubject(
                UUID.randomUUID(),
                Set.of("PROVIDER_OPERATOR"),
                UUID.randomUUID(),
                Set.of("inspection.order.arrange.assigned"),
                0);
        assertThatThrownBy(() -> service.arrange(
                otherProvider,
                ORDER,
                UUID.randomUUID(),
                0,
                arrangement(),
                "arrange-other"))
                .isInstanceOf(PlanResourceNotFoundException.class);
    }

    @Test
    void teamCanChangeUntilEveryVisitIsAccepted() {
        store.order = withStatus(store.order, ServiceOrderStatus.IN_PROGRESS);
        ServiceOrderView changed = service.arrange(
                arranger(),
                ORDER,
                UUID.randomUUID(),
                0,
                arrangement(),
                "arrange-in-progress");
        assertThat(changed.status()).isEqualTo(ServiceOrderStatus.IN_PROGRESS);

        store.order = withStatus(store.order, ServiceOrderStatus.ACCEPTED);
        assertThatThrownBy(() -> service.arrange(
                arranger(),
                ORDER,
                UUID.randomUUID(),
                store.order.version(),
                arrangement(),
                "arrange-after-acceptance"))
                .isInstanceOf(OrderStateInvalidException.class);
    }

    @Test
    void providerSchedulesAVisitIdempotentlyAndCanRescheduleIt() {
        UUID key = UUID.randomUUID();
        PutVisitScheduleCommand command = new PutVisitScheduleCommand(START);

        StoredServiceVisit scheduled =
                service.schedule(arranger(), ORDER, VISIT_1, key, 0, command, "schedule-1");
        StoredServiceVisit replay =
                service.schedule(arranger(), ORDER, VISIT_1, key, 0, command, "schedule-1-replay");
        StoredServiceVisit moved = service.schedule(
                arranger(),
                ORDER,
                VISIT_1,
                UUID.randomUUID(),
                scheduled.version(),
                new PutVisitScheduleCommand(START.plusSeconds(86_400)),
                "schedule-2");

        assertThat(scheduled.status()).isEqualTo(ServiceVisitStatus.SCHEDULED);
        assertThat(scheduled.plannedStartAt()).isEqualTo(START);
        assertThat(replay.id()).isEqualTo(scheduled.id());
        assertThat(moved.plannedStartAt()).isEqualTo(START.plusSeconds(86_400));
        assertThat(moved.version()).isEqualTo(2);
        assertThat(store.scheduleCount).isEqualTo(2);
    }

    @Test
    void scheduleChecksVisitVersionOwnershipAndState() {
        assertThatThrownBy(() -> service.schedule(
                arranger(),
                ORDER,
                VISIT_1,
                UUID.randomUUID(),
                7,
                new PutVisitScheduleCommand(START),
                "schedule-stale"))
                .isInstanceOf(PlanVersionConflictException.class);
        assertThatThrownBy(() -> service.schedule(
                arranger(),
                ORDER,
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                new PutVisitScheduleCommand(START),
                "schedule-unknown-visit"))
                .isInstanceOf(PlanResourceNotFoundException.class);

        readyVisit(VISIT_1);
        start(VISIT_1, 60);
        assertThatThrownBy(() -> service.schedule(
                arranger(),
                ORDER,
                VISIT_1,
                UUID.randomUUID(),
                store.visit(VISIT_1).version(),
                new PutVisitScheduleCommand(START),
                "schedule-started"))
                .isInstanceOf(VisitStateInvalidException.class);
    }

    @Test
    void progressMovesForwardAndNeverJumpsOrMovesBack() {
        readyVisit(VISIT_1);

        assertThatThrownBy(() -> appendProgress(
                VISIT_1,
                OrderProgressCode.SERVICE_COMPLETED,
                60,
                "非法跳到完成"))
                .isInstanceOf(VisitStateInvalidException.class);

        StoredServiceVisit started =
                appendProgress(VISIT_1, OrderProgressCode.SERVICE_STARTED, 60, "现场巡检开始");
        appendProgress(VISIT_1, OrderProgressCode.SERVICE_PROGRESS, 120, "完成杆塔巡检 12 基");

        assertThatThrownBy(() -> appendProgress(
                VISIT_1,
                OrderProgressCode.SERVICE_PROGRESS,
                90,
                "时间倒退"))
                .isInstanceOf(VisitStateInvalidException.class);

        StoredServiceVisit completed = appendProgress(
                VISIT_1,
                OrderProgressCode.SERVICE_COMPLETED,
                180,
                "现场巡检完成，准备交付");

        assertThat(started.status()).isEqualTo(ServiceVisitStatus.IN_PROGRESS);
        assertThat(started.startedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(completed.status()).isEqualTo(ServiceVisitStatus.IN_PROGRESS);
        assertThat(completed.progress()).extracting(OrderProgressEvent::eventCode)
                .containsExactly(
                        OrderProgressCode.SERVICE_STARTED,
                        OrderProgressCode.SERVICE_PROGRESS,
                        OrderProgressCode.SERVICE_COMPLETED);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.IN_PROGRESS);
    }

    @Test
    void completedVisitTakesNoMoreProgress() {
        readyVisit(VISIT_1);
        start(VISIT_1, 60);
        appendProgress(VISIT_1, OrderProgressCode.SERVICE_COMPLETED, 120, "现场完成");

        assertThatThrownBy(() -> appendProgress(
                VISIT_1,
                OrderProgressCode.SERVICE_PROGRESS,
                180,
                "完成后不能再推进"))
                .isInstanceOf(VisitStateInvalidException.class);
    }

    @Test
    void startRequiresScheduleAndAssignments() {
        assertThatThrownBy(() -> appendProgress(
                VISIT_1,
                OrderProgressCode.SERVICE_STARTED,
                60,
                "未排期不能开工"))
                .isInstanceOf(VisitStateInvalidException.class);

        service.schedule(
                arranger(),
                ORDER,
                VISIT_1,
                UUID.randomUUID(),
                0,
                new PutVisitScheduleCommand(START),
                "schedule-no-team");
        assertThatThrownBy(() -> appendProgress(
                VISIT_1,
                OrderProgressCode.SERVICE_STARTED,
                60,
                "没有人员设备不能开工"))
                .isInstanceOf(VisitStateInvalidException.class);
    }

    @Test
    void visitsStartInOrderButNotWaitingForAcceptance() {
        readyVisit(VISIT_1);
        service.schedule(
                arranger(),
                ORDER,
                VISIT_2,
                UUID.randomUUID(),
                0,
                new PutVisitScheduleCommand(START.plusSeconds(86_400)),
                "schedule-second");

        assertThatThrownBy(() -> start(VISIT_2, 60))
                .isInstanceOf(VisitOutOfOrderException.class);

        start(VISIT_1, 60);
        assertThatThrownBy(() -> start(VISIT_2, 120))
                .isInstanceOf(VisitOutOfOrderException.class);

        appendProgress(VISIT_1, OrderProgressCode.SERVICE_COMPLETED, 180, "第 1 次现场完成");
        StoredServiceVisit second = start(VISIT_2, 240);

        assertThat(second.status()).isEqualTo(ServiceVisitStatus.IN_PROGRESS);
        assertThat(store.order.status()).isEqualTo(ServiceOrderStatus.IN_PROGRESS);
    }

    @Test
    void firstEventOfAVisitCannotPredateTheArrangementOrThePreviousVisit() {
        readyVisit(VISIT_1);
        readyVisit(VISIT_2);

        assertThatThrownBy(() -> start(VISIT_1, -60))
                .isInstanceOf(VisitStateInvalidException.class);

        start(VISIT_1, 60);
        appendProgress(VISIT_1, OrderProgressCode.SERVICE_COMPLETED, 180, "第 1 次现场完成");

        assertThatThrownBy(() -> start(VISIT_2, 120))
                .isInstanceOf(VisitStateInvalidException.class);
        assertThat(start(VISIT_2, 180).status()).isEqualTo(ServiceVisitStatus.IN_PROGRESS);
    }

    @Test
    void onlyTheFirstStartMovesTheOrderAndVisitCommandsLeaveItsVersionAlone() {
        readyVisit(VISIT_1);
        int arrangedVersion = store.order.version();

        start(VISIT_1, 60);
        assertThat(store.orderStatusChanges).isEqualTo(1);
        assertThat(store.order.version()).isEqualTo(arrangedVersion + 1);

        int startedVersion = store.order.version();
        appendProgress(VISIT_1, OrderProgressCode.SERVICE_PROGRESS, 120, "进行中");
        appendProgress(VISIT_1, OrderProgressCode.SERVICE_COMPLETED, 180, "现场完成");
        assertThat(store.orderStatusChanges).isEqualTo(1);
        assertThat(store.order.version()).isEqualTo(startedVersion);
    }

    @Test
    void reusingAnIdempotencyKeyOnAnotherVisitConflicts() {
        UUID key = UUID.randomUUID();
        service.schedule(
                arranger(),
                ORDER,
                VISIT_1,
                key,
                0,
                new PutVisitScheduleCommand(START),
                "schedule-first");

        assertThatThrownBy(() -> service.schedule(
                arranger(),
                ORDER,
                VISIT_2,
                key,
                0,
                new PutVisitScheduleCommand(START),
                "schedule-other-visit"))
                .isInstanceOf(PlanIdempotencyConflictException.class);

        readyVisit(VISIT_2);
        store.order = completedFirstVisit(store.order);
        UUID progressKey = UUID.randomUUID();
        service.appendProgress(
                progresser(),
                ORDER,
                VISIT_2,
                progressKey,
                store.visit(VISIT_2).version(),
                new AppendProgressCommand(
                        OrderProgressCode.SERVICE_STARTED,
                        NOW.plusSeconds(60),
                        "开工"),
                "progress-first");

        assertThatThrownBy(() -> service.appendProgress(
                progresser(),
                ORDER,
                VISIT_1,
                progressKey,
                store.visit(VISIT_1).version(),
                new AppendProgressCommand(
                        OrderProgressCode.SERVICE_STARTED,
                        NOW.plusSeconds(60),
                        "开工"),
                "progress-other-visit"))
                .isInstanceOf(PlanIdempotencyConflictException.class);
    }

    @Test
    void customerProviderAndPlatformReadOnlyTheirVisibleOrder() {
        assertThat(service.read(customer(CUSTOMER), ORDER).id()).isEqualTo(ORDER);
        assertThat(service.read(
                provider(Set.of("inspection.order.read.assigned")),
                ORDER).id()).isEqualTo(ORDER);
        assertThat(service.read(platform(), ORDER).id()).isEqualTo(ORDER);

        assertThatThrownBy(() -> service.read(customer(UUID.randomUUID()), ORDER))
                .isInstanceOf(PlanResourceNotFoundException.class);
    }

    @Test
    void viewOnlyExposesNodesThatAreAlreadyPayable() {
        StoredServiceVisit first = store.order.visits().get(0);
        store.order = withVisits(
                store.order,
                List.of(
                        first.withProgress(ServiceVisitStatus.ACCEPTED, List.of(), null),
                        store.order.visits().get(1)),
                List.of(
                        new OrderPaymentNode(
                                1, PaymentNodeType.DOWN_PAYMENT, null, null, 3_000L, null),
                        new OrderPaymentNode(
                                2, PaymentNodeType.PER_VISIT, 1, VISIT_1, 3_500L, null),
                        new OrderPaymentNode(
                                3, PaymentNodeType.PER_VISIT, 2, VISIT_2, 3_500L, null)));

        assertThat(service.read(customer(CUSTOMER), ORDER).paymentNodes())
                .extracting(OrderPaymentNodeView::payable)
                .containsExactly(true, true, false);
    }

    @Test
    void paidNodesAreNoLongerPayable() {
        store.order = withVisits(
                store.order,
                store.order.visits(),
                List.of(new OrderPaymentNode(
                        1, PaymentNodeType.DOWN_PAYMENT, null, null, 3_000L, NOW)));

        OrderPaymentNodeView node = service.read(customer(CUSTOMER), ORDER).paymentNodes().get(0);

        assertThat(node.payable()).isFalse();
        assertThat(node.paidAt()).isEqualTo(NOW);
    }

    private void readyVisit(UUID visitId) {
        if (store.order.assignments().isEmpty()) {
            service.arrange(
                    arranger(),
                    ORDER,
                    UUID.randomUUID(),
                    store.order.version(),
                    arrangement(),
                    "arrange-ready");
        }
        if (store.visit(visitId).status() == ServiceVisitStatus.PENDING_SCHEDULE) {
            service.schedule(
                    arranger(),
                    ORDER,
                    visitId,
                    UUID.randomUUID(),
                    store.visit(visitId).version(),
                    new PutVisitScheduleCommand(START),
                    "schedule-ready");
        }
    }

    private StoredServiceVisit start(UUID visitId, long secondsFromNow) {
        return appendProgress(
                visitId,
                OrderProgressCode.SERVICE_STARTED,
                secondsFromNow,
                "开始作业");
    }

    private StoredServiceVisit appendProgress(
            UUID visitId,
            OrderProgressCode code,
            long secondsFromNow,
            String message
    ) {
        return service.appendProgress(
                progresser(),
                ORDER,
                visitId,
                UUID.randomUUID(),
                store.visit(visitId).version(),
                new AppendProgressCommand(code, NOW.plusSeconds(secondsFromNow), message),
                "progress");
    }

    private static PutArrangementCommand arrangement() {
        return new PutArrangementCommand(List.of(
                new AssignmentInput(
                        AssignmentType.TEAM_MEMBER,
                        UUID.fromString("70000000-0000-4000-8000-000000000001"),
                        "东营电力巡检一组",
                        "项目负责人",
                        "13812346621"),
                new AssignmentInput(
                        AssignmentType.DEVICE,
                        UUID.fromString("70000000-0000-4000-8000-000000000002"),
                        "M350 RTK + H30T",
                        null,
                        null)));
    }

    private static StoredServiceOrder order(ServiceOrderStatus status) {
        return new StoredServiceOrder(
                ORDER,
                "FW202607280001",
                UUID.randomUUID(),
                UUID.randomUUID(),
                CUSTOMER,
                PROVIDER,
                PaymentMode.NONE,
                status,
                new ServiceSnapshot(
                        UUID.randomUUID(),
                        "输电线路无人机巡检",
                        null,
                        "370500",
                        "东营市黄河路88号",
                        118.5,
                        37.4,
                        18_600L,
                        LocalDate.of(2026, 8, 2),
                        List.of(new CodeLabel("POWER_LINE", "线路本体巡检"))),
                List.of(
                        StoredServiceVisit.planned(
                                VISIT_1,
                                ORDER,
                                new PlanVisit(1, LocalDate.of(2026, 8, 2))),
                        StoredServiceVisit.planned(
                                VISIT_2,
                                ORDER,
                                new PlanVisit(2, LocalDate.of(2026, 8, 16)))),
                List.of(),
                NOW.minusSeconds(3_600),
                0);
    }

    private static StoredServiceOrder withStatus(
            StoredServiceOrder source,
            ServiceOrderStatus status
    ) {
        return new StoredServiceOrder(
                source.id(),
                source.serviceOrderNo(),
                source.reservationId(),
                source.planId(),
                source.customerId(),
                source.providerId(),
                source.paymentMode(),
                status,
                source.serviceSnapshot(),
                source.assignments(),
                source.progress(),
                source.visits(),
                source.paymentNodes(),
                source.createdAt(),
                source.deliveredAt(),
                source.acceptedAt(),
                source.version());
    }

    private static StoredServiceOrder withVisits(
            StoredServiceOrder source,
            List<StoredServiceVisit> visits,
            List<OrderPaymentNode> nodes
    ) {
        return new StoredServiceOrder(
                source.id(),
                source.serviceOrderNo(),
                source.reservationId(),
                source.planId(),
                source.customerId(),
                source.providerId(),
                PaymentMode.NODES,
                source.status(),
                source.serviceSnapshot(),
                source.assignments(),
                source.progress(),
                visits,
                nodes,
                source.createdAt(),
                source.deliveredAt(),
                source.acceptedAt(),
                source.version());
    }

    private static StoredServiceOrder completedFirstVisit(StoredServiceOrder source) {
        StoredServiceVisit first = source.visits().get(0);
        return withVisits(
                source,
                List.of(
                        first.withProgress(
                                ServiceVisitStatus.IN_PROGRESS,
                                List.of(new OrderProgressEvent(
                                        UUID.randomUUID(),
                                        OrderProgressCode.SERVICE_COMPLETED,
                                        NOW,
                                        "现场完成")),
                                NOW),
                        source.visits().get(1)),
                List.of());
    }

    private static CurrentSubject customer(UUID customerId) {
        return new CurrentSubject(
                customerId,
                Set.of("CUSTOMER"),
                UUID.randomUUID(),
                Set.of("inspection.order.read.own"),
                0);
    }

    private static CurrentSubject arranger() {
        return provider(Set.of("inspection.order.arrange.assigned"));
    }

    private static CurrentSubject progresser() {
        return provider(Set.of("inspection.order.progress.assigned"));
    }

    private static CurrentSubject provider(Set<String> permissions) {
        return new CurrentSubject(
                UUID.fromString("30000000-0000-4000-8000-000000000002"),
                Set.of("PROVIDER_OPERATOR"),
                PROVIDER,
                permissions,
                0);
    }

    private static CurrentSubject platform() {
        return new CurrentSubject(
                UUID.randomUUID(),
                Set.of("PLATFORM_OPERATOR"),
                UUID.randomUUID(),
                Set.of("inspection.order.read.all"),
                0);
    }
}

final class InMemoryOrderStore implements PlanStore {

    StoredServiceOrder order;
    private final Map<String, PlanIdempotencyEntry> idempotency = new HashMap<>();
    List<AssignmentWrite> lastAssignments = List.of();
    int arrangeCount;
    int scheduleCount;
    int orderStatusChanges;

    InMemoryOrderStore(StoredServiceOrder order) {
        this.order = order;
    }

    StoredServiceVisit visit(UUID visitId) {
        return order.visit(visitId).orElseThrow();
    }

    @Override
    public Optional<StoredServiceOrder> findOrder(UUID orderId) {
        return order.id().equals(orderId) ? Optional.of(order) : Optional.empty();
    }

    @Override
    public Optional<StoredServiceVisit> findVisit(UUID visitId) {
        return order.visit(visitId);
    }

    @Override
    public Optional<PlanIdempotencyEntry> findIdempotency(
            UUID actorId,
            String operation,
            UUID key
    ) {
        return Optional.ofNullable(idempotency.get(actorId + ":" + operation + ":" + key));
    }

    @Override
    public PlanWriteResult arrange(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder arrangedOrder,
            List<AssignmentWrite> assignments,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        String key = entry.actorId() + ":" + entry.operation() + ":" + entry.key();
        if (idempotency.containsKey(key)) return PlanWriteResult.IDEMPOTENCY_EXISTS;
        if (order.version() != expectedOrder.version()) return PlanWriteResult.VERSION_CONFLICT;
        order = arrangedOrder;
        lastAssignments = assignments;
        idempotency.put(key, entry);
        arrangeCount++;
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult schedule(
            StoredServiceOrder expectedOrder,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit scheduledVisit,
            PlanIdempotencyEntry entry,
            Instant at,
            CurrentSubject actor,
            String requestId
    ) {
        String key = entry.actorId() + ":" + entry.operation() + ":" + entry.key();
        if (idempotency.containsKey(key)) return PlanWriteResult.IDEMPOTENCY_EXISTS;
        replaceVisit(scheduledVisit, order.status(), order.version());
        idempotency.put(key, entry);
        scheduleCount++;
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult appendProgress(
            StoredServiceOrder expectedOrder,
            ServiceOrderStatus nextOrderStatus,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit updatedVisit,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        String key = entry.actorId() + ":" + entry.operation() + ":" + entry.key();
        if (idempotency.containsKey(key)) return PlanWriteResult.IDEMPOTENCY_EXISTS;
        boolean statusChanged = order.status() != nextOrderStatus;
        orderStatusChanges += statusChanged ? 1 : 0;
        replaceVisit(
                updatedVisit,
                nextOrderStatus,
                statusChanged ? order.version() + 1 : order.version());
        idempotency.put(key, entry);
        return PlanWriteResult.SAVED;
    }

    private void replaceVisit(
            StoredServiceVisit updated,
            ServiceOrderStatus status,
            int version
    ) {
        List<StoredServiceVisit> visits = new ArrayList<>(order.visits());
        visits.replaceAll(visit -> visit.id().equals(updated.id()) ? updated : visit);
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
                List.copyOf(visits),
                order.paymentNodes(),
                order.createdAt(),
                order.deliveredAt(),
                order.acceptedAt(),
                version);
    }

    @Override public Optional<PlanReservation> findReservation(UUID id) { return Optional.empty(); }
    @Override public Optional<StoredServicePlan> findPlan(UUID id) { return Optional.empty(); }
    @Override public int nextPlanVersion(UUID id) { return 1; }
    @Override public PlanWriteResult publish(PlanReservation reservation, StoredServicePlan plan, PlanIdempotencyEntry entry, CurrentSubject actor, String requestId) { return PlanWriteResult.STATE_CONFLICT; }
    @Override public PlanWriteResult confirm(StoredServicePlan plan, StoredServiceOrder order, PlanIdempotencyEntry entry, String agreementVersion, CurrentSubject actor, String requestId) { return PlanWriteResult.STATE_CONFLICT; }
}
