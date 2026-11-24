package com.lowaltitude.inspection.experience.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.VenueBookingException.Error;
import com.lowaltitude.inspection.experience.application.VenueParticipantPort.VenueParticipant;
import com.lowaltitude.inspection.experience.application.VenuePaymentPort.OpenPayment;
import com.lowaltitude.inspection.experience.application.VenuePaymentPort.OpenedPayment;
import com.lowaltitude.inspection.experience.application.VenuePaymentPort.RefundProgress;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 到馆预约的规则测试（E10，§21.1）。mapper 换成内存实现（按 SQL 的条件和 V002 的约束写，违反约束时抛异常），
 * 交易模块和参与人名单换成替身：取消未付款订单时替身像交易模块一样回调付款结果。时钟固定；换时间时用同一份数据另起一个服务。
 * SQL 文本由 VenueBookingMapperContractTest 检查，交易一侧的规则见 TradeSourceOrdersTest。
 */
class VenueBookingServiceTest {

    /** 北京时间 2026-11-19 10:00。 */
    private static final Instant NOW = Instant.parse("2026-11-19T02:00:00Z");
    /** 北京时间 11 月 21 日 10:00 开始、10:30 结束。 */
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2026-11-21T02:00:00Z");
    private static final OffsetDateTime ENDS_AT = STARTS_AT.plusMinutes(30);

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID OPERATOR_ORG = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_ORG = UUID.fromString("a1000000-0000-4000-8000-000000000009");
    private static final UUID OPERATOR = UUID.fromString("a1000000-0000-4000-8000-0000000000a4");
    private static final UUID VENUE = UUID.fromString("b2000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("b4000000-0000-4000-8000-000000000101");
    private static final UUID OTHER_PROJECT = UUID.fromString("b4000000-0000-4000-8000-000000000102");
    private static final UUID FREE_PROJECT = UUID.fromString("b4000000-0000-4000-8000-000000000109");
    /** 同一项目的两场：改期的目标那一场的 ID 更小，用来确认两个场次按 ID 从小到大锁。 */
    private static final UUID SESSION = UUID.fromString("b5000000-0000-4000-8000-000000000002");
    private static final UUID LATER_SESSION = UUID.fromString("b5000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_PROJECT_SESSION = UUID.fromString("b5000000-0000-4000-8000-000000000003");
    private static final UUID FREE_SESSION = UUID.fromString("b5000000-0000-4000-8000-000000000004");
    private static final UUID SELF = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final UUID MING = UUID.fromString("c0000000-0000-4000-8000-000000000002");
    private static final UUID BAO = UUID.fromString("c0000000-0000-4000-8000-000000000003");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_KEY = UUID.fromString("e0000000-0000-4000-8000-000000000002");
    private static final long PRICE = 3900;

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final FakePayments payments = new FakePayments();
    private final FakeParticipants people = new FakeParticipants();
    private int issuedCodes;
    private final VenueBookingService service = at(NOW);

    VenueBookingServiceTest() {
        payments.callback = service;
    }

    // ---- 参与人 ----

    @Test
    void participantsComeWithTheirAgeOnTheSessionDayAndWhetherTheyCanBook() {
        VenueParticipantListView list = service.listParticipants(ACCOUNT, "王女士", SESSION);

        assertThat(people.accountNames).containsExactly("王女士");
        assertThat(list.sessionId()).isEqualTo(SESSION);
        assertThat(list.seatsLeft()).isEqualTo(6);
        assertThat(list.maxPeople()).isEqualTo(10);
        // 项目最低 8 岁：小明 2016-05 生，到 2026-11 满 10 岁；小宝 2021-03 生，5 岁；本人按成年人。
        assertThat(list.items())
                .extracting(VenueParticipantView::participantId, VenueParticipantView::relation,
                        VenueParticipantView::ageAtSession, VenueParticipantView::eligible,
                        VenueParticipantView::reason)
                .containsExactly(
                        tuple(SELF, VenueParticipantRelation.SELF, null, true, VenueEligibilityReason.ELIGIBLE),
                        tuple(MING, VenueParticipantRelation.CHILD, 10, true, VenueEligibilityReason.ELIGIBLE),
                        tuple(BAO, VenueParticipantRelation.CHILD, 5, false, VenueEligibilityReason.UNDER_MIN_AGE));

        book(KEY, SELF);

        assertThat(service.listParticipants(ACCOUNT, "王女士", SESSION).items().get(0).reason())
                .isEqualTo(VenueEligibilityReason.ALREADY_BOOKED);
        // 下线的场次当作不存在。
        mapper.session(SESSION).status = "CANCELLED";
        assertRejected(Error.NOT_FOUND, () -> service.listParticipants(ACCOUNT, "王女士", SESSION));
        assertRejected(Error.NOT_FOUND, () -> service.listParticipants(ACCOUNT, "王女士", UUID.randomUUID()));
    }

    // 本人按成年人：只在项目最低年龄高于 18 岁时不能约。
    @Test
    void theAccountHolderCountsAsAnAdult() {
        mapper.session(SESSION).minAge = 18;
        assertThat(service.listParticipants(ACCOUNT, "王女士", SESSION).items().get(0).eligible()).isTrue();
        mapper.session(SESSION).minAge = 19;
        assertThat(service.listParticipants(ACCOUNT, "王女士", SESSION).items().get(0).reason())
                .isEqualTo(VenueEligibilityReason.UNDER_MIN_AGE);
    }

    // ---- 预约 ----

    @Test
    void aPaidBookingTakesTheSeatsAndOpensATradeOrderButHasNoCodeYet() {
        BookingResult result = service.book(ACCOUNT, "王女士", KEY, command(2 * PRICE, MING, SELF));

        VenueBookingView view = result.booking();
        assertThat(result.created()).isTrue();
        assertThat(view.status()).isEqualTo(VenueBookingStatus.PENDING_PAYMENT);
        assertThat(view.code()).isNull();
        assertThat(view.people()).isEqualTo(2);
        assertThat(view.cancellable()).isTrue();
        assertThat(view.reschedulable()).isFalse();
        UUID order = payments.orderOf(view.bookingId());
        OffsetDateTime payBefore = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(15);
        assertThat(view.payment()).isEqualTo(new VenueBookingPaymentView(
                VenuePaymentStatus.PENDING_PAYMENT, order, 2 * PRICE, PRICE, payBefore));
        assertThat(view.refund()).isNull();
        assertThat(view.venue()).isEqualTo(new BookingVenueView(VENUE, "东营低空与机器人体验馆", "370500", "东营区示例路 66 号", null));
        assertThat(view.project()).isEqualTo(new BookingProjectView(PROJECT, "机器人避障闯关", 30));
        assertThat(view.session()).isEqualTo(new BookingSessionView(SESSION, STARTS_AT, ENDS_AT));
        // 人按“本人在前、其余按姓名”排，年龄是场次当天的。
        assertThat(view.participants()).containsExactly(
                new BookedParticipantView(SELF, "王女士", VenueParticipantRelation.SELF, null),
                new BookedParticipantView(MING, "小明", VenueParticipantRelation.CHILD, 10));
        assertThat(mapper.session(SESSION).booked).isEqualTo(4);
        // 交易订单：收款方是场馆运营机构，订单上是“场馆 项目”和北京时间的场次。
        assertThat(payments.opened).containsExactly(new OpenPayment(view.bookingId(), ACCOUNT, OPERATOR_ORG, "370500",
                PROJECT, SESSION, "东营低空与机器人体验馆 机器人避障闯关", "11月21日 10:00", null, PRICE, 2,
                payBefore.toInstant()));
        assertThat(mapper.events).containsExactly(
                new InMemoryMapper.Event(view.bookingId(), "BOOKED", ACCOUNT, null, null));
    }

    @Test
    void thePaymentDeadlineNeverPassesTheSessionStart() {
        VenueBookingService late = at(STARTS_AT.minusMinutes(5).toInstant());

        VenueBookingView view = late.book(ACCOUNT, "王女士", KEY, command(PRICE, SELF)).booking();

        assertThat(view.payment().payBefore()).isEqualTo(STARTS_AT);
    }

    @Test
    void aFreeProjectIsBookedAtOnceWithACodeAndNoTradeOrder() {
        VenueBookingView view = service.book(ACCOUNT, "王女士", KEY, freeCommand(0, SELF, MING)).booking();

        assertThat(view.status()).isEqualTo(VenueBookingStatus.CONFIRMED);
        assertThat(view.code()).isEqualTo("VEN-TEST-0000-0001");
        assertThat(view.payment()).isEqualTo(new VenueBookingPaymentView(VenuePaymentStatus.FREE, null, 0, 0, null));
        assertThat(view.reschedulable()).isTrue();
        assertThat(payments.opened).isEmpty();
        assertThat(payments.merchantQueries).isEmpty();
        assertThat(mapper.session(FREE_SESSION).booked).isEqualTo(2);
        // 免费的取消：直接取消、放回名额，不退款。
        VenueBookingView cancelled = service.cancel(ACCOUNT, view.bookingId());
        assertThat(cancelled.status()).isEqualTo(VenueBookingStatus.CANCELLED);
        assertThat(cancelled.code()).isNull();
        assertThat(cancelled.refund()).isNull();
        assertThat(payments.refunds).isEmpty();
        assertThat(payments.orderLocks).isEmpty();
        assertThat(mapper.session(FREE_SESSION).booked).isZero();
    }

    // 没接交易模块：免费项目照常可约，要付款的一律“暂未开通在线支付”。
    @Test
    void withoutTheTradeModuleOnlyFreeProjectsCanBeBooked() {
        VenueBookingService unwired = new VenueBookingService(mapper, people, VenuePaymentPort.unavailable(),
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC), this::nextCode);

        assertRejected(Error.PAYMENT_UNAVAILABLE, () -> unwired.book(ACCOUNT, "王女士", KEY, command(PRICE, SELF)));
        assertThat(unwired.book(ACCOUNT, "王女士", KEY, freeCommand(0, SELF)).booking().status())
                .isEqualTo(VenueBookingStatus.CONFIRMED);
    }

    @Test
    void everyRefusalLeavesNothingBehind() {
        payments.collecting.clear();
        assertRejected(Error.PAYMENT_UNAVAILABLE, () -> book(KEY, SELF));
        payments.collecting.add(OPERATOR_ORG);

        assertRejected(Error.AMOUNT_CHANGED, () -> service.book(ACCOUNT, "王女士", KEY, command(PRICE, SELF, MING)));
        assertRejected(Error.PARTICIPANT_NOT_ELIGIBLE, () -> book(KEY, SELF, BAO));
        assertRejected(Error.NOT_FOUND, () -> book(KEY, SELF, UUID.randomUUID()));
        mapper.session(SESSION).booked = 7;
        assertRejected(Error.SEATS_NOT_ENOUGH, () -> book(KEY, SELF, MING));
        mapper.session(SESSION).booked = 2;
        assertRejected(Error.SESSION_NOT_OPEN, () -> at(STARTS_AT.toInstant()).book(ACCOUNT, "王女士", KEY,
                command(PRICE, SELF)));
        mapper.session(SESSION).projectStatus = "DISABLED";
        assertRejected(Error.NOT_FOUND, () -> book(KEY, SELF));
        mapper.session(SESSION).projectStatus = "ACTIVE";
        assertRejected(Error.NOT_FOUND, () -> service.book(ACCOUNT, "王女士", KEY,
                new BookCommand(UUID.randomUUID(), ids(SELF), PRICE, true)));

        assertNothingBooked();
    }

    @Test
    void malformedBookingsAreInvalid() {
        List<UUID> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(UUID.randomUUID());
        }
        assertInvalid(() -> service.book(ACCOUNT, "王女士", null, command(PRICE, SELF)));
        assertInvalid(() -> service.book(ACCOUNT, "王女士", KEY, new BookCommand(SESSION, ids(), 0L, true)));
        assertInvalid(() -> service.book(ACCOUNT, "王女士", KEY, new BookCommand(SESSION, eleven, 11 * PRICE, true)));
        assertInvalid(() -> service.book(ACCOUNT, "王女士", KEY, new BookCommand(SESSION, ids(SELF, SELF), PRICE, true)));
        assertInvalid(() -> service.book(ACCOUNT, "王女士", KEY, new BookCommand(SESSION, ids(SELF), null, true)));
        assertInvalid(() -> service.book(ACCOUNT, "王女士", KEY, new BookCommand(SESSION, ids(SELF), PRICE, false)));
        assertInvalid(() -> service.book(ACCOUNT, "王女士", KEY,
                new BookCommand(SESSION, Arrays.asList(SELF, null), PRICE, true)));
        assertInvalid(() -> service.book(null, "王女士", KEY, command(PRICE, SELF)));
        assertNothingBooked();
    }

