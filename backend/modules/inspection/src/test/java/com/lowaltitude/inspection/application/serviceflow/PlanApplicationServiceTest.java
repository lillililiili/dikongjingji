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

class PlanApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T02:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER_OPERATOR =
            UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID RESERVATION = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID PUBLISH_KEY = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID CONFIRM_KEY = UUID.fromString("60000000-0000-4000-8000-000000000001");

    private InMemoryPlanStore store;
    private PlanApplicationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryPlanStore(reservation());
        service = new PlanApplicationService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void assignedProviderPublishesAndReplaysOneCurrentPlan() {
        ServicePlanView first = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("首次方案", 100),
                "publish-1");
        ServicePlanView replay = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("首次方案", 100),
                "publish-1-replay");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(first.status()).isEqualTo(PlanStatus.PUBLISHED);
        assertThat(first.planVersion()).isEqualTo(1);
        assertThat(first.totalAmountMinor()).isEqualTo(2_400L);
        assertThat(store.publishCount).isEqualTo(1);
        assertThat(store.reservation.status()).isEqualTo(ReservationPlanStatus.PLAN_PUBLISHED);
        assertThat(store.reservation.version()).isEqualTo(1);
    }

    @Test
    void changedRequestCannotReusePublishIdempotencyKey() {
        service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("首次方案", 100),
                "publish-2");

        assertThatThrownBy(() -> service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                1,
                command("修改报价", 101),
                "publish-2-conflict"))
                .isInstanceOf(PlanIdempotencyConflictException.class);
    }

    @Test
    void publishingReplacementWithdrawsOldVersionAndRequiresFreshReservationVersion() {
        ServicePlanView first = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("首次方案", 100),
                "publish-3");

        assertThatThrownBy(() -> service.publish(
                provider(PROVIDER),
                RESERVATION,
                UUID.randomUUID(),
                0,
                command("第二版", 120),
                "publish-3-stale"))
                .isInstanceOf(PlanVersionConflictException.class);

        ServicePlanView second = service.publish(
                provider(PROVIDER),
                RESERVATION,
                UUID.randomUUID(),
                1,
                command("第二版", 120),
                "publish-3-v2");

        assertThat(second.planVersion()).isEqualTo(2);
        assertThat(store.plans.get(first.id()).status()).isEqualTo(PlanStatus.WITHDRAWN);
        assertThat(store.plans.values().stream()
                .filter(plan -> plan.status() == PlanStatus.PUBLISHED))
                .hasSize(1);
    }

    @Test
    void providerCannotPublishForAnotherOrganization() {
        assertThatThrownBy(() -> service.publish(
                provider(UUID.randomUUID()),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("越权方案", 100),
                "publish-4"))
                .isInstanceOf(PlanResourceNotFoundException.class);

        CurrentSubject missingPermission = new CurrentSubject(
                UUID.randomUUID(),
                Set.of("PROVIDER_OPERATOR"),
                PROVIDER,
                Set.of(),
                0);
        assertThatThrownBy(() -> service.publish(
                missingPermission,
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("无权限方案", 100),
                "publish-4-forbidden"))
                .isInstanceOf(PlanForbiddenException.class);
    }

    @Test
    void customerReadsAndConfirmsPublishedPlanOnce() {
        ServicePlanView plan = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("待确认方案", 100),
                "publish-5");

        ServicePlanView read = service.read(customer(CUSTOMER), plan.id());
        ServiceOrderView order = service.confirm(
                customer(CUSTOMER),
                plan.id(),
                CONFIRM_KEY,
                0,
                new ConfirmPlanCommand("inspection-plan-v1", true),
                "confirm-5");
        ServiceOrderView replay = service.confirm(
                customer(CUSTOMER),
                plan.id(),
                CONFIRM_KEY,
                0,
                new ConfirmPlanCommand("inspection-plan-v1", true),
                "confirm-5-replay");

        assertThat(read.id()).isEqualTo(plan.id());
        assertThat(order.id()).isEqualTo(replay.id());
        assertThat(order.status()).isEqualTo(ServiceOrderStatus.ARRANGING);
        assertThat(order.serviceSnapshot().address()).isEqualTo("东营市黄河路88号");
        assertThat(store.plans.get(plan.id()).status()).isEqualTo(PlanStatus.CONFIRMED);
        assertThat(store.confirmCount).isEqualTo(1);
    }

    @Test
    void confirmationRejectsOtherCustomerExpiredPlanAndStaleVersion() {
        ServicePlanView plan = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("待确认方案", 100),
                "publish-6");

        assertThatThrownBy(() -> service.confirm(
                customer(UUID.randomUUID()),
                plan.id(),
                CONFIRM_KEY,
                0,
                new ConfirmPlanCommand("inspection-plan-v1", true),
                "confirm-other"))
                .isInstanceOf(PlanResourceNotFoundException.class);

        assertThatThrownBy(() -> service.confirm(
                customer(CUSTOMER),
                plan.id(),
                CONFIRM_KEY,
                8,
                new ConfirmPlanCommand("inspection-plan-v1", true),
                "confirm-stale"))
                .isInstanceOf(PlanVersionConflictException.class);

        store.plans.put(plan.id(), store.plans.get(plan.id()).withValidUntil(NOW.minusSeconds(1)));
        assertThatThrownBy(() -> service.confirm(
                customer(CUSTOMER),
                plan.id(),
                UUID.randomUUID(),
                0,
                new ConfirmPlanCommand("inspection-plan-v1", true),
                "confirm-expired"))
                .isInstanceOf(PlanExpiredException.class);
    }

    private static PublishPlanCommand command(String summary, long unitPriceMinor) {
        return new PublishPlanCommand(
                PROVIDER,
                NOW.plusSeconds(86_400),
                LocalDate.of(2026, 8, 2),
                summary,
                List.of(new PlanItemInput("POWER_LINE", "线路本体巡检", 24, "基", unitPriceMinor)),
                List.of(
                        new DeliverableInput("HD_MEDIA", "高清巡检影像"),
                        new DeliverableInput("DEFECT_LIST", "缺陷清单"),
                        new DeliverableInput("POINT_MARKS", "点位标注"),
                        new DeliverableInput("REPORT", "巡检报告")),
                "CNY");
    }

    private static PlanReservation reservation() {
        return new PlanReservation(
                RESERVATION,
                "IR202607280001",
                CUSTOMER,
                PROVIDER,
                ReservationPlanStatus.SUBMITTED,
                UUID.fromString("70000000-0000-4000-8000-000000000001"),
                "输电线路无人机巡检",
                "/static/images/inspection/inspection-hero-v1.png",
                "370500",
                "东营市黄河路88号",
                118.5,
                37.4,
                18_600L,
                List.of(new CodeLabel("POWER_LINE", "线路本体巡检")),
                0);
    }

    private static CurrentSubject provider(UUID organizationId) {
        return new CurrentSubject(
                PROVIDER_OPERATOR,
                Set.of("PROVIDER_OPERATOR"),
                organizationId,
                Set.of("inspection.plan.publish.assigned"),
                0);
    }

    private static CurrentSubject customer(UUID customerId) {
        return new CurrentSubject(
                customerId,
                Set.of("CUSTOMER"),
                UUID.randomUUID(),
                Set.of("inspection.plan.read.own", "inspection.plan.confirm.own"),
                0);
    }
}

