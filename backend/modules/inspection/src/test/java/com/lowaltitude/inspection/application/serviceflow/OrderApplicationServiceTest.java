package com.lowaltitude.inspection.application.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    private static final UUID CUSTOMER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("60000000-0000-4000-8000-000000000001");

    private InMemoryOrderStore store;
    private OrderApplicationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryOrderStore(order());
        service = new OrderApplicationService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void assignedProviderArrangesTeamAndDeviceIdempotently() {
        UUID key = UUID.randomUUID();
        PutArrangementCommand command = arrangement();

        ServiceOrderView arranged = service.arrange(
                provider(Set.of("inspection.order.arrange.assigned")),
                ORDER,
                key,
                0,
                command,
                "arrange-1");
        ServiceOrderView replay = service.arrange(
                provider(Set.of("inspection.order.arrange.assigned")),
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
    void arrangementRequiresFreshVersionAndMatchingProvider() {
        assertThatThrownBy(() -> service.arrange(
                provider(Set.of("inspection.order.arrange.assigned")),
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
    void progressMovesForwardAndNeverJumpsOrMovesBack() {
        ServiceOrderView arranged = service.arrange(
                provider(Set.of("inspection.order.arrange.assigned")),
                ORDER,
                UUID.randomUUID(),
                0,
                arrangement(),
                "arrange-2");

        assertThatThrownBy(() -> service.appendProgress(
                provider(Set.of("inspection.order.progress.assigned")),
                ORDER,
                UUID.randomUUID(),
                arranged.version(),
                new AppendProgressCommand(
                        OrderProgressCode.SERVICE_COMPLETED,
                        NOW.plusSeconds(60),
                        "非法跳到完成"),
                "progress-jump"))
                .isInstanceOf(OrderStateInvalidException.class);

        ServiceOrderView started = service.appendProgress(
                provider(Set.of("inspection.order.progress.assigned")),
                ORDER,
                UUID.randomUUID(),
                arranged.version(),
                new AppendProgressCommand(
                        OrderProgressCode.SERVICE_STARTED,
                        NOW.plusSeconds(60),
                        "现场巡检开始"),
                "progress-start");
        ServiceOrderView progressing = service.appendProgress(
                provider(Set.of("inspection.order.progress.assigned")),
                ORDER,
                UUID.randomUUID(),
                started.version(),
                new AppendProgressCommand(
                        OrderProgressCode.SERVICE_PROGRESS,
                        NOW.plusSeconds(120),
                        "完成杆塔巡检 12 基"),
                "progress-update");
        ServiceOrderView completed = service.appendProgress(
                provider(Set.of("inspection.order.progress.assigned")),
                ORDER,
                UUID.randomUUID(),
                progressing.version(),
                new AppendProgressCommand(
                        OrderProgressCode.SERVICE_COMPLETED,
                        NOW.plusSeconds(180),
                        "现场巡检完成，准备交付"),
                "progress-complete");

        assertThat(started.status()).isEqualTo(ServiceOrderStatus.IN_PROGRESS);
        assertThat(completed.status()).isEqualTo(ServiceOrderStatus.IN_PROGRESS);
        assertThat(completed.progress()).extracting(OrderProgressEvent::eventCode)
                .containsExactly(
                        OrderProgressCode.ARRANGEMENT_READY,
                        OrderProgressCode.SERVICE_STARTED,
                        OrderProgressCode.SERVICE_PROGRESS,
                        OrderProgressCode.SERVICE_COMPLETED);
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

    private static PutArrangementCommand arrangement() {
        return new PutArrangementCommand(
                Instant.parse("2026-08-02T01:00:00Z"),
                List.of(
                        new AssignmentInput(
                                AssignmentType.TEAM_MEMBER,
                                UUID.fromString("70000000-0000-4000-8000-000000000001"),
                                "东营电力巡检一组",
                                "项目负责人"),
                        new AssignmentInput(
                                AssignmentType.DEVICE,
                                UUID.fromString("70000000-0000-4000-8000-000000000002"),
                                "M350 RTK + H30T",
                                null)));
    }

    private static StoredServiceOrder order() {
        return new StoredServiceOrder(
                ORDER,
                "FW202607280001",
                UUID.randomUUID(),
                UUID.randomUUID(),
                CUSTOMER,
                PROVIDER,
                ServiceOrderStatus.ARRANGING,
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
                null,
                List.of(),
                List.of(),
                NOW.minusSeconds(3_600),
                null,
                null,
                0);
    }

    private static CurrentSubject customer(UUID customerId) {
        return new CurrentSubject(
                customerId,
                Set.of("CUSTOMER"),
                UUID.randomUUID(),
                Set.of("inspection.order.read.own"),
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

    private StoredServiceOrder order;
    private final Map<String, PlanIdempotencyEntry> idempotency = new HashMap<>();
    int arrangeCount;

    InMemoryOrderStore(StoredServiceOrder order) {
        this.order = order;
    }

    @Override
    public Optional<StoredServiceOrder> findOrder(UUID orderId) {
        return order.id().equals(orderId) ? Optional.of(order) : Optional.empty();
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
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        String key = entry.actorId() + ":" + entry.operation() + ":" + entry.key();
        if (idempotency.containsKey(key)) return PlanWriteResult.IDEMPOTENCY_EXISTS;
        if (order.version() != expectedOrder.version()) return PlanWriteResult.VERSION_CONFLICT;
        order = arrangedOrder;
        idempotency.put(key, entry);
        arrangeCount++;
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult appendProgress(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder updatedOrder,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        String key = entry.actorId() + ":" + entry.operation() + ":" + entry.key();
        if (idempotency.containsKey(key)) return PlanWriteResult.IDEMPOTENCY_EXISTS;
        if (order.version() != expectedOrder.version()) return PlanWriteResult.VERSION_CONFLICT;
        order = updatedOrder;
        idempotency.put(key, entry);
        return PlanWriteResult.SAVED;
    }

    @Override public Optional<PlanReservation> findReservation(UUID id) { return Optional.empty(); }
    @Override public Optional<StoredServicePlan> findPlan(UUID id) { return Optional.empty(); }
    @Override public int nextPlanVersion(UUID id) { return 1; }
    @Override public PlanWriteResult publish(PlanReservation reservation, StoredServicePlan plan, PlanIdempotencyEntry entry, CurrentSubject actor, String requestId) { return PlanWriteResult.STATE_CONFLICT; }
    @Override public PlanWriteResult confirm(StoredServicePlan plan, StoredServiceOrder order, PlanIdempotencyEntry entry, String agreementVersion, CurrentSubject actor, String requestId) { return PlanWriteResult.STATE_CONFLICT; }
}
