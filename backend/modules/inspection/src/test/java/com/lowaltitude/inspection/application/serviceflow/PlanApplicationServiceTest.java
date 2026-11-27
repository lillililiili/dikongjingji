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

import com.lowaltitude.inspection.domain.files.DeliveryFileType;
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
    void convertedDemandRequiresChosenQuoteAmountForEveryPublishedEdition() {
        store.chosenAmount = 2_400L;
        assertThatThrownBy(() -> service.publish(provider(PROVIDER), RESERVATION, PUBLISH_KEY, 0,
                command("金额不符", 101), "publish"))
                .isInstanceOfSatisfying(PlanFlowException.class,
                        error -> assertThat(error.error()).isEqualTo(PlanFlowException.PlanFlowError.QUOTE_AMOUNT_MISMATCH));
        assertThat(store.publishCount).isZero();
        service.publish(provider(PROVIDER), RESERVATION, PUBLISH_KEY, 0, command("首版", 100), "publish");
        assertThatThrownBy(() -> service.publish(provider(PROVIDER), RESERVATION, UUID.randomUUID(), 1,
                command("改版仍需同价", 101), "republish"))
                .isInstanceOfSatisfying(PlanFlowException.class,
                        error -> assertThat(error.error()).isEqualTo(PlanFlowException.PlanFlowError.QUOTE_AMOUNT_MISMATCH));
        assertThat(store.publishCount).isOne();
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
    void publisherReadsTheCurrentEditionOnlyForItsAssignedReservation() {
        assertThatThrownBy(() -> service.currentForProvider(provider(PROVIDER), RESERVATION))
                .isInstanceOf(PlanResourceNotFoundException.class);
        ServicePlanView published = service.publish(provider(PROVIDER), RESERVATION, PUBLISH_KEY, 0,
                command("现行方案", 100), "publish");
        assertThat(service.currentForProvider(provider(PROVIDER), RESERVATION)).isEqualTo(published);
        assertThatThrownBy(() -> service.currentForProvider(provider(UUID.randomUUID()), RESERVATION))
                .isInstanceOf(PlanResourceNotFoundException.class);
        assertThatThrownBy(() -> service.currentForProvider(customer(CUSTOMER), RESERVATION))
                .isInstanceOf(PlanForbiddenException.class);
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
    void reusingAPublishKeyOnAnotherReservationConflicts() {
        service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("首次方案", 100),
                "publish-first");

        UUID otherReservation = useAnotherReservation();

        assertThatThrownBy(() -> service.publish(
                provider(PROVIDER),
                otherReservation,
                PUBLISH_KEY,
                0,
                command("首次方案", 100),
                "publish-second"))
                .isInstanceOf(PlanIdempotencyConflictException.class);
    }

    @Test
    void reusingAConfirmKeyOnAnotherPlanConflicts() {
        ConfirmPlanCommand agreement = new ConfirmPlanCommand("inspection-plan-v1", true);
        ServicePlanView first = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("首次方案", 100),
                "publish-first");
        service.confirm(customer(CUSTOMER), first.id(), CONFIRM_KEY, 0, agreement, "confirm-first");

        UUID otherReservation = useAnotherReservation();
        ServicePlanView second = service.publish(
                provider(PROVIDER),
                otherReservation,
                UUID.randomUUID(),
                0,
                command("另一份方案", 100),
                "publish-other");

        assertThatThrownBy(() -> service.confirm(
                customer(CUSTOMER),
                second.id(),
                CONFIRM_KEY,
                0,
                agreement,
                "confirm-second"))
                .isInstanceOf(PlanIdempotencyConflictException.class);
    }

    @Test
    void deliveryTermsStayWithinTheContractLimits() {
        assertThat(service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                withTerms(new DeliveryTerms(8_760, 3_650)),
                "publish-limit").terms().deliveryTerms()).isEqualTo(new DeliveryTerms(8_760, 3_650));

        for (DeliveryTerms invalid : List.of(
                new DeliveryTerms(8_761, 90),
                new DeliveryTerms(48, 3_651))) {
            assertThatThrownBy(() -> service.publish(
                    provider(PROVIDER),
                    RESERVATION,
                    UUID.randomUUID(),
                    1,
                    withTerms(invalid),
                    "publish-over-limit"))
                    .isInstanceOf(PlanValidationException.class);
        }
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

    @Test
    void planRecordsItsVisitsAndTermsAndUsesTheFirstDateAsServiceDate() {
        ServicePlanView plan = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command(
                        "季度巡检",
                        100,
                        List.of(
                                LocalDate.of(2026, 8, 2),
                                LocalDate.of(2026, 8, 16),
                                LocalDate.of(2026, 9, 1))),
                "publish-visits");

        assertThat(plan.serviceDate()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(plan.terms().visits()).extracting(PlanVisit::visitNo).containsExactly(1, 2, 3);
        assertThat(plan.terms().scopeSummary()).isEqualTo("约 42 万㎡");
        assertThat(plan.terms().serviceMethods()).extracting(CodeLabel::code)
                .containsExactly("DRONE");
        assertThat(plan.terms().deliveryTerms()).isEqualTo(new DeliveryTerms(48, 90));
        assertThat(plan.terms().paymentNodes()).isEmpty();
    }

    @Test
    void planNamesTheCustomerOfItsReservation() {
        store.customerParty = new CustomerParty("东营港务物流", "王女士");

        ServicePlanView plan = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("季度巡检", 100),
                "publish-party");

        assertThat(plan.customerParty()).isEqualTo(new CustomerParty("东营港务物流", "王女士"));
    }

    @Test
    void visitsMustBeInTheFutureStrictlyAscendingAndBounded() {
        LocalDate today = LocalDate.of(2026, 7, 28);
        assertThat(service.publish(
                provider(PROVIDER),
                RESERVATION,
                UUID.randomUUID(),
                0,
                command("今天开始", 100, List.of(today)),
                "publish-today").serviceDate()).isEqualTo(today);

        for (List<LocalDate> invalid : List.of(
                List.<LocalDate>of(),
                List.of(today.minusDays(1)),
                List.of(today.plusDays(2), today.plusDays(1)),
                List.of(today.plusDays(1), today.plusDays(1)),
                java.util.stream.IntStream.range(1, 54)
                        .mapToObj(today::plusDays)
                        .toList())) {
            assertThatThrownBy(() -> service.publish(
                    provider(PROVIDER),
                    RESERVATION,
                    UUID.randomUUID(),
                    1,
                    command("非法日期", 100, invalid),
                    "publish-bad-visits"))
                    .isInstanceOf(PlanValidationException.class);
        }
    }

    @Test
    void deliverablesNeedOneItemAndCannotRepeatAFileType() {
        ServicePlanView single = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command(
                        "只交报告",
                        List.of(new DeliverableInput(
                                "REPORT",
                                "巡检报告",
                                DeliveryFileType.INSPECTION_REPORT))),
                "publish-one-deliverable");
        assertThat(single.deliverables()).hasSize(1);

        assertThatThrownBy(() -> service.publish(
                provider(PROVIDER),
                RESERVATION,
                UUID.randomUUID(),
                1,
                command("没有交付物", List.of()),
                "publish-no-deliverable"))
                .isInstanceOf(PlanValidationException.class);
        assertThatThrownBy(() -> service.publish(
                provider(PROVIDER),
                RESERVATION,
                UUID.randomUUID(),
                1,
                command(
                        "重复类型",
                        List.of(
                                new DeliverableInput(
                                        "REPORT",
                                        "巡检报告",
                                        DeliveryFileType.INSPECTION_REPORT),
                                new DeliverableInput(
                                        "REPORT_2",
                                        "复核报告",
                                        DeliveryFileType.INSPECTION_REPORT))),
                "publish-repeat-type"))
                .isInstanceOf(PlanValidationException.class);
    }

    @Test
    void changedVisitsCannotReusePublishIdempotencyKey() {
        service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("首次方案", 100, List.of(LocalDate.of(2026, 8, 2))),
                "publish-key");

        assertThatThrownBy(() -> service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                1,
                command("首次方案", 100, List.of(LocalDate.of(2026, 8, 3))),
                "publish-key-conflict"))
                .isInstanceOf(PlanIdempotencyConflictException.class);
    }

    @Test
    void confirmationCopiesEveryPlannedVisitIntoTheOrder() {
        ServicePlanView plan = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command(
                        "两次巡检",
                        100,
                        List.of(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 16))),
                "publish-two");

        ServiceOrderView order = service.confirm(
                customer(CUSTOMER),
                plan.id(),
                CONFIRM_KEY,
                0,
                new ConfirmPlanCommand("inspection-plan-v2", true),
                "confirm-two");

        assertThat(order.status()).isEqualTo(ServiceOrderStatus.ARRANGING);
        assertThat(order.paymentMode()).isEqualTo(PaymentMode.NONE);
        assertThat(order.paymentNodes()).isEmpty();
        assertThat(order.visits()).extracting(StoredServiceVisit::visitNo).containsExactly(1, 2);
        assertThat(order.visits()).allSatisfy(visit -> {
            assertThat(visit.status()).isEqualTo(ServiceVisitStatus.PENDING_SCHEDULE);
            assertThat(visit.serviceOrderId()).isEqualTo(order.id());
            assertThat(visit.id()).isNotEqualTo(order.id());
        });
        assertThat(order.visits().get(1).plannedDate()).isEqualTo(LocalDate.of(2026, 8, 16));
    }

    /** 只回答服务商能不能收款的付款端口。 */
    private static InspectionPaymentPort collecting(boolean canCollect) {
        InspectionPaymentPort unavailable = InspectionPaymentPort.unavailable();
        return new InspectionPaymentPort() {
            @Override
            public boolean merchantCanCollect(UUID organizationId) {
                return canCollect && PROVIDER.equals(organizationId);
            }

            @Override
            public OpenedPayment open(OpenPayment request) {
                return unavailable.open(request);
            }

            @Override
            public void lockOrder(UUID tradeOrderId) {
                unavailable.lockOrder(tradeOrderId);
            }

            @Override
            public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID key, UUID actorId) {
                return unavailable.requestRefund(tradeOrderId, amountMinor, reason, key, actorId);
            }

            @Override
            public java.util.Map<UUID, RefundProgress> refundStates(java.util.Collection<UUID> refundIds) {
                return java.util.Map.of();
            }

            @Override
            public boolean cancelUnpaid(UUID accountId, UUID tradeOrderId) {
                return unavailable.cancelUnpaid(accountId, tradeOrderId);
            }
        };
    }

    // D9：服务商没有登记收款账户时，带付款节点的方案不能确认，什么都不写。
    @Test
    void aPlanWithPaymentNodesCannotBeConfirmedWhenTheProviderCannotCollect() {
        service = new PlanApplicationService(store, Clock.fixed(NOW, ZoneOffset.UTC), true, collecting(false));
        ServicePlanView plan = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("按次付款", 100, List.of(LocalDate.of(2026, 8, 2))),
                "publish-nodes");

        assertThatThrownBy(() -> service.confirm(
                customer(CUSTOMER),
                plan.id(),
                CONFIRM_KEY,
                0,
                new ConfirmPlanCommand("inspection-plan-v2", true),
                "confirm-nodes"))
                .isInstanceOf(ProviderPaymentUnavailableException.class);
        assertThat(store.orders).isEmpty();
    }

    // 方案总价很小、按次分不出钱时，金额为 0 的节点确认时直接记为已付；首付为 0 时直接待安排。
    @Test
    void zeroAmountNodesArePaidOnConfirmation() {
        service = new PlanApplicationService(store, Clock.fixed(NOW, ZoneOffset.UTC), true, collecting(true));
        ServicePlanView plan = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command("按次付款", 0, List.of(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 16))),
                "publish-nodes");

        ServiceOrderView order = service.confirm(
                customer(CUSTOMER),
                plan.id(),
                CONFIRM_KEY,
                0,
                new ConfirmPlanCommand("inspection-plan-v2", true),
                "confirm-nodes");

        assertThat(order.status()).isEqualTo(ServiceOrderStatus.ARRANGING);
        assertThat(order.paymentNodes()).extracting(OrderPaymentNodeView::paidAt).containsOnly(NOW);
        assertThat(order.paymentNodes()).extracting(OrderPaymentNodeView::payable).containsOnly(false);
    }

    @Test
    void paymentNodesAreCopiedToTheOrderWhenEnabled() {
        service = new PlanApplicationService(store, Clock.fixed(NOW, ZoneOffset.UTC), true, collecting(true));
        ServicePlanView plan = service.publish(
                provider(PROVIDER),
                RESERVATION,
                PUBLISH_KEY,
                0,
                command(
                        "按次付款",
                        100,
                        List.of(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 16))),
                "publish-nodes");

        ServiceOrderView order = service.confirm(
                customer(CUSTOMER),
                plan.id(),
                CONFIRM_KEY,
                0,
                new ConfirmPlanCommand("inspection-plan-v2", true),
                "confirm-nodes");

        assertThat(plan.terms().paymentNodes()).extracting(PlanPaymentNode::amountMinor)
                .containsExactly(720L, 840L, 840L);
        assertThat(order.paymentMode()).isEqualTo(PaymentMode.NODES);
        // 首付没到账之前服务单待付款，服务商不能安排。
        assertThat(order.status()).isEqualTo(ServiceOrderStatus.AWAITING_PAYMENT);
        assertThat(order.cancellable()).isTrue();
        assertThat(order.paymentNodes()).extracting(OrderPaymentNodeView::payable)
                .containsExactly(true, false, false);
        assertThat(order.paymentNodes()).extracting(OrderPaymentNodeView::paidAt)
                .containsOnlyNulls();
        StoredServiceOrder stored = store.orders.get(order.id());
        assertThat(stored.paymentNodes().get(1).visitId())
                .isEqualTo(stored.visits().get(0).id());
        assertThat(stored.paymentNodes().get(0).visitId()).isNull();
    }

    @Test
    void customerListsPlanVersionsOfTheirOwnReservationOnly() {
        store.versions = List.of(new PlanVersionSummary(
                UUID.randomUUID(),
                "FA1",
                1,
                PlanStatus.WITHDRAWN,
                1_200_000L,
                List.of(),
                null,
                NOW));

        assertThat(service.listVersions(customer(CUSTOMER), RESERVATION))
                .extracting(PlanVersionSummary::planVersion)
                .containsExactly(1);
        assertThatThrownBy(() -> service.listVersions(customer(UUID.randomUUID()), RESERVATION))
                .isInstanceOf(PlanResourceNotFoundException.class);
        assertThatThrownBy(() -> service.listVersions(customer(CUSTOMER), UUID.randomUUID()))
                .isInstanceOf(PlanResourceNotFoundException.class);
    }

    private UUID useAnotherReservation() {
        PlanReservation source = reservation();
        UUID id = UUID.randomUUID();
        store.reservation = new PlanReservation(
                id,
                "IR202607280002",
                source.customerId(),
                source.providerId(),
                source.status(),
                source.serviceId(),
                source.serviceTitle(),
                source.serviceCoverUrl(),
                source.cityCode(),
                source.address(),
                source.longitude(),
                source.latitude(),
                source.lineLengthMeters(),
                source.inspectionItems(),
                source.version());
        return id;
    }

    private static PublishPlanCommand withTerms(DeliveryTerms terms) {
        PublishPlanCommand base = command("交付约定", 100);
        return new PublishPlanCommand(
                base.providerId(),
                base.validUntil(),
                base.visits(),
                base.summary(),
                base.scopeSummary(),
                base.serviceMethods(),
                terms,
                base.changeNote(),
                base.planItems(),
                base.deliverables(),
                base.currency());
    }

    private static PublishPlanCommand command(String summary, long unitPriceMinor) {
        return command(summary, unitPriceMinor, List.of(LocalDate.of(2026, 8, 2)));
    }

    private static PublishPlanCommand command(String summary, List<DeliverableInput> deliverables) {
        return command(summary, 100, List.of(LocalDate.of(2026, 8, 2)), deliverables);
    }

    private static PublishPlanCommand command(
            String summary,
            long unitPriceMinor,
            List<LocalDate> visits
    ) {
        return command(
                summary,
                unitPriceMinor,
                visits,
                List.of(
                        new DeliverableInput("HD_MEDIA", "高清巡检影像", null),
                        new DeliverableInput("DEFECT_LIST", "缺陷清单", null),
                        new DeliverableInput("POINT_MARKS", "点位标注", null),
                        new DeliverableInput("REPORT", "巡检报告", null)));
    }

    private static PublishPlanCommand command(
            String summary,
            long unitPriceMinor,
            List<LocalDate> visits,
            List<DeliverableInput> deliverables
    ) {
        return new PublishPlanCommand(
                PROVIDER,
                NOW.plusSeconds(86_400),
                visits,
                summary,
                "约 42 万㎡",
                List.of(new CodeLabel("DRONE", "无人机巡检")),
                new DeliveryTerms(48, 90),
                null,
                List.of(new PlanItemInput("POWER_LINE", "线路本体巡检", 24, "基", unitPriceMinor)),
                deliverables,
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

    Long chosenAmount;

    @Override
    public Optional<Long> chosenDemandAmount(UUID id) {
        return Optional.ofNullable(chosenAmount);
    }

    PlanReservation reservation;
    final Map<UUID, StoredServicePlan> plans = new HashMap<>();
    final Map<UUID, StoredServiceOrder> orders = new HashMap<>();
    final Map<String, PlanIdempotencyEntry> idempotency = new HashMap<>();
    List<PlanVersionSummary> versions = List.of();
    CustomerParty customerParty;
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
    public Optional<StoredServicePlan> findCurrentPlan(UUID reservationId) {
        return plans.values().stream()
                .filter(plan -> plan.reservation().id().equals(reservationId))
                .filter(plan -> plan.status() == PlanStatus.PUBLISHED || plan.status() == PlanStatus.CONFIRMED)
                .max(java.util.Comparator.comparingInt(StoredServicePlan::planVersion));
    }

    @Override
    public Optional<StoredServicePlan> findPlan(UUID planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    @Override
    public Optional<CustomerParty> findCustomerParty(UUID reservationId) {
        return Optional.ofNullable(customerParty);
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
    public List<PlanVersionSummary> findPlanVersions(UUID reservationId) {
        return versions;
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
                actor.subjectId(),
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
