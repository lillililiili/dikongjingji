package com.lowaltitude.inspection.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.lowaltitude.inspection.application.outbox.InspectionProgressEvent;
import org.junit.jupiter.api.Test;

class InspectionProgressNotifierTest {

    private static final UUID EVENT = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RESERVATION = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID PLAN = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID VISIT = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final Instant AT = Instant.parse("2026-11-17T03:00:00Z");

    @Test
    void aPublishedPlanAsksTheCustomerToConfirmAndOpensThePlan() {
        NewNotification notification = InspectionProgressNotifier
                .toNotification(event("INSPECTION_PLAN_PUBLISHED", null, null, null))
                .orElseThrow();

        assertThat(notification).isEqualTo(new NewNotification(
                CUSTOMER,
                NotificationCategory.SERVICE_PROGRESS,
                "INSPECTION_PLAN_PUBLISHED",
                "方案已出",
                "光伏电站巡检服务的方案已发布，请查看并确认。",
                NotificationTargetType.INSPECTION_PLAN,
                PLAN,
                null,
                "inspection-outbox:" + EVENT,
                AT));
    }

    @Test
    void visitNoticesSayWhichVisitAndOpenTheOrderOnThatVisit() {
        NewNotification scheduled = InspectionProgressNotifier
                .toNotification(event("INSPECTION_VISIT_SCHEDULED", VISIT, 2, Instant.parse("2026-11-26T01:00:00Z")))
                .orElseThrow();
        // 北京时间。
        assertThat(scheduled.title()).isEqualTo("巡检已排期");
        assertThat(scheduled.body()).isEqualTo("光伏电站巡检服务第 2 次巡检定在 11/26 09:00 开始。");
        assertThat(scheduled.targetType()).isEqualTo(NotificationTargetType.INSPECTION_FULFILLMENT);
        assertThat(scheduled.targetId()).isEqualTo(ORDER);
        assertThat(scheduled.targetSubId()).isEqualTo(VISIT);

        NewNotification delivered = InspectionProgressNotifier
                .toNotification(event("INSPECTION_DELIVERY_PUBLISHED", VISIT, 1, null))
                .orElseThrow();
        assertThat(delivered.title()).isEqualTo("巡检报告已交付");
        assertThat(delivered.body()).isEqualTo("光伏电站巡检服务第 1 次报告已交付，请核对后验收。");
        assertThat(delivered.targetType()).isEqualTo(NotificationTargetType.INSPECTION_DELIVERY);
        assertThat(delivered.targetId()).isEqualTo(ORDER);
        assertThat(delivered.targetSubId()).isEqualTo(VISIT);

        assertThat(InspectionProgressNotifier.toNotification(event("INSPECTION_ORDER_ARRANGED", null, null, null))
                .orElseThrow().title()).isEqualTo("服务团队已安排");
        assertThat(InspectionProgressNotifier.toNotification(event("INSPECTION_VISIT_SERVICE_STARTED", VISIT, 1, null))
                .orElseThrow().body()).isEqualTo("光伏电站巡检服务第 1 次巡检已开始。");
        assertThat(InspectionProgressNotifier.toNotification(event("INSPECTION_VISIT_SERVICE_COMPLETED", VISIT, 1, null))
                .orElseThrow().title()).isEqualTo("现场巡检完成");
    }

    // 客户自己做的动作和中途进度不发通知。
    @Test
    void theCustomersOwnActionsAndInterimProgressAreNotNotified() {
        for (String type : new String[] {
                "INSPECTION_PLAN_CONFIRMED",
                "INSPECTION_DELIVERY_ACCEPTED",
                "INSPECTION_RESERVATION_CANCELLED",
                "INSPECTION_VISIT_SERVICE_PROGRESS",
                "SOMETHING_NEW"}) {
            assertThat(InspectionProgressNotifier.toNotification(event(type, VISIT, 1, null))).isEmpty();
        }
    }

    @Test
    void aNoticeWithoutARecipientOrTargetIsAnErrorNotASilentSkip() {
        InspectionProgressEvent noCustomer = new InspectionProgressEvent(
                EVENT, "INSPECTION_PLAN_PUBLISHED", AT, null, RESERVATION, "IR1", "光伏电站巡检服务",
                PLAN, null, null, null, null);
        InspectionProgressEvent noPlan = new InspectionProgressEvent(
                EVENT, "INSPECTION_PLAN_PUBLISHED", AT, CUSTOMER, RESERVATION, "IR1", "光伏电站巡检服务",
                null, null, null, null, null);

        assertThatThrownBy(() -> InspectionProgressNotifier.toNotification(noCustomer))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InspectionProgressNotifier.toNotification(noPlan))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aVeryLongOrMissingServiceNameStillFitsTheBody() {
        InspectionProgressEvent longName = new InspectionProgressEvent(
                EVENT, "INSPECTION_DELIVERY_PUBLISHED", AT, CUSTOMER, RESERVATION, "IR1", "巡".repeat(200),
                PLAN, ORDER, VISIT, 12, null);
        InspectionProgressEvent noName = new InspectionProgressEvent(
                EVENT, "INSPECTION_DELIVERY_PUBLISHED", AT, CUSTOMER, RESERVATION, "IR1", " ",
                PLAN, ORDER, VISIT, 1, null);

        assertThat(InspectionProgressNotifier.toNotification(longName).orElseThrow().body().length())
                .isLessThanOrEqualTo(300);
        assertThat(InspectionProgressNotifier.toNotification(noName).orElseThrow().body())
                .startsWith("巡检服务第 1 次报告已交付");
    }

    private static InspectionProgressEvent event(String type, UUID visitId, Integer visitNo, Instant plannedStartAt) {
        return new InspectionProgressEvent(
                EVENT, type, AT, CUSTOMER, RESERVATION, "IR1794913039913", "光伏电站巡检服务",
                PLAN, ORDER, visitId, visitNo, plannedStartAt);
    }
}
