package com.lowaltitude.inspection.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentNotifier;
import com.lowaltitude.inspection.experience.application.VenueBookingNotifier;
import com.lowaltitude.inspection.trade.application.TradeBuyerNotifier;
import com.lowaltitude.inspection.trade.application.TradeBuyerNotifier.OrderFacts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * R6-5：交易物流和活动提醒。收件人是订单、报名单、预约单的买家；文字照 Messages 画板，只写名字、人数、时间、金额；
 * 同一事件的幂等键固定；租赁、到馆预约的退款不发；业务事务提交之后才写。
 */
class BuyerNotificationsConfigurationTest {

    private static final UUID BUYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private static final UUID REFUND = UUID.fromString("f1000000-0000-4000-8000-000000000001");
    private static final UUID ENROLLMENT = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final UUID BOOKING = UUID.fromString("b6000000-0000-4000-8000-000000000001");
    private static final Instant AT = Instant.parse("2026-11-20T02:24:00Z");
    /** 北京时间 11 月 15 日 08:30。 */
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2026-11-15T00:30:00Z");

    private static OrderFacts mall(String product, int pieces) {
        return new OrderFacts(ORDER, BUYER, "MALL", null, "示例机器人商家", product, pieces);
    }

    // ---- 交易物流 ----

    @Test
    void aShippedMallOrderSaysWhatWasSentAndByWhom() {
        assertThat(BuyerNotificationsConfiguration.shippedNotice(mall("编程机器人探索套装", 1), AT)).contains(
                new NewNotification(BUYER, NotificationCategory.TRADE_LOGISTICS, "TRADE_ORDER_SHIPPED", "订单已发货",
                        "编程机器人探索套装，示例机器人商家已发出", NotificationTargetType.TRADE_ORDER, ORDER, null,
                        "trade-shipped:" + ORDER, AT));
        assertThat(BuyerNotificationsConfiguration.shippedNotice(mall("示例无人机", 3), AT).orElseThrow().body())
                .isEqualTo("示例无人机 等 3 件，示例机器人商家已发出");
    }

    @Test
    void aPaidPickupOrderCanBeCollectedButTheCodeAndPlaceStayOnTheOrderPage() {
        NewNotification notice = BuyerNotificationsConfiguration.readyForPickupNotice(mall("示例无人机", 1), AT)
                .orElseThrow();

        assertThat(notice.category()).isEqualTo(NotificationCategory.TRADE_LOGISTICS);
        assertThat(notice.eventCode()).isEqualTo("TRADE_ORDER_READY_FOR_PICKUP");
        assertThat(notice.title()).isEqualTo("可以自提了");
        assertThat(notice.body()).isEqualTo("示例无人机已付款，凭订单里的取货码到示例机器人商家自提点提货");
        assertThat(notice.targetType()).isEqualTo(NotificationTargetType.TRADE_ORDER);
        assertThat(notice.sourceKey()).isEqualTo("trade-pickup:" + ORDER);
    }

    @Test
    void aMallRefundSaysTheAmountAndOpensTheGoodsOrder() {
        assertThat(BuyerNotificationsConfiguration.refundedNotice(mall("示例无人机", 1), REFUND, 41_000, AT)).contains(
                new NewNotification(BUYER, NotificationCategory.TRADE_LOGISTICS, "TRADE_REFUND_SUCCEEDED",
                        "退款已原路退回", "示例无人机的退款 ¥410.00 已原路退回", NotificationTargetType.TRADE_ORDER,
                        ORDER, null, "trade-refund:" + REFUND, AT));
    }

    // 收费报名的退款是活动提醒“报名已退款”，去报名订单（业务单就是报名单）；租赁、到馆预约的退款不发；
    // 只有商城订单会发货、可以自提。
    @Test
    void refundsAreSortedBySourceAndOtherSourcesSayNothing() {
        OrderFacts signUp = new OrderFacts(ORDER, BUYER, "ACTIVITY", ENROLLMENT, "示例青少年科创中心",
                "青少年无人机编程挑战赛", 2);
        assertThat(BuyerNotificationsConfiguration.refundedNotice(signUp, REFUND, 9_900, AT)).contains(
                new NewNotification(BUYER, NotificationCategory.ACTIVITY_REMINDER, "ACTIVITY_REFUNDED", "报名已退款",
                        "青少年无人机编程挑战赛的报名费 ¥99.00 已原路退回", NotificationTargetType.ACTIVITY_ORDER,
                        ENROLLMENT, null, "trade-refund:" + REFUND, AT));

        for (String source : List.of("RENTAL", "VENUE")) {
            OrderFacts other = new OrderFacts(ORDER, BUYER, source, UUID.randomUUID(), "商家", "商品", 1);
            assertThat(BuyerNotificationsConfiguration.refundedNotice(other, REFUND, 100, AT)).isEmpty();
            assertThat(BuyerNotificationsConfiguration.shippedNotice(other, AT)).isEmpty();
            assertThat(BuyerNotificationsConfiguration.readyForPickupNotice(other, AT)).isEmpty();
        }
    }