final class InMemoryPlanStore implements PlanStore {

    PlanReservation reservation;
    final Map<UUID, StoredServicePlan> plans = new HashMap<>();
    final Map<UUID, StoredServiceOrder> orders = new HashMap<>();
    final Map<String, PlanIdempotencyEntry> idempotency = new HashMap<>();
    int publishCount;
    int confirmCount;

    InMemoryPlanStore(PlanReservation reservation) {
        this.reservation = reservation;
    }

    @Override
    public Optional<PlanReservation> findReservation(UUID reservationId) {
        return reservation.id().equals(reservationId) ? Optional.of(reservation) : Optional.empty();
    }

    @Override
    public Optional<StoredServicePlan> findPlan(UUID planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    @Override
    public Optional<StoredServiceOrder> findOrder(UUID orderId) {
        return Optional.ofNullable(orders.get(orderId));
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
    public int nextPlanVersion(UUID reservationId) {
        return plans.values().stream()
                .filter(plan -> plan.reservation().id().equals(reservationId))
                .mapToInt(StoredServicePlan::planVersion)
                .max()
                .orElse(0) + 1;
    }

    @Override
    public PlanWriteResult publish(
            PlanReservation expectedReservation,
            StoredServicePlan plan,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        if (idempotency.containsKey(entryKey(entry))) {
            return PlanWriteResult.IDEMPOTENCY_EXISTS;
        }
        if (reservation.version() != expectedReservation.version()) {
            return PlanWriteResult.VERSION_CONFLICT;
        }
        plans.replaceAll((id, existing) ->
                existing.reservation().id().equals(reservation.id())
                                && existing.status() == PlanStatus.PUBLISHED
                        ? existing.withStatus(PlanStatus.WITHDRAWN)
                        : existing);
        plans.put(plan.id(), plan);
        reservation = reservation.withStatusAndVersion(
                ReservationPlanStatus.PLAN_PUBLISHED,
                reservation.version() + 1);
        idempotency.put(entryKey(entry), entry);
        publishCount++;
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult confirm(
            StoredServicePlan expectedPlan,
            StoredServiceOrder order,
            PlanIdempotencyEntry entry,
            String agreementVersion,
            CurrentSubject actor,
            String requestId
    ) {
        if (idempotency.containsKey(entryKey(entry))) {
            return PlanWriteResult.IDEMPOTENCY_EXISTS;
        }
        StoredServicePlan current = plans.get(expectedPlan.id());
        if (current.version() != expectedPlan.version()) {
            return PlanWriteResult.VERSION_CONFLICT;
        }
        if (current.status() != PlanStatus.PUBLISHED) {
            return PlanWriteResult.STATE_CONFLICT;
        }
        plans.put(current.id(), current.confirmed(
                Instant.parse("2026-07-28T02:00:00Z"),
                order.id()));
        orders.put(order.id(), order);
        idempotency.put(entryKey(entry), entry);
        confirmCount++;
        return PlanWriteResult.SAVED;
    }

    private static String entryKey(PlanIdempotencyEntry entry) {
        return entry.actorId() + ":" + entry.operation() + ":" + entry.key();
    }
}