    @Test
    void aRepeatedSubmissionReturnsTheSameBookingWithoutASecondOrder() {
        VenueBookingView first = book(KEY, SELF, MING);

        BookingResult again = service.book(ACCOUNT, "王女士", KEY, command(2 * PRICE, MING, SELF));

        assertThat(again.created()).isFalse();
        assertThat(again.booking()).isEqualTo(first);
        assertThat(payments.opened).hasSize(1);
        assertThat(mapper.session(SESSION).booked).isEqualTo(4);
        assertRejected(Error.IDEMPOTENCY_CONFLICT, () -> book(KEY, SELF));
    }

    // 同一个人同一场只能有一张有效预约，待付款的也算。
    @Test
    void aPersonCannotHoldTwoBookingsForOneSession() {
        book(KEY, SELF);

        assertRejected(Error.PARTICIPANT_ALREADY_BOOKED, () -> book(SECOND_KEY, MING, SELF));
        assertThat(mapper.bookings).hasSize(1);
    }

    // ---- 付款结果 ----

    @Test
    void aConfirmedPaymentIssuesTheCodeOnce() {
        VenueBookingView pending = book(KEY, SELF);
        UUID order = payments.orderOf(pending.bookingId());

        assertThat(service.onPaymentConfirmed(order, pending.bookingId())).isTrue();
        assertThat(service.onPaymentConfirmed(order, pending.bookingId())).isTrue();

        VenueBookingView view = service.readBooking(ACCOUNT, pending.bookingId());
        assertThat(view.status()).isEqualTo(VenueBookingStatus.CONFIRMED);
        assertThat(view.code()).isEqualTo("VEN-TEST-0000-0001");
        assertThat(view.payment().status()).isEqualTo(VenuePaymentStatus.PAID);
        assertThat(view.reschedulable()).isTrue();
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type, InMemoryMapper.Event::actor)
                .containsExactly(tuple("BOOKED", ACCOUNT), tuple("PAYMENT_CONFIRMED", null));
        // 先锁场次、后锁预约单。
        assertThat(mapper.locks).containsSubsequence("session:" + SESSION, "booking:" + pending.bookingId());
        // 别的交易订单报来的付款对不上这张预约：收不下。
        assertThat(service.onPaymentConfirmed(UUID.randomUUID(), pending.bookingId())).isFalse();
    }

    @Test
    void aClosedOrderReleasesTheSeatsAndThePeople() {
        VenueBookingView pending = book(KEY, SELF, MING);
        UUID order = payments.orderOf(pending.bookingId());

        service.onPaymentClosed(order, pending.bookingId());
        service.onPaymentClosed(order, pending.bookingId());

        VenueBookingView view = service.readBooking(ACCOUNT, pending.bookingId());
        assertThat(view.status()).isEqualTo(VenueBookingStatus.CLOSED);
        assertThat(view.payment().status()).isEqualTo(VenuePaymentStatus.CLOSED);
        assertThat(view.cancellable()).isFalse();
        assertThat(mapper.session(SESSION).booked).isEqualTo(2);
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).containsExactly("BOOKED", "PAYMENT_CLOSED");
        assertThat(service.listBookings(ACCOUNT, "CANCELLED", 1, 20).items())
                .extracting(VenueBookingView::bookingId).containsExactly(pending.bookingId());
        assertThat(service.listBookings(ACCOUNT, null, 1, 20).items()).isEmpty();
        // 关闭之后这些人可以重新约这一场。
        assertThat(book(SECOND_KEY, SELF, MING).status()).isEqualTo(VenueBookingStatus.PENDING_PAYMENT);
    }

    @Test
    void aPaymentAfterTheCloseRestoresTheBookingWhenNothingStandsInTheWay() {
        VenueBookingView pending = book(KEY, SELF);
        UUID order = payments.orderOf(pending.bookingId());
        service.onPaymentClosed(order, pending.bookingId());

        assertThat(service.onPaymentConfirmed(order, pending.bookingId())).isTrue();

        VenueBookingView view = service.readBooking(ACCOUNT, pending.bookingId());
        assertThat(view.status()).isEqualTo(VenueBookingStatus.CONFIRMED);
        assertThat(view.code()).isNotNull();
        assertThat(mapper.session(SESSION).booked).isEqualTo(3);
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type)
                .containsExactly("BOOKED", "PAYMENT_CLOSED", "PAYMENT_RESTORED");
    }

    @Test
    void aPaymentAfterTheCloseIsTurnedAwayWhenTheSessionStartedTheSeatsAreGoneOrThePersonBookedAgain() {
        VenueBookingView pending = book(KEY, SELF);
        UUID order = payments.orderOf(pending.bookingId());
        service.onPaymentClosed(order, pending.bookingId());

        assertThat(at(STARTS_AT.toInstant()).onPaymentConfirmed(order, pending.bookingId())).isFalse();
        mapper.session(SESSION).booked = 8;
        assertThat(service.onPaymentConfirmed(order, pending.bookingId())).isFalse();
        mapper.session(SESSION).booked = 2;
        book(SECOND_KEY, SELF);
        assertThat(service.onPaymentConfirmed(order, pending.bookingId())).isFalse();

        assertThat(mapper.booking(pending.bookingId()).status).isEqualTo("CLOSED");
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).doesNotContain("PAYMENT_RESTORED");
    }

    // ---- 取消 ----

    @Test
    void cancellingAnUnpaidBookingClosesItsTradeOrder() {
        VenueBookingView pending = book(KEY, SELF, MING);

        VenueBookingView view = service.cancel(ACCOUNT, pending.bookingId());

        assertThat(payments.cancelled).containsExactly(payments.orderOf(pending.bookingId()));
        assertThat(view.status()).isEqualTo(VenueBookingStatus.CLOSED);
        assertThat(mapper.session(SESSION).booked).isEqualTo(2);
    }

    // 取消时向渠道查单发现已经付了：按付款处理，什么都不取消。
    @Test
    void cancellingABookingThatWasJustPaidKeepsIt() {
        VenueBookingView pending = book(KEY, SELF);
        payments.paidBeforeCancel = true;

        VenueBookingView view = service.cancel(ACCOUNT, pending.bookingId());

        assertThat(view.status()).isEqualTo(VenueBookingStatus.CONFIRMED);
        assertThat(mapper.session(SESSION).booked).isEqualTo(3);
    }

    @Test
    void cancellingAPaidBookingRefundsTheWholeAmountOnce() {
        VenueBookingView paid = paidBooking(KEY, SELF, MING);
        UUID order = payments.orderOf(paid.bookingId());
        mapper.locks.clear();

        VenueBookingView view = service.cancel(ACCOUNT, paid.bookingId());

        assertThat(view.status()).isEqualTo(VenueBookingStatus.CANCELLED);
        assertThat(view.code()).isNull();
        assertThat(view.cancelledAt()).isNotNull();
        assertThat(view.cancellable()).isFalse();
        assertThat(view.payment().status()).isEqualTo(VenuePaymentStatus.PAID);
        assertThat(view.refund()).isEqualTo(new VenueBookingRefundView(VenueRefundStatus.REQUESTED, 2 * PRICE));
        assertThat(payments.refunds).containsExactly(new FakePayments.Refund(new UUID(0x5000L, 1), order, 2 * PRICE,
                "取消到馆预约",
                UUID.nameUUIDFromBytes(("venue-refund:" + paid.bookingId()).getBytes(StandardCharsets.UTF_8)),
                ACCOUNT));
        assertThat(mapper.session(SESSION).booked).isEqualTo(2);
        // 先锁交易订单，再锁场次，最后锁预约单。
        assertThat(mapper.locks).containsExactly(
                "order:" + order, "session:" + SESSION, "booking:" + paid.bookingId());
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type)
                .endsWith("CANCELLED", "REFUND_REQUESTED");
        // 退款到账后看到“已退款”；再取消一次没有变化，也不会再退。
        payments.progress.put(payments.refunds.get(0).refundId(), new RefundProgress("SUCCEEDED", 2 * PRICE));
        assertThat(service.cancel(ACCOUNT, paid.bookingId()).refund().status()).isEqualTo(VenueRefundStatus.SUCCEEDED);
        assertThat(payments.refunds).hasSize(1);
        assertThat(service.listBookings(ACCOUNT, "CANCELLED", 1, 20).total()).isEqualTo(1);
    }

    @Test
    void aStartedOrCheckedInBookingCannotBeCancelled() {
        VenueBookingView paid = paidBooking(KEY, SELF);
        assertRejected(Error.CANCEL_NOT_ALLOWED, () -> at(STARTS_AT.toInstant()).cancel(ACCOUNT, paid.bookingId()));

        at(STARTS_AT.minusMinutes(10).toInstant()).checkIn(OPERATOR, OPERATOR_ORG, codeOf(paid.bookingId()));
        assertRejected(Error.CANCEL_NOT_ALLOWED, () -> service.cancel(ACCOUNT, paid.bookingId()));

        assertThat(payments.refunds).isEmpty();
        assertRejected(Error.NOT_FOUND, () -> service.cancel(OTHER_ACCOUNT, paid.bookingId()));
    }

    // ---- 改期 ----

    // 身份 V013：运营机构被挂起上架后不能再约，已付款的预约仍能改期。
    @Test
    void aSuspendedOperatorTakesNoNewBookingsButAPaidOneCanStillBeRescheduled() {
        VenueBookingView paid = paidBooking(KEY, SELF);
        mapper.session(SESSION).suspended = true;
        mapper.session(LATER_SESSION).suspended = true;

        assertRejected(Error.NOT_FOUND, () -> book(UUID.randomUUID(), MING));
        assertRejected(Error.NOT_FOUND, () -> service.listParticipants(ACCOUNT, "王女士", SESSION));

        VenueBookingView moved = service.reschedule(ACCOUNT, paid.bookingId(), paid.version(), LATER_SESSION);
        assertThat(moved.session().sessionId()).isEqualTo(LATER_SESSION);
    }

    @Test
    void reschedulingMovesTheSeatsAndTheCodeToAnotherSessionOfTheSameProject() {
        VenueBookingView paid = paidBooking(KEY, SELF, MING);
        String oldCode = paid.code();
        mapper.locks.clear();

        VenueBookingView moved = service.reschedule(ACCOUNT, paid.bookingId(), paid.version(), LATER_SESSION);

        assertThat(moved.session().sessionId()).isEqualTo(LATER_SESSION);
        assertThat(moved.status()).isEqualTo(VenueBookingStatus.CONFIRMED);
        assertThat(moved.code()).isNotNull().isNotEqualTo(oldCode);
        assertThat(moved.version()).isEqualTo(paid.version() + 1);
        assertThat(moved.payment()).isEqualTo(paid.payment());
        assertThat(mapper.session(SESSION).booked).isEqualTo(2);
        assertThat(mapper.session(LATER_SESSION).booked).isEqualTo(5);
        assertThat(mapper.participants).allSatisfy(row -> assertThat(row.sessionId).isEqualTo(LATER_SESSION));
        assertThat(mapper.events).last().isEqualTo(
                new InMemoryMapper.Event(paid.bookingId(), "RESCHEDULED", ACCOUNT, SESSION, LATER_SESSION));
        // 交易订单最先锁；两个场次按 ID 从小到大（目标那一场的 ID 更小），最后锁预约单。
        assertThat(mapper.locks).containsExactly("order:" + payments.orderOf(paid.bookingId()),
                "session:" + LATER_SESSION, "session:" + SESSION, "booking:" + paid.bookingId());
        assertThat(payments.refunds).isEmpty();
        // 旧码作废：按旧码当作不存在；新码能核销。
        VenueBookingService atTheDoor = at(OffsetDateTime.parse("2026-11-21T06:00:00Z").minusMinutes(10).toInstant());
        assertRejected(Error.NOT_FOUND, () -> atTheDoor.checkIn(OPERATOR, OPERATOR_ORG, oldCode));
        assertThat(atTheDoor.checkIn(OPERATOR, OPERATOR_ORG, moved.code()).repeated()).isFalse();
        // 同一个目标再提交一次：没有变化。
        assertThat(service.reschedule(ACCOUNT, paid.bookingId(), 0, LATER_SESSION).session().sessionId())
                .isEqualTo(LATER_SESSION);
    }

    @Test
    void reschedulingChecksTheVersionTheStateAndTheTarget() {
        VenueBookingView paid = paidBooking(KEY, SELF, MING);
        int version = paid.version();

        assertRejected(Error.VERSION_CONFLICT,
                () -> service.reschedule(ACCOUNT, paid.bookingId(), version - 1, LATER_SESSION));
        assertRejected(Error.RESCHEDULE_NOT_ALLOWED,
                () -> service.reschedule(ACCOUNT, paid.bookingId(), version, OTHER_PROJECT_SESSION));
        assertRejected(Error.NOT_FOUND,
                () -> service.reschedule(ACCOUNT, paid.bookingId(), version, UUID.randomUUID()));
        mapper.session(LATER_SESSION).booked = 7;
        assertRejected(Error.SEATS_NOT_ENOUGH,
                () -> service.reschedule(ACCOUNT, paid.bookingId(), version, LATER_SESSION));
        mapper.session(LATER_SESSION).booked = 3;
        mapper.session(LATER_SESSION).status = "CANCELLED";
        assertRejected(Error.SESSION_NOT_OPEN,
                () -> service.reschedule(ACCOUNT, paid.bookingId(), version, LATER_SESSION));
        mapper.session(LATER_SESSION).status = "ACTIVE";
        // 原场次已经开始。
        assertRejected(Error.RESCHEDULE_NOT_ALLOWED, () -> at(STARTS_AT.toInstant())
                .reschedule(ACCOUNT, paid.bookingId(), version, LATER_SESSION));
        // 小明在目标场次另有一张预约。
        payments.collecting.add(OPERATOR_ORG);
        service.book(ACCOUNT, "王女士", SECOND_KEY, new BookCommand(LATER_SESSION, ids(MING), PRICE, true));
        assertRejected(Error.PARTICIPANT_ALREADY_BOOKED,
                () -> service.reschedule(ACCOUNT, paid.bookingId(), version, LATER_SESSION));
        assertRejected(Error.NOT_FOUND,
                () -> service.reschedule(OTHER_ACCOUNT, paid.bookingId(), version, LATER_SESSION));

        assertThat(mapper.booking(paid.bookingId()).sessionId).isEqualTo(SESSION);
        assertThat(mapper.session(SESSION).booked).isEqualTo(4);
    }

    @Test
    void onlyAConfirmedBookingCanBeRescheduled() {
        VenueBookingView pending = book(KEY, SELF);
        assertRejected(Error.RESCHEDULE_NOT_ALLOWED,
                () -> service.reschedule(ACCOUNT, pending.bookingId(), pending.version(), LATER_SESSION));

        VenueBookingView paid = paidBooking(SECOND_KEY, MING);
        at(STARTS_AT.minusMinutes(10).toInstant()).checkIn(OPERATOR, OPERATOR_ORG, codeOf(paid.bookingId()));
        int version = mapper.booking(paid.bookingId()).version;
        assertRejected(Error.RESCHEDULE_NOT_ALLOWED,
                () -> service.reschedule(ACCOUNT, paid.bookingId(), version, LATER_SESSION));
    }

    // ---- 核销 ----

    @Test
    void checkInIsOpenFromHalfAnHourBeforeTheStartUntilTheEndAndHappensOnce() {
        VenueBookingView paid = paidBooking(KEY, SELF, MING);
        String code = codeOf(paid.bookingId()).toLowerCase();

        assertRejected(Error.CHECKIN_NOT_OPEN,
                () -> at(STARTS_AT.minusMinutes(31).toInstant()).checkIn(OPERATOR, OPERATOR_ORG, code));
        assertRejected(Error.CHECKIN_NOT_OPEN,
                () -> at(ENDS_AT.toInstant()).checkIn(OPERATOR, OPERATOR_ORG, code));
        assertRejected(Error.NOT_FOUND,
                () -> at(STARTS_AT.toInstant()).checkIn(OPERATOR, OTHER_ORG, code));

        OffsetDateTime first = STARTS_AT.minusMinutes(30);
        VenueCheckinView checkedIn = at(first.toInstant()).checkIn(OPERATOR, OPERATOR_ORG, " " + code + " ");
        VenueCheckinView again = at(ENDS_AT.plusHours(1).toInstant()).checkIn(UUID.randomUUID(), OPERATOR_ORG, code);

        assertThat(checkedIn).isEqualTo(new VenueCheckinView(code.toUpperCase(), "东营低空与机器人体验馆", "机器人避障闯关",
                STARTS_AT, 2, first, false));
        assertThat(again).isEqualTo(new VenueCheckinView(code.toUpperCase(), "东营低空与机器人体验馆", "机器人避障闯关",
                STARTS_AT, 2, first, true));
        assertThat(mapper.booking(paid.bookingId()).checkedInBy).isEqualTo(OPERATOR);
        VenueBookingView view = service.readBooking(ACCOUNT, paid.bookingId());
        assertThat(view.status()).isEqualTo(VenueBookingStatus.CHECKED_IN);
        assertThat(view.checkedInAt()).isEqualTo(first);
        assertThat(view.code()).isNotNull();
        assertThat(mapper.events).extracting(InMemoryMapper.Event::type).endsWith("CHECKED_IN");
        assertThat(mapper.events).filteredOn(event -> event.type().equals("CHECKED_IN")).hasSize(1);
    }

    // S3-1：检票时间按场馆的规则：开场前 15 分钟开始，开场后 10 分钟停止入场（含第 10 分钟）。
    @Test
    void checkInFollowsTheVenueEntryRule() {
        mapper.checkinOpensMinutes = 15;
        mapper.lateEntryMinutes = 10;
        VenueBookingView paid = paidBooking(KEY, SELF);
        String code = codeOf(paid.bookingId());

        assertRejected(Error.CHECKIN_NOT_OPEN,
                () -> at(STARTS_AT.minusMinutes(16).toInstant()).checkIn(OPERATOR, OPERATOR_ORG, code));
        assertRejected(Error.CHECKIN_NOT_OPEN,
                () -> at(STARTS_AT.plusMinutes(10).plusSeconds(1).toInstant()).checkIn(OPERATOR, OPERATOR_ORG, code));
        assertThat(at(STARTS_AT.plusMinutes(10).toInstant()).checkIn(OPERATOR, OPERATOR_ORG, code).repeated())
                .isFalse();
    }

    @Test
    void entryRuleWindowEdges() {
        OffsetDateTime start = STARTS_AT;
        OffsetDateTime end = ENDS_AT;
        var row = new com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper.CheckinRow(
                UUID.randomUUID(), "VEN-AAAA-BBBB-CCCC", "CONFIRMED", 1, null, "馆", "项目", start, end, 0, null);
        assertThat(VenueBookingService.checkinOpen(row, start.minusSeconds(1))).isFalse();
        assertThat(VenueBookingService.checkinOpen(row, start)).isTrue();
        assertThat(VenueBookingService.checkinOpen(row, end.minusSeconds(1))).isTrue();
        assertThat(VenueBookingService.checkinOpen(row, end)).isFalse();
        // 迟到截止晚于场次结束时，以场次结束为准。
        var late = new com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper.CheckinRow(
                UUID.randomUUID(), "VEN-AAAA-BBBB-CCCC", "CONFIRMED", 1, null, "馆", "项目", start, end, 30, 240);
        assertThat(VenueBookingService.checkinOpen(late, start.minusMinutes(30))).isTrue();
        assertThat(VenueBookingService.checkinOpen(late, end)).isFalse();
    }

    @Test
    void cancelledCodesAndBadInputAreRefused() {
        VenueBookingView paid = paidBooking(KEY, SELF);
        String code = codeOf(paid.bookingId());
        service.cancel(ACCOUNT, paid.bookingId());

        assertRejected(Error.CODE_NOT_VALID,
                () -> at(STARTS_AT.toInstant()).checkIn(OPERATOR, OPERATOR_ORG, code));
        assertInvalid(() -> service.checkIn(OPERATOR, OPERATOR_ORG, "ACT-AAAA-BBBB-CCCC"));
        assertRejected(Error.FORBIDDEN, () -> service.checkIn(OPERATOR, null, code));
    }

    // 场次结束还没核销：读成已过期，进“已完成”，不给码，不能取消，也不退款。
    @Test
    void aBookingNobodyCameForExpiresWithoutARefund() {
        VenueBookingView paid = paidBooking(KEY, SELF);
        VenueBookingService later = at(ENDS_AT.toInstant());

        VenueBookingView view = later.readBooking(ACCOUNT, paid.bookingId());

        assertThat(view.status()).isEqualTo(VenueBookingStatus.EXPIRED);
        assertThat(view.code()).isNull();
        assertThat(view.cancellable()).isFalse();
        assertThat(view.reschedulable()).isFalse();
        assertThat(later.listBookings(ACCOUNT, "FINISHED", 1, 20).items())
                .extracting(VenueBookingView::bookingId).containsExactly(paid.bookingId());
        assertThat(later.listBookings(ACCOUNT, "UPCOMING", 1, 20).items()).isEmpty();
        assertRejected(Error.CANCEL_NOT_ALLOWED, () -> later.cancel(ACCOUNT, paid.bookingId()));
        assertThat(payments.refunds).isEmpty();
    }

    @Test
    void myBookingsAreSplitIntoUpcomingFinishedAndCancelled() {
        VenueBookingView pending = book(KEY, SELF);
        VenueBookingView free = service.book(ACCOUNT, "王女士", SECOND_KEY, freeCommand(0, MING)).booking();

        VenueBookingPageView upcoming = service.listBookings(ACCOUNT, null, 1, 1);

        assertThat(upcoming.total()).isEqualTo(2);
        assertThat(upcoming.hasMore()).isTrue();
        assertThat(service.listBookings(ACCOUNT, "UPCOMING", 1, 20).items())
                .extracting(VenueBookingView::bookingId)
                .containsExactlyInAnyOrder(pending.bookingId(), free.bookingId());
        assertThat(service.listBookings(OTHER_ACCOUNT, "UPCOMING", 1, 20).items()).isEmpty();
        assertInvalid(() -> service.listBookings(ACCOUNT, "LATER", 1, 20));
        assertInvalid(() -> service.listBookings(ACCOUNT, null, 1, 51));
        assertRejected(Error.NOT_FOUND, () -> service.readBooking(OTHER_ACCOUNT, pending.bookingId()));
    }

    // ---- 通知（R6-5） ----

    /** 记下体验模块要告诉预约账号的事：“booked 预约 人数 开始时间” / “rescheduled 预约 改期前版本 开始时间”。 */
    private final List<String> told = new ArrayList<>();
    private final List<VenueBookingNotifier.Booking> toldBookings = new ArrayList<>();

    private VenueBookingService telling() {
        VenueBookingService telling = new VenueBookingService(mapper, people, payments,
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC), this::nextCode,
                new VenueBookingNotifier() {
                    @Override
                    public void booked(Booking booking) {
                        toldBookings.add(booking);
                        told.add("booked " + booking.bookingId() + " " + booking.people() + " " + booking.startsAt());
                    }

                    @Override
                    public void rescheduled(Booking booking, int fromVersion) {
                        toldBookings.add(booking);
                        told.add("rescheduled " + booking.bookingId() + " " + fromVersion + " " + booking.startsAt());
                    }
                });
        payments.callback = telling;
        return telling;
    }

    // 收费项目提交时不说，付款到账说一次（场馆名、项目名、开始时间、人数，不带姓名和核销码）；重复到账不再说。
    @Test
    void aPaidBookingIsReportedOnceWhenThePaymentArrives() {
        VenueBookingService telling = telling();
        VenueBookingView pending = telling.book(ACCOUNT, "王女士", KEY, command(PRICE * 2, SELF, MING)).booking();
        UUID order = payments.orderOf(pending.bookingId());
        assertThat(told).isEmpty();

        telling.onPaymentConfirmed(order, pending.bookingId());
        telling.onPaymentConfirmed(order, pending.bookingId());

        assertThat(told).containsExactly("booked " + pending.bookingId() + " 2 " + STARTS_AT);
        VenueBookingNotifier.Booking booking = toldBookings.getFirst();
        assertThat(booking.accountId()).isEqualTo(ACCOUNT);
        assertThat(booking.venueName()).isEqualTo("东营低空与机器人体验馆");
        assertThat(booking.projectName()).isNotBlank();
        assertThat(booking.at()).isEqualTo(NOW);
    }

    // 免费项目提交即预约成功，马上说；同一个幂等键重放不再说。
    @Test
    void aFreeBookingIsReportedAtOnce() {
        VenueBookingService telling = telling();

        VenueBookingView free = telling.book(ACCOUNT, "王女士", KEY, freeCommand(0, SELF)).booking();
        telling.book(ACCOUNT, "王女士", KEY, freeCommand(0, SELF));

        assertThat(told).containsExactly("booked " + free.bookingId() + " 1 " + mapper.session(FREE_SESSION).startsAt);
    }

    // 改期说一次，带上新场次的时间和改期前的版本（同一张预约每改一次都不同）；同一目标重复提交不再说。
    @Test
    void aRescheduleIsReportedWithTheNewSessionAndThePreviousVersion() {
        VenueBookingService telling = telling();
        VenueBookingView pending = telling.book(ACCOUNT, "王女士", KEY, command(PRICE, SELF)).booking();
        telling.onPaymentConfirmed(payments.orderOf(pending.bookingId()), pending.bookingId());
        VenueBookingView paid = telling.readBooking(ACCOUNT, pending.bookingId());
        told.clear();

        telling.reschedule(ACCOUNT, paid.bookingId(), paid.version(), LATER_SESSION);
        telling.reschedule(ACCOUNT, paid.bookingId(), paid.version(), LATER_SESSION);

        assertThat(told).containsExactly("rescheduled " + paid.bookingId() + " " + paid.version() + " "
                + mapper.session(LATER_SESSION).startsAt);
    }

    // ---- 辅助 ----

    private VenueBookingService at(Instant instant) {
        return new VenueBookingService(mapper, people, payments,
                TransactionOperations.withoutTransaction(), Clock.fixed(instant, ZoneOffset.UTC), this::nextCode);
    }

    private String nextCode() {
        return String.format("VEN-TEST-0000-%04d", ++issuedCodes);
    }

    private static BookCommand command(long expected, UUID... participantIds) {
        return new BookCommand(SESSION, ids(participantIds), expected, true);
    }

    private static BookCommand freeCommand(long expected, UUID... participantIds) {
        return new BookCommand(FREE_SESSION, ids(participantIds), expected, true);
    }

    private static List<UUID> ids(UUID... ids) {
        return new ArrayList<>(Arrays.asList(ids));
    }

    private VenueBookingView book(UUID key, UUID... participantIds) {
        return service.book(ACCOUNT, "王女士", key, command(PRICE * participantIds.length, participantIds)).booking();
    }

    private VenueBookingView paidBooking(UUID key, UUID... participantIds) {
        VenueBookingView pending = book(key, participantIds);
        service.onPaymentConfirmed(payments.orderOf(pending.bookingId()), pending.bookingId());
        return service.readBooking(ACCOUNT, pending.bookingId());
    }

    private String codeOf(UUID bookingId) {
        return mapper.booking(bookingId).code;
    }

    private void assertNothingBooked() {
        assertThat(mapper.bookings).isEmpty();
        assertThat(mapper.participants).isEmpty();
        assertThat(mapper.events).isEmpty();
        assertThat(payments.opened).isEmpty();
        assertThat(mapper.session(SESSION).booked).isEqualTo(2);
    }

    private static void assertRejected(Error error, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(VenueBookingException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    /** 本人、小明（10 岁）、小宝（5 岁）。 */
    private static final class FakeParticipants implements VenueParticipantPort {
        final List<String> accountNames = new ArrayList<>();

        @Override
        public List<VenueParticipant> participants(UUID accountId, String accountName) {
            accountNames.add(accountName);
            if (!ACCOUNT.equals(accountId)) {
                return List.of();
            }
            return List.of(
                    new VenueParticipant(SELF, true, "王女士", null),
                    new VenueParticipant(MING, false, "小明", LocalDate.parse("2016-05-01")),
                    new VenueParticipant(BAO, false, "小宝", LocalDate.parse("2021-03-01")));
        }
    }

    /** 交易模块的替身：记下每次调用；取消未付款订单时像交易模块一样回调关闭（或查单发现已付款时回调到账）。 */
    private final class FakePayments implements VenuePaymentPort {
        final Set<UUID> collecting = new HashSet<>(Set.of(OPERATOR_ORG));
        final List<UUID> merchantQueries = new ArrayList<>();
        final List<OpenPayment> opened = new ArrayList<>();
        final Map<UUID, UUID> bookingOfOrder = new HashMap<>();
        final List<UUID> orderLocks = new ArrayList<>();
        final List<Refund> refunds = new ArrayList<>();
        final Map<UUID, RefundProgress> progress = new HashMap<>();
        final List<UUID> cancelled = new ArrayList<>();
        boolean paidBeforeCancel;
        VenueBookingService callback;

        UUID orderOf(UUID bookingId) {
            return UUID.nameUUIDFromBytes(("order:" + bookingId).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean merchantCanCollect(UUID organizationId) {
            merchantQueries.add(organizationId);
            return collecting.contains(organizationId);
        }

        @Override
        public OpenedPayment open(OpenPayment request) {
            opened.add(request);
            UUID order = orderOf(request.bookingId());
            bookingOfOrder.put(order, request.bookingId());
            return new OpenedPayment(order, request.payBefore());
        }

        @Override
        public void lockOrder(UUID tradeOrderId) {
            orderLocks.add(tradeOrderId);
            mapper.locks.add("order:" + tradeOrderId);
        }

        @Override
        public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                                  UUID actorId) {
            UUID refundId = new UUID(0x5000L, refunds.size() + 1);
            refunds.add(new Refund(refundId, tradeOrderId, amountMinor, reason, idempotencyKey, actorId));
            progress.put(refundId, new RefundProgress("REQUESTED", amountMinor));
            return refundId;
        }

        @Override
        public Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds) {
            Map<UUID, RefundProgress> found = new HashMap<>();
            refundIds.forEach(id -> {
                if (progress.containsKey(id)) {
                    found.put(id, progress.get(id));
                }
            });
            return found;
        }

        @Override
        public void cancelUnpaid(UUID accountId, UUID tradeOrderId) {
            cancelled.add(tradeOrderId);
            UUID bookingId = bookingOfOrder.get(tradeOrderId);
            if (paidBeforeCancel) {
                callback.onPaymentConfirmed(tradeOrderId, bookingId);
            } else {
                callback.onPaymentClosed(tradeOrderId, bookingId);
            }
        }

        record Refund(UUID refundId, UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                      UUID actorId) {
        }
    }

    /**
     * 内存里的 mapper：每个方法按对应 SQL 的条件写，V002 的约束（金额、付款、核销码、一人一场一张有效预约、名额）
     * 在这里用异常代替，服务一旦写出数据库不会接受的数据，测试就会失败。locks 按顺序记下每次加锁。
     */
    private static final class InMemoryMapper implements VenueBookingMapper {

        private static final Set<String> EVENT_TYPES = Set.of("BOOKED", "PAYMENT_CONFIRMED", "PAYMENT_CLOSED",
                "PAYMENT_RESTORED", "RESCHEDULED", "CANCELLED", "REFUND_REQUESTED", "CHECKED_IN");
        private static final Set<String> SYSTEM_EVENTS = Set.of("PAYMENT_CONFIRMED", "PAYMENT_CLOSED", "PAYMENT_RESTORED");

        final Map<UUID, Session> sessions = new LinkedHashMap<>();
        final List<Booking> bookings = new ArrayList<>();
        final List<Participant> participants = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        final List<String> locks = new ArrayList<>();
        private long sequence;

        InMemoryMapper() {
            sessions.put(SESSION, new Session(SESSION, PROJECT, STARTS_AT, 8, 2, 8, PRICE, "机器人避障闯关"));
            sessions.put(LATER_SESSION, new Session(LATER_SESSION, PROJECT, OffsetDateTime.parse("2026-11-21T06:00:00Z"),
                    8, 3, 8, PRICE, "机器人避障闯关"));
            sessions.put(OTHER_PROJECT_SESSION, new Session(OTHER_PROJECT_SESSION, OTHER_PROJECT,
                    OffsetDateTime.parse("2026-11-21T08:00:00Z"), 8, 0, 8, PRICE, "无人机模拟飞行"));
            sessions.put(FREE_SESSION, new Session(FREE_SESSION, FREE_PROJECT, STARTS_AT, 30, 0, null, 0, "免费参观"));
        }

        Session session(UUID id) {
            return sessions.get(id);
        }

        Booking booking(UUID id) {
            return bookings.stream().filter(row -> row.id.equals(id)).findFirst().orElseThrow();
        }

        // ---- 场次 ----

        @Override
        public SessionRow lockSession(UUID sessionId) {
            locks.add("session:" + sessionId);
            return findSession(sessionId);
        }

        @Override
        public SessionRow findSession(UUID sessionId) {
            Session row = sessions.get(sessionId);
            return row == null ? null : row.toRow();
        }

        @Override
        public int addBooked(UUID sessionId, int delta, OffsetDateTime at) {
            Session row = sessions.get(sessionId);
            int next = row.booked + delta;
            if (next < 0 || next > row.capacity) {
                throw new IllegalStateException("ck_venue_session_seats");
            }
            row.booked = next;
            return 1;
        }

        // ---- 预约单 ----

        @Override
        public KeyRow findBookingByKey(UUID accountId, UUID idempotencyKey) {
            return bookings.stream()
                    .filter(row -> row.accountId.equals(accountId) && row.idempotencyKey.equals(idempotencyKey))
                    .map(row -> new KeyRow(row.id, row.requestHash))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<UUID> findBookedParticipantIds(UUID sessionId, UUID accountId, List<UUID> participantIds) {
            if (participantIds.isEmpty()) {
                throw new IllegalStateException("IN () is not valid SQL");
            }
            return participants.stream()
                    .filter(row -> row.sessionId.equals(sessionId) && row.active)
                    .filter(row -> booking(row.bookingId).accountId.equals(accountId))
                    .filter(row -> participantIds.contains(row.participantId))
                    .map(row -> row.participantId)
                    .toList();
        }

        @Override
        public int insertBooking(UUID bookingId, UUID accountId, UUID venueId, UUID projectId, UUID sessionId,
                                 String status, String code, int people, long unitAmountMinor, long amountMinor,
                                 UUID tradeOrderId, OffsetDateTime payBefore, UUID idempotencyKey, String requestHash,
                                 OffsetDateTime confirmedAt, OffsetDateTime at) {
            if (findBookingByKey(accountId, idempotencyKey) != null) {
                throw new IllegalStateException("UNIQUE (account_id, idempotency_key)");
            }
            if (amountMinor != unitAmountMinor * people || people < 1 || people > 10) {
                throw new IllegalStateException("ck_venue_booking_amount");
            }
            if ((amountMinor > 0) != (tradeOrderId != null && payBefore != null)
                    || (amountMinor == 0 && (tradeOrderId != null || payBefore != null))) {
                throw new IllegalStateException("ck_venue_booking_payment");
            }
            checkCode(status, code);
            if (Set.of("PENDING_PAYMENT", "CLOSED").contains(status) != (confirmedAt == null)) {
                throw new IllegalStateException("ck_venue_booking_confirmed");
            }
            if (requestHash.length() != 64) {
                throw new IllegalStateException("request_hash char(64)");
            }
            Booking row = new Booking();
            row.id = bookingId;
            row.accountId = accountId;
            row.venueId = venueId;
            row.projectId = projectId;
            row.sessionId = sessionId;
            row.status = status;
            row.code = code;
            row.people = people;
            row.unit = unitAmountMinor;
            row.amount = amountMinor;
            row.tradeOrderId = tradeOrderId;
            row.payBefore = payBefore;
            row.idempotencyKey = idempotencyKey;
            row.requestHash = requestHash;
            row.createdAt = at;
            row.sequence = ++sequence;
            bookings.add(row);
            return 1;
        }

        private void checkCode(String status, String code) {
            if (Set.of("PENDING_PAYMENT", "CLOSED").contains(status) != (code == null)) {
                throw new IllegalStateException("ck_venue_booking_code");
            }
            if (code != null && bookings.stream().anyMatch(row -> code.equals(row.code))) {
                throw new IllegalStateException("code UNIQUE");
            }
        }

        @Override
        public int insertParticipant(UUID bookingId, UUID sessionId, UUID participantId, String name, String relation,
                                     Integer age, OffsetDateTime at) {
            if (participants.stream().anyMatch(row -> row.bookingId.equals(bookingId)
                    && row.participantId.equals(participantId))) {
                throw new IllegalStateException("UNIQUE (booking_id, participant_id)");
            }
            requireFreeSeat(sessionId, participantId, null);
            Participant row = new Participant();
            row.id = ++sequence;
            row.bookingId = bookingId;
            row.sessionId = sessionId;
            row.participantId = participantId;
            row.name = name;
            row.relation = relation;
            row.age = age;
            participants.add(row);
            return 1;
        }

        private void requireFreeSeat(UUID sessionId, UUID participantId, Participant except) {
            boolean taken = participants.stream().anyMatch(row -> row != except && row.active
                    && row.sessionId.equals(sessionId) && row.participantId.equals(participantId));
            if (taken) {
                throw new IllegalStateException("uq_venue_booking_participant_active");
            }
        }

        @Override
        public int insertEvent(UUID bookingId, String eventType, UUID actorId, UUID fromSessionId, UUID toSessionId,
                               OffsetDateTime at) {
            if (!EVENT_TYPES.contains(eventType) || at == null
                    || (actorId == null && !SYSTEM_EVENTS.contains(eventType))
                    || ("RESCHEDULED".equals(eventType) != (fromSessionId != null && toSessionId != null))) {
                throw new IllegalStateException("venue_booking_event constraint");
            }
            events.add(new Event(bookingId, eventType, actorId, fromSessionId, toSessionId));
            return 1;
        }

        @Override
        public BookingRow findBooking(UUID accountId, UUID bookingId) {
            return bookings.stream()
                    .filter(row -> row.accountId.equals(accountId) && row.id.equals(bookingId))
                    .map(this::toRow)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public long countBookings(UUID accountId, String bucket, OffsetDateTime now) {
            return inBucket(accountId, bucket, now).size();
        }

        @Override
        public List<BookingRow> findBookingPage(UUID accountId, String bucket, OffsetDateTime now, int limit,
                                                int offset) {
            return inBucket(accountId, bucket, now).stream().skip(offset).limit(limit).map(this::toRow).toList();
        }

        private List<Booking> inBucket(UUID accountId, String bucket, OffsetDateTime now) {
            return bookings.stream()
                    .filter(row -> row.accountId.equals(accountId))
                    .filter(row -> {
                        boolean ended = !sessions.get(row.sessionId).endsAt.isAfter(now);
                        return switch (bucket) {
                            case "UPCOMING" -> row.status.equals("PENDING_PAYMENT")
                                    || (row.status.equals("CONFIRMED") && !ended);
                            case "FINISHED" -> row.status.equals("CHECKED_IN")
                                    || (row.status.equals("CONFIRMED") && ended);
                            case "CANCELLED" -> Set.of("CANCELLED", "CLOSED").contains(row.status);
                            default -> false;
                        };
                    })
                    .sorted(Comparator.comparing((Booking row) -> sessions.get(row.sessionId).startsAt))
                    .toList();
        }

        private BookingRow toRow(Booking row) {
            Session session = sessions.get(row.sessionId);
            return new BookingRow(row.id, row.status, row.code, row.people, row.unit, row.amount, row.tradeOrderId,
                    row.payBefore, row.refundId, row.checkedInAt, row.cancelledAt, row.createdAt, row.version,
                    VENUE, "东营低空与机器人体验馆", "370500", "东营区示例路 66 号", " ", row.projectId, session.projectName, 30,
                    row.sessionId, session.startsAt, session.endsAt);
        }

        @Override
        public List<ParticipantRow> findParticipants(List<UUID> bookingIds) {
            if (bookingIds.isEmpty()) {
                throw new IllegalStateException("IN () is not valid SQL");
            }
            return participants.stream()
                    .filter(row -> bookingIds.contains(row.bookingId))
                    .sorted(Comparator.comparing((Participant row) -> !row.relation.equals("SELF"))
                            .thenComparing(row -> row.name))
                    .map(row -> new ParticipantRow(row.bookingId, row.participantId, row.name, row.relation, row.age))
                    .toList();
        }

        // ---- 状态 ----

        private StateRow state(Booking row) {
            return new StateRow(row.id, row.accountId, row.projectId, row.sessionId, row.status, row.people,
                    row.amount, row.tradeOrderId, row.version);
        }

        @Override
        public StateRow lockBooking(UUID accountId, UUID bookingId) {
            locks.add("booking:" + bookingId);
            return bookings.stream()
                    .filter(row -> row.accountId.equals(accountId) && row.id.equals(bookingId))
                    .map(this::state)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public StateRow findPaidBooking(UUID bookingId, UUID tradeOrderId) {
            return bookings.stream()
                    .filter(row -> row.id.equals(bookingId) && tradeOrderId.equals(row.tradeOrderId))
                    .map(this::state)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public StateRow lockPaidBooking(UUID bookingId, UUID tradeOrderId) {
            locks.add("booking:" + bookingId);
            return findPaidBooking(bookingId, tradeOrderId);
        }

        private Booking inStatus(UUID bookingId, String status) {
            return bookings.stream()
                    .filter(row -> row.id.equals(bookingId) && row.status.equals(status))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int confirmPayment(UUID bookingId, String code, OffsetDateTime at) {
            Booking row = inStatus(bookingId, "PENDING_PAYMENT");
            if (row == null) {
                return 0;
            }
            checkCode("CONFIRMED", code);
            row.status = "CONFIRMED";
            row.code = code;
            row.version++;
            return 1;
        }

        @Override
        public int closeUnpaid(UUID bookingId, OffsetDateTime at) {
            Booking row = inStatus(bookingId, "PENDING_PAYMENT");
            if (row == null) {
                return 0;
            }
            row.status = "CLOSED";
            row.version++;
            return 1;
        }

        @Override
        public int restoreClosed(UUID bookingId, String code, OffsetDateTime at) {
            Booking row = inStatus(bookingId, "CLOSED");
            if (row == null) {
                return 0;
            }
            checkCode("CONFIRMED", code);
            row.status = "CONFIRMED";
            row.code = code;
            row.version++;
            return 1;
        }

        @Override
        public int cancelConfirmed(UUID bookingId, OffsetDateTime at) {
            Booking row = inStatus(bookingId, "CONFIRMED");
            if (row == null) {
                return 0;
            }
            row.status = "CANCELLED";
            row.cancelledAt = at;
            row.version++;
            return 1;
        }

        @Override
        public int setRefund(UUID bookingId, UUID refundId, OffsetDateTime at) {
            Booking row = inStatus(bookingId, "CANCELLED");
            if (row == null || row.refundId != null || row.tradeOrderId == null) {
                return 0;
            }
            row.refundId = refundId;
            row.version++;
            return 1;
        }

        @Override
        public int moveBooking(UUID bookingId, UUID sessionId, String code, int version, OffsetDateTime at) {
            Booking row = inStatus(bookingId, "CONFIRMED");
            if (row == null || row.version != version) {
                return 0;
            }
            checkCode("CONFIRMED", code);
            row.sessionId = sessionId;
            row.code = code;
            row.version++;
            return 1;
        }

        @Override
        public int releaseParticipants(UUID bookingId, OffsetDateTime at) {
            int changed = 0;
            for (Participant row : participants) {
                if (row.bookingId.equals(bookingId) && row.active) {
                    row.active = false;
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public int holdParticipants(UUID bookingId, OffsetDateTime at) {
            int changed = 0;
            for (Participant row : participants) {
                if (row.bookingId.equals(bookingId) && !row.active) {
                    requireFreeSeat(row.sessionId, row.participantId, row);
                    row.active = true;
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public int moveParticipants(UUID bookingId, UUID sessionId, OffsetDateTime at) {
            int changed = 0;
            for (Participant row : participants) {
                if (row.bookingId.equals(bookingId)) {
                    if (row.active) {
                        requireFreeSeat(sessionId, row.participantId, row);
                    }
                    row.sessionId = sessionId;
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public int countOtherActiveParticipants(UUID bookingId, UUID sessionId) {
            Set<UUID> mine = new HashSet<>();
            participants.stream().filter(row -> row.bookingId.equals(bookingId))
                    .forEach(row -> mine.add(row.participantId));
            return (int) participants.stream()
                    .filter(row -> row.sessionId.equals(sessionId) && row.active && !row.bookingId.equals(bookingId))
                    .filter(row -> mine.contains(row.participantId))
                    .count();
        }

        // ---- 核销 ----

        /** 场馆的检票规则（S3-1）：默认和迁移一样开场前 30 分钟、到场次结束。 */
        int checkinOpensMinutes = 30;
        Integer lateEntryMinutes;

        @Override
        public CheckinRow lockBookingByCode(String code, UUID organizationId) {
            if (!OPERATOR_ORG.equals(organizationId)) {
                return null;
            }
            return bookings.stream()
                    .filter(row -> code.equals(row.code))
                    .map(row -> {
                        locks.add("booking:" + row.id);
                        Session session = sessions.get(row.sessionId);
                        return new CheckinRow(row.id, row.code, row.status, row.people, row.checkedInAt,
                                "东营低空与机器人体验馆", session.projectName, session.startsAt, session.endsAt,
                                checkinOpensMinutes, lateEntryMinutes);
                    })
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int checkIn(UUID bookingId, UUID operatorId, OffsetDateTime at) {
            Booking row = inStatus(bookingId, "CONFIRMED");
            if (row == null || operatorId == null) {
                return 0;
            }
            row.status = "CHECKED_IN";
            row.checkedInAt = at;
            row.checkedInBy = operatorId;
            row.version++;
            return 1;
        }

        static final class Session {
            final UUID id;
            final UUID projectId;
            final OffsetDateTime startsAt;
            final OffsetDateTime endsAt;
            final int capacity;
            int booked;
            Integer minAge;
            final long price;
            final String projectName;
            String status = "ACTIVE";
            String projectStatus = "ACTIVE";
            boolean suspended;

            Session(UUID id, UUID projectId, OffsetDateTime startsAt, int capacity, int booked, Integer minAge,
                    long price, String projectName) {
                this.id = id;
                this.projectId = projectId;
                this.startsAt = startsAt;
                this.endsAt = startsAt.plusMinutes(30);
                this.capacity = capacity;
                this.booked = booked;
                this.minAge = minAge;
                this.price = price;
                this.projectName = projectName;
            }

            SessionRow toRow() {
                return new SessionRow(id, projectId, VENUE, OPERATOR_ORG, "PUBLISHED", projectStatus, status,
                        startsAt, endsAt, capacity, booked, minAge, price, "东营低空与机器人体验馆", projectName,
                        "370500 ", null, suspended);
            }
        }

        static final class Booking {
            UUID id;
            UUID accountId;
            UUID venueId;
            UUID projectId;
            UUID sessionId;
            String status;
            String code;
            int people;
            long unit;
            long amount;
            UUID tradeOrderId;
            OffsetDateTime payBefore;
            UUID refundId;
            UUID idempotencyKey;
            String requestHash;
            OffsetDateTime createdAt;
            OffsetDateTime checkedInAt;
            UUID checkedInBy;
            OffsetDateTime cancelledAt;
            int version;
            long sequence;
        }

        static final class Participant {
            long id;
            UUID bookingId;
            UUID sessionId;
            UUID participantId;
            String name;
            String relation;
            Integer age;
            boolean active = true;
        }

        record Event(UUID bookingId, String type, UUID actor, UUID from, UUID to) {
        }
    }
}
