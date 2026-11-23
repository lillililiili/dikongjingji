package com.lowaltitude.inspection.application.serviceflow;

import static com.lowaltitude.inspection.application.serviceflow.InspectionPaymentServiceTest.NOW;
import static com.lowaltitude.inspection.application.serviceflow.InspectionPaymentServiceTest.customer;
import static com.lowaltitude.inspection.application.serviceflow.InspectionPaymentServiceTest.day;
import static com.lowaltitude.inspection.application.serviceflow.InspectionPaymentServiceTest.provider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** S4-1：改期（C3）和退回整改（B2）。 */
class VisitChangeServiceTest {

    private FakeServiceFlow flow;
    private VisitChangeService service;

    @BeforeEach
    void setUp() {
        flow = new FakeServiceFlow();
        service = new VisitChangeService(flow, flow, flow, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- 改期 ----

    @Test
    void aCustomerAsksToMoveAVisitAndTheProviderApproves() {
        UUID orderId = flow.order(ServiceOrderStatus.ARRANGING, 0L, List.of(2_170L, 2_170L));
        StoredServiceVisit first = flow.visit(orderId, 1);

        VisitRescheduleRecord request = service.requestReschedule(customer(), orderId, first.id(), first.version(),
                day(4), " 园区那天封闭 ", "r");
        VisitRescheduleRecord repeated = service.requestReschedule(customer(), orderId, first.id(), first.version(),
                day(4), "园区那天封闭", "r2");

        assertThat(repeated.id()).isEqualTo(request.id());
        assertThat(request.fromDate()).isEqualTo(day(2));
        assertThat(request.reason()).isEqualTo("园区那天封闭");
        // 待处理时不能再申请别的日期。
        assertThatThrownBy(() -> service.requestReschedule(customer(), orderId, first.id(), first.version(),
                day(5), "另一个原因", "r3"))
                .isInstanceOf(RescheduleNotAllowedException.class);

        VisitRescheduleRecord approved = service.decideReschedule(provider(), orderId, first.id(), request.id(), true,
                "可以", "d");
        VisitRescheduleRecord again = service.decideReschedule(provider(), orderId, first.id(), request.id(), true,
                "可以", "d2");

        assertThat(approved.status()).isEqualTo(RescheduleStatus.APPROVED);
        assertThat(again.status()).isEqualTo(RescheduleStatus.APPROVED);
        StoredServiceVisit moved = flow.visit(orderId, 1);
        assertThat(moved.plannedDate()).isEqualTo(day(4));
        assertThat(moved.status()).isEqualTo(ServiceVisitStatus.PENDING_SCHEDULE);
        assertThat(flow.originals).containsEntry(first.id(), day(2));
        assertThatThrownBy(() -> service.decideReschedule(provider(), orderId, first.id(), request.id(), false,
                null, "d3"))
                .isInstanceOf(RescheduleStateInvalidException.class);
    }

    @Test
    void aRejectedRequestLeavesTheVisitAsItWas() {
        UUID orderId = flow.order(ServiceOrderStatus.ARRANGING, 0L, List.of(2_170L));
        StoredServiceVisit first = flow.visit(orderId, 1);
        VisitRescheduleRecord request = service.requestReschedule(customer(), orderId, first.id(), first.version(),
                day(5), "临时出差", "r");

        VisitRescheduleRecord rejected = service.decideReschedule(provider(), orderId, first.id(), request.id(),
                false, "那天设备已排满", "d");

        assertThat(rejected.status()).isEqualTo(RescheduleStatus.REJECTED);
        assertThat(rejected.decisionNote()).isEqualTo("那天设备已排满");
        assertThat(flow.visit(orderId, 1).plannedDate()).isEqualTo(day(2));
        assertThat(flow.originals).isEmpty();
    }

    @Test
    void moveRequestsCloseTwentyFourHoursBeforeTheVisitStarts() {
        UUID orderId = flow.order(ServiceOrderStatus.ARRANGING, 0L, List.of(2_170L));
        StoredServiceVisit first = flow.visit(orderId, 1);
        flow.scheduleVisit(orderId, 1, NOW.plus(Duration.ofHours(23)));
        StoredServiceVisit scheduled = flow.visit(orderId, 1);

        assertThatThrownBy(() -> service.requestReschedule(customer(), orderId, first.id(), scheduled.version(),
                day(5), "来不及", "r"))
                .isInstanceOf(RescheduleNotAllowedException.class);
        assertThat(PaymentRules.reschedulable(flow.orders.get(orderId), scheduled, NOW)).isFalse();
        assertThat(PaymentRules.reschedulable(flow.orders.get(orderId), scheduled,
                NOW.minus(Duration.ofHours(2)))).isTrue();
    }

    @Test
    void theNewDateMustBeInTheFutureAndKeepTheVisitsInOrder() {
        UUID orderId = flow.order(ServiceOrderStatus.ARRANGING, 0L, List.of(2_170L, 2_170L, 2_170L));
        StoredServiceVisit second = flow.visit(orderId, 2);

        for (var date : List.of(day(0), day(2), day(9), day(16), day(20))) {
            assertThatThrownBy(() -> service.requestReschedule(customer(), orderId, second.id(), second.version(),
                    date, "换日子", "r"))
                    .as(date.toString())
                    .isInstanceOf(PlanValidationException.class);
        }
        assertThat(service.requestReschedule(customer(), orderId, second.id(), second.version(), day(12), "换日子",
                "r").requestedDate()).isEqualTo(day(12));
    }

    @Test
    void anOrderAwaitingItsDownPaymentOrCancelledCannotBeRescheduled() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        StoredServiceVisit first = flow.visit(orderId, 1);

        assertThatThrownBy(() -> service.requestReschedule(customer(), orderId, first.id(), first.version(), day(5),
                "换日子", "r"))
                .isInstanceOf(RescheduleNotAllowedException.class);
    }

    @Test
    void onlyTheProviderOfTheOrderDecides() {
        UUID orderId = flow.order(ServiceOrderStatus.ARRANGING, 0L, List.of(2_170L));
        StoredServiceVisit first = flow.visit(orderId, 1);
        VisitRescheduleRecord request = service.requestReschedule(customer(), orderId, first.id(), first.version(),
                day(5), "换日子", "r");

        assertThatThrownBy(() -> service.decideReschedule(customer(), orderId, first.id(), request.id(), true, null,
                "d"))
                .isInstanceOf(PlanForbiddenException.class);
        assertThatThrownBy(() -> service.decideReschedule(provider(), orderId, UUID.randomUUID(), request.id(), true,
                null, "d"))
                .isInstanceOf(PlanResourceNotFoundException.class);
    }

    // ---- 退回整改 ----

    @Test
    void aDeliveredVisitIsReturnedForReworkOnce() {
        UUID orderId = flow.order(ServiceOrderStatus.DELIVERED, 0L, List.of(4_200L));
        flow.startVisit(orderId, 1);
        flow.deliverVisit(orderId, 1);
        StoredServiceVisit delivered = flow.visit(orderId, 1);

        VisitReworkRecord rework = service.returnForRework(customer(), orderId, delivered.id(), delivered.version(),
                " 第 3 段线路没有拍到 ", "w");
        VisitReworkRecord repeated = service.returnForRework(customer(), orderId, delivered.id(), 0,
                "第 3 段线路没有拍到", "w2");

        assertThat(repeated.id()).isEqualTo(rework.id());
        assertThat(rework.reason()).isEqualTo("第 3 段线路没有拍到");
        assertThat(flow.visit(orderId, 1).status()).isEqualTo(ServiceVisitStatus.REWORK);
        assertThat(flow.orders.get(orderId).status()).isEqualTo(ServiceOrderStatus.IN_PROGRESS);
        assertThat(flow.findDeliveryByVisit(delivered.id()).orElseThrow().status())
                .isEqualTo(DeliveryStatus.RETURNED);
        // 换了原因不是重放，交付已经退回了。
        assertThatThrownBy(() -> service.returnForRework(customer(), orderId, delivered.id(), 0, "另一个原因", "w3"))
                .isInstanceOf(DeliveryStateInvalidException.class);
    }

    @Test
    void onlyADeliveredVisitCanBeReturnedAndTheReasonIsRequired() {
        UUID orderId = flow.order(ServiceOrderStatus.IN_PROGRESS, 0L, List.of(4_200L));
        flow.startVisit(orderId, 1);
        StoredServiceVisit started = flow.visit(orderId, 1);

        assertThatThrownBy(() -> service.returnForRework(customer(), orderId, started.id(), started.version(), "不行",
                "w"))
                .isInstanceOf(DeliveryStateInvalidException.class);
        assertThatThrownBy(() -> service.returnForRework(customer(), orderId, started.id(), started.version(), " ",
                "w"))
                .isInstanceOf(PlanValidationException.class);
        assertThatThrownBy(() -> service.returnForRework(customer(), orderId, started.id(), started.version(),
                "x".repeat(501), "w"))
                .isInstanceOf(PlanValidationException.class);
    }

    @Test
    void reworkCountsAsStartedButNotDelivered() {
        StoredServiceVisit rework = new StoredServiceVisit(UUID.randomUUID(), UUID.randomUUID(), 1, day(2), null,
                ServiceVisitStatus.REWORK, List.of(), NOW, null, null, 1);

        assertThat(ServiceOrderProgression.derive(List.of(rework))).isEqualTo(ServiceOrderStatus.IN_PROGRESS);
    }
}
