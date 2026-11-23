package com.lowaltitude.inspection.notification.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentNotifier;
import com.lowaltitude.inspection.experience.application.VenueBookingNotifier;
import com.lowaltitude.inspection.trade.application.TradeBuyerNotifier;
import com.lowaltitude.inspection.trade.application.TradeBuyerNotifier.OrderFacts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 交易物流和活动提醒（R6-5，docs/redesign/06-原型对齐修正计划.md）：交易、活动、体验三个模块的通知端口接到站内信。
 * 都在业务事务提交之后写（{@link AfterCommitNotifications}），同一事件只记一条（sourceKey）。
 * 收件人只取订单、报名单、预约单的买家账号；文字照 Messages 画板，只写商品、商家、活动、场馆、项目的名字、
 * 人数、场次时间和退款金额，不写地址、电话、取货码、核销码和参与人姓名。
 *
 * <ul>
 *   <li>交易物流：商城订单已发货、自提订单付款后可以自提、商城订单退款到账（询价下的单也是商城订单；租赁订单不发）；</li>
 *   <li>活动提醒：报名成功（免费的提交即成功、收费的付款到账）、收费报名的退款到账、到馆预约成功、到馆改期；
 *   S5-3 加了候补转正、报名改期、结果已发布。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class BuyerNotificationsConfiguration {

    static final String SHIPPED = "TRADE_ORDER_SHIPPED";
    static final String READY_FOR_PICKUP = "TRADE_ORDER_READY_FOR_PICKUP";
    static final String REFUND_SUCCEEDED = "TRADE_REFUND_SUCCEEDED";
    static final String ENROLLED = "ACTIVITY_ENROLLED";
    static final String ACTIVITY_REFUNDED = "ACTIVITY_REFUNDED";
    static final String VENUE_BOOKED = "VENUE_BOOKED";
    static final String VENUE_RESCHEDULED = "VENUE_RESCHEDULED";
    static final String WAITLIST_OFFERED = "ACTIVITY_WAITLIST_OFFERED";
    static final String ACTIVITY_RESCHEDULED = "ACTIVITY_RESCHEDULED";
    static final String RESULT_RECORDED = "ACTIVITY_RESULT_RECORDED";

    private static final String MALL = "MALL";
    private static final String ACTIVITY = "ACTIVITY";
    private static final int MAX_NAME = 40;
    private static final DateTimeFormatter SESSION_TIME =
            DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    @Bean
    AfterCommitNotifications afterCommitNotifications(
            NotificationService notifications,
            PlatformTransactionManager transactionManager
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new AfterCommitNotifications(notifications, template);
    }

    @Bean
    TradeBuyerNotifier tradeBuyerNotifier(AfterCommitNotifications notifications) {
        return new TradeBuyerNotifier() {
            @Override
            public void shipped(OrderFacts order, Instant at) {
                shippedNotice(order, at).ifPresent(notifications::record);
            }

            @Override
            public void readyForPickup(OrderFacts order, Instant at) {
                readyForPickupNotice(order, at).ifPresent(notifications::record);
            }

            @Override
            public void refunded(OrderFacts order, UUID refundId, long amountMinor, Instant at) {
                refundedNotice(order, refundId, amountMinor, at).ifPresent(notifications::record);
            }
        };
    }

    @Bean
    ActivityEnrollmentNotifier activityEnrollmentNotifier(AfterCommitNotifications notifications) {
        return new ActivityEnrollmentNotifier() {
            @Override
            public void enrolled(Enrolled event) {
                notifications.record(enrolledNotice(event));
            }

            @Override
            public void waitlistOffered(WaitlistOffered event) {
                notifications.record(waitlistOfferedNotice(event));
            }

            @Override
            public void rescheduled(Rescheduled event) {
                notifications.record(activityRescheduledNotice(event));
            }

            @Override
            public void resultRecorded(ResultRecorded event) {
                notifications.record(resultRecordedNotice(event));
            }
        };
    }

    @Bean
    VenueBookingNotifier venueBookingNotifier(AfterCommitNotifications notifications) {
        return new VenueBookingNotifier() {
            @Override
            public void booked(Booking booking) {
                notifications.record(venueBookedNotice(booking));
            }

            @Override
            public void rescheduled(Booking booking, int fromVersion) {
                notifications.record(venueRescheduledNotice(booking, fromVersion));
            }
        };
    }

    // ---- 交易物流 ----

    /** “订单已发货”：编程机器人探索套装，示例机器人商家已发出。只有商城订单发货。 */
    static Optional<NewNotification> shippedNotice(OrderFacts order, Instant at) {
        if (!MALL.equals(order.source())) {
            return Optional.empty();
        }
        return Optional.of(tradeNotice(order, SHIPPED, "订单已发货",
                goods(order) + "，" + merchant(order) + "已发出", "trade-shipped:" + order.orderId(), at));
    }

    /** “可以自提了”：自提的商城订单付款到账、有了取货码。取货码和自提点地址不写进通知，订单页里看。 */
    static Optional<NewNotification> readyForPickupNotice(OrderFacts order, Instant at) {
        if (!MALL.equals(order.source())) {
            return Optional.empty();
        }
        return Optional.of(tradeNotice(order, READY_FOR_PICKUP, "可以自提了",
                goods(order) + "已付款，凭订单里的取货码到" + merchant(order) + "自提点提货",
                "trade-pickup:" + order.orderId(), at));
    }

    /**
     * 退款到账：商城订单是交易物流“退款已原路退回”，收费报名是活动提醒“报名已退款”（去报名订单）。
     * 租赁、到馆预约的退款不发。同一笔退款只记一条。
     */
    static Optional<NewNotification> refundedNotice(OrderFacts order, UUID refundId, long amountMinor, Instant at) {
        String key = "trade-refund:" + refundId;
        if (MALL.equals(order.source())) {
            return Optional.of(tradeNotice(order, REFUND_SUCCEEDED, "退款已原路退回",
                    goods(order) + "的退款 " + yuan(amountMinor) + " 已原路退回", key, at));
        }
        if (ACTIVITY.equals(order.source()) && order.sourceRef() != null) {
            return Optional.of(new NewNotification(
                    order.buyerId(),
                    NotificationCategory.ACTIVITY_REMINDER,
                    ACTIVITY_REFUNDED,
                    "报名已退款",
                    name(order.productName(), "活动") + "的报名费 " + yuan(amountMinor) + " 已原路退回",
                    NotificationTargetType.ACTIVITY_ORDER,
                    order.sourceRef(),
                    null,
                    key,
                    at));
        }
        return Optional.empty();
    }

    private static NewNotification tradeNotice(OrderFacts order, String eventCode, String title, String body,
                                               String sourceKey, Instant at) {
        return new NewNotification(order.buyerId(), NotificationCategory.TRADE_LOGISTICS, eventCode, title, body,
                NotificationTargetType.TRADE_ORDER, order.orderId(), null, sourceKey, at);
    }

    /** 第一件商品的名字，多件时加“等 N 件”。 */
    private static String goods(OrderFacts order) {
        String first = name(order.productName(), "你的订单");
        return order.pieces() > 1 ? first + " 等 " + order.pieces() + " 件" : first;
    }

    private static String merchant(OrderFacts order) {
        return name(order.merchantName(), "商家");
    }

    // ---- 活动提醒 ----

    /** “报名成功”：青少年无人机编程挑战赛 2 人，11/15 08:30 开始，凭证已生成。去报名凭证。 */
    static NewNotification enrolledNotice(ActivityEnrollmentNotifier.Enrolled event) {
        return new NewNotification(
                event.accountId(),
                NotificationCategory.ACTIVITY_REMINDER,
                ENROLLED,
                "报名成功",
                name(event.activityTitle(), "活动") + " " + event.people() + " 人，"
                        + time(event.startsAt()) + " 开始，凭证已生成",
                NotificationTargetType.ACTIVITY_ENROLLMENT,
                event.enrollmentId(),
                null,
                "activity-enrolled:" + event.enrollmentId(),
                event.at());
    }

    /**
     * “候补转正”（S5-3）：青少年无人机编程挑战赛 11/15 14:00 有名额释放，名额保留到 10:35，请尽快付款。
     * 去候补详情。同一条候补只转正一次。
     */
    static NewNotification waitlistOfferedNotice(ActivityEnrollmentNotifier.WaitlistOffered event) {
        return new NewNotification(
                event.accountId(),
                NotificationCategory.ACTIVITY_REMINDER,
                WAITLIST_OFFERED,
                "候补转正",
                name(event.activityTitle(), "活动") + " " + time(event.startsAt()) + " 有名额释放，名额保留到 "
                        + CLOCK.format(event.offerExpiresAt()) + "，请尽快" + (event.paid() ? "付款" : "确认报名"),
                NotificationTargetType.ACTIVITY_WAITLIST,
                event.waitlistId(),
                null,
                "activity-waitlist-offered:" + event.waitlistId(),
                event.at());
    }

    /** “报名已改期”（S5-3）：青少年无人机编程挑战赛 2 人改到 11/22 09:00，请用新的凭证。每改一次记一条。 */
    static NewNotification activityRescheduledNotice(ActivityEnrollmentNotifier.Rescheduled event) {
        return new NewNotification(
                event.accountId(),
                NotificationCategory.ACTIVITY_REMINDER,
                ACTIVITY_RESCHEDULED,
                "报名已改期",
                name(event.activityTitle(), "活动") + " " + event.people() + " 人改到 " + time(event.newStartsAt())
                        + "，请用新的凭证",
                NotificationTargetType.ACTIVITY_ENROLLMENT,
                event.enrollmentId(),
                null,
                "activity-rescheduled:" + event.changeId(),
                event.at());
    }

    /** “结果已发布”（S5-3）：主办方发布了青少年无人机编程挑战赛的结果记录。同一张报名单只记一条。 */
    static NewNotification resultRecordedNotice(ActivityEnrollmentNotifier.ResultRecorded event) {
        return new NewNotification(
                event.accountId(),
                NotificationCategory.ACTIVITY_REMINDER,
                RESULT_RECORDED,
                "结果已发布",
                "主办方发布了" + name(event.activityTitle(), "活动") + "的结果记录",
                NotificationTargetType.ACTIVITY_ENROLLMENT,
                event.enrollmentId(),
                null,
                "activity-result:" + event.enrollmentId(),
                event.at());
    }

    /** “到馆预约成功”：示例体验馆 机器人避障闯关 2 人，11/3 14:00 开始，到馆出示核销码。 */
    static NewNotification venueBookedNotice(VenueBookingNotifier.Booking booking) {
        return venueNotice(booking, VENUE_BOOKED, "到馆预约成功",
                place(booking) + " " + booking.people() + " 人，" + time(booking.startsAt()) + " 开始，到馆出示核销码",
                "venue-booked:" + booking.bookingId());
    }

    /** “到馆已改期”：示例体验馆 机器人避障闯关改到 11/4 10:00，请用新的核销码。每改一次记一条。 */
    static NewNotification venueRescheduledNotice(VenueBookingNotifier.Booking booking, int fromVersion) {
        return venueNotice(booking, VENUE_RESCHEDULED, "到馆已改期",
                place(booking) + "改到 " + time(booking.startsAt()) + "，请用新的核销码",
                "venue-rescheduled:" + booking.bookingId() + ":" + fromVersion);
    }

    private static NewNotification venueNotice(VenueBookingNotifier.Booking booking, String eventCode, String title,
                                               String body, String sourceKey) {
        return new NewNotification(booking.accountId(), NotificationCategory.ACTIVITY_REMINDER, eventCode, title, body,
                NotificationTargetType.VENUE_BOOKING, booking.bookingId(), null, sourceKey, booking.at());
    }

    private static String place(VenueBookingNotifier.Booking booking) {
        return name(booking.venueName(), "体验馆") + " " + name(booking.projectName(), "体验项目");
    }

    // ---- 小工具 ----

    private static String time(OffsetDateTime at) {
        return SESSION_TIME.format(at);
    }

    /** ¥128.00 */
    static String yuan(long amountMinor) {
        return "¥" + BigDecimal.valueOf(amountMinor, 2).toPlainString();
    }

    /** 名字去掉首尾空白，太长截到 40 个字加省略号；没有时用替代词。 */
    private static String name(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return fallback;
        }
        return text.codePointCount(0, text.length()) <= MAX_NAME
                ? text
                : text.substring(0, text.offsetByCodePoints(0, MAX_NAME)) + "…";
    }
}