    @Test
    void missingOrLongNamesAreReplacedOrCut() {
        OrderFacts unnamed = new OrderFacts(ORDER, BUYER, "MALL", null, " ", null, 0);
        assertThat(BuyerNotificationsConfiguration.shippedNotice(unnamed, AT).orElseThrow().body())
                .isEqualTo("你的订单，商家已发出");
        String longName = "长".repeat(60);
        assertThat(BuyerNotificationsConfiguration.shippedNotice(mall(longName, 1), AT).orElseThrow().body())
                .isEqualTo("长".repeat(40) + "…，示例机器人商家已发出");
        assertThat(BuyerNotificationsConfiguration.yuan(1)).isEqualTo("¥0.01");
        assertThat(BuyerNotificationsConfiguration.yuan(128_00)).isEqualTo("¥128.00");
    }

    // ---- 活动提醒 ----

    @Test
    void anEnrollmentSaysTheActivityThePeopleAndTheStartButNoNames() {
        assertThat(BuyerNotificationsConfiguration.enrolledNotice(new ActivityEnrollmentNotifier.Enrolled(
                BUYER, ENROLLMENT, "青少年无人机编程挑战赛", STARTS_AT, 2, true, AT))).isEqualTo(
                new NewNotification(BUYER, NotificationCategory.ACTIVITY_REMINDER, "ACTIVITY_ENROLLED", "报名成功",
                        "青少年无人机编程挑战赛 2 人，11/15 08:30 开始，凭证已生成",
                        NotificationTargetType.ACTIVITY_ENROLLMENT, ENROLLMENT, null,
                        "activity-enrolled:" + ENROLLMENT, AT));
    }

    @Test
    void venueBookingsAndReschedulesOpenTheBooking() {
        VenueBookingNotifier.Booking booking = new VenueBookingNotifier.Booking(BUYER, BOOKING, "东营低空与机器人体验馆",
                "机器人避障闯关", STARTS_AT, 2, AT);

        assertThat(BuyerNotificationsConfiguration.venueBookedNotice(booking)).isEqualTo(
                new NewNotification(BUYER, NotificationCategory.ACTIVITY_REMINDER, "VENUE_BOOKED", "到馆预约成功",
                        "东营低空与机器人体验馆 机器人避障闯关 2 人，11/15 08:30 开始，到馆出示核销码",
                        NotificationTargetType.VENUE_BOOKING, BOOKING, null, "venue-booked:" + BOOKING, AT));
        assertThat(BuyerNotificationsConfiguration.venueRescheduledNotice(booking, 3)).isEqualTo(
                new NewNotification(BUYER, NotificationCategory.ACTIVITY_REMINDER, "VENUE_RESCHEDULED", "到馆已改期",
                        "东营低空与机器人体验馆 机器人避障闯关改到 11/15 08:30，请用新的核销码",
                        NotificationTargetType.VENUE_BOOKING, BOOKING, null, "venue-rescheduled:" + BOOKING + ":3", AT));
    }

    // ---- 接线和提交之后再写 ----

    @Test
    void theBeansHandTheNoticesToTheAfterCommitWriter() {
        NotificationService notifications = mock(NotificationService.class);
        AfterCommitNotifications writer = new AfterCommitNotifications(notifications,
                TransactionOperations.withoutTransaction());
        BuyerNotificationsConfiguration configuration = new BuyerNotificationsConfiguration();
        TradeBuyerNotifier trade = configuration.tradeBuyerNotifier(writer);

        trade.shipped(mall("示例无人机", 1), AT);
        trade.refunded(new OrderFacts(ORDER, BUYER, "RENTAL", UUID.randomUUID(), "商家", "租赁", 1), REFUND, 100, AT);
        configuration.activityEnrollmentNotifier(writer).enrolled(new ActivityEnrollmentNotifier.Enrolled(
                BUYER, ENROLLMENT, "编程挑战赛", STARTS_AT, 1, false, AT));
        configuration.venueBookingNotifier(writer).booked(new VenueBookingNotifier.Booking(BUYER, BOOKING, "体验馆",
                "项目", STARTS_AT, 1, AT));

        ArgumentCaptor<NewNotification> recorded = ArgumentCaptor.forClass(NewNotification.class);
        verify(notifications, org.mockito.Mockito.times(3)).record(recorded.capture());
        assertThat(recorded.getAllValues()).extracting(NewNotification::eventCode)
                .containsExactly("TRADE_ORDER_SHIPPED", "ACTIVITY_ENROLLED", "VENUE_BOOKED");
    }

    @Test
    void insideATransactionTheNoticeWaitsForTheCommitAndAFailureStaysThere() {
        NotificationService notifications = mock(NotificationService.class);
        AfterCommitNotifications writer = new AfterCommitNotifications(notifications,
                TransactionOperations.withoutTransaction());
        NewNotification notice = BuyerNotificationsConfiguration.shippedNotice(mall("示例无人机", 1), AT).orElseThrow();

        TransactionSynchronizationManager.initSynchronization();
        try {
            writer.record(notice);
            verify(notifications, never()).record(any());
            List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
            assertThat(registered).hasSize(1);

            doThrow(new IllegalStateException("notification store is down")).when(notifications).record(any());
            assertThatCode(() -> registered.getFirst().afterCommit()).doesNotThrowAnyException();
            verify(notifications).record(notice);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(Optional.ofNullable(notice.targetSubId())).isEmpty();
    }
}
