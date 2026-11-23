package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentState;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders.Line;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders.OpenCommand;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders.OpenedOrder;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders.RefundState;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradePaymentMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeSourceMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 业务来源的订单（E09，收费活动报名）：建订单、付款和关闭回告业务模块、业务收不下时自动退款、
 * 业务模块发起的退款在提交后才交给渠道、买家取消未付款的订单、商城订单不受影响。
 *
 * <p>四个 mapper 换成共用一份数据的内存实现（按 SQL 的条件和 V001–V005 的约束写，违反约束时抛异常）；
 * 订单、支付、退款服务都是真的；“事务提交”由测试显式执行登记在提交之后的动作。
 */
class TradeSourceOrdersTest {

    private static final Instant NOW = Instant.parse("2026-11-19T02:00:00Z");
    private static final UUID BUYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_BUYER = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID ORGANIZER = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private static final UUID UNREGISTERED = UUID.fromString("a1000000-0000-4000-8000-000000000003");
    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("a3000000-0000-4000-8000-000000000101");
    private static final UUID ENROLLMENT = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final String SP_MCHID = "1900000001";
    private static final String SUB_MCHID = "1900000201";
    private static final long UNIT = 12_000;

    private final Store store = new Store();
    private final MutableClock clock = new MutableClock(NOW);
    private final FakeChannel channel = new FakeChannel();
    private final List<Runnable> afterCommit = new ArrayList<>();
    private final RecordingListener listener = new RecordingListener();
    private final List<TradeSourceListener> listeners = new ArrayList<>(List.of(listener));
    private long suffixes;
    private TradeRefundService refunds;
    private final TradeSourceEvents events = new TradeSourceEvents(
            () -> listeners, store.sourceMapper, () -> refunds, afterCommit::add);
    private final TradeOrderService orders = new TradeOrderService(
            store.orderMapper, null, clock, () -> String.format("%08d", ++suffixes), events);
    private final TradePaymentService payments;
    private final TradeSourceOrders sourceOrders;

    TradeSourceOrdersTest() {
        refunds = new TradeRefundService(store.refundMapper, store.orderMapper, orders, () -> channel,
                TransactionOperations.withoutTransaction(), clock, () -> String.format("%012d", ++suffixes));
        payments = new TradePaymentService(store.paymentMapper, store.orderMapper, orders, () -> channel,
                TransactionOperations.withoutTransaction(), clock, () -> String.format("%012d", ++suffixes),
                () -> "000000", refunds, events);
        sourceOrders = new TradeSourceOrders(store.sourceMapper, store.orderMapper, orders, payments, refunds,
                afterCommit::add, clock);
        store.accounts.put(ORGANIZER, new TradeOrderMapper.MerchantAccountRow(ORGANIZER, SUB_MCHID, true));
        store.organizations.put(ORGANIZER, "示例青少年科技机构");
        store.organizations.put(UNREGISTERED, "示例培训机构");
    }

    // ---- 建订单 ----

    @Test
    void anActivityOrderIsAPendingOrderWithoutDeliveryOrMerchantKind() {
        Instant payBefore = NOW.plus(Duration.ofMinutes(15));

        OpenedOrder opened = sourceOrders.open(command(ENROLLMENT, 2, payBefore));

        Store.Order order = store.order(opened.orderId());
        assertThat(opened.orderNo()).isEqualTo("AO2026111900000001");
        assertThat(opened.payBefore()).isEqualTo(payBefore);
        assertThat(order.source).isEqualTo("ACTIVITY");
        assertThat(order.sourceRef).isEqualTo(ENROLLMENT);
        assertThat(order.deliveryMode).isEqualTo("NONE");
        assertThat(order.merchantKind).isNull();
        assertThat(order.merchantName).isEqualTo("示例青少年科技机构");
        assertThat(order.subMchid).isEqualTo(SUB_MCHID);
        assertThat(order.payableMinor).isEqualTo(2 * UNIT);
        // 幂等键就是业务单 ID。
        assertThat(order.idempotencyKey).isEqualTo(ENROLLMENT);
        assertThat(order.requestHash).hasSize(64);
        assertThat(store.lines).singleElement().satisfies(line -> {
            assertThat(line.productId).isEqualTo(ACTIVITY);
            assertThat(line.offerId).isEqualTo(SESSION);
            assertThat(line.productName).isEqualTo("青少年无人机编程挑战赛");
            assertThat(line.skuName).isEqualTo("11月28日 上午场");
            assertThat(line.quantity).isEqualTo(2);
            assertThat(line.subtotalMinor).isEqualTo(2 * UNIT);
        });
        assertThat(store.transitions).containsExactly(
                new Store.Transition(opened.orderId(), null, "PENDING_PAYMENT", "SUBMIT", BUYER));

        TradeOrderView view = orders.read(BUYER, opened.orderId());
        assertThat(view.source()).isEqualTo("ACTIVITY");
        assertThat(view.sourceRef()).isEqualTo(ENROLLMENT);
        assertThat(view.delivery()).isNull();
        assertThat(view.merchant().kind()).isNull();
        assertThat(view.payBefore()).isEqualTo(payBefore);
        assertThat(view.cancellable()).isTrue();
    }

    // E10：到馆预约是第二个业务来源，订单编号以 VO 开头；同一个业务单 ID 在不同来源里互不相干。
    @Test
    void aVenueBookingIsItsOwnSourceWithItsOwnOrderNumbers() {
        OpenedOrder venue = sourceOrders.open(new OpenCommand("VENUE", ENROLLMENT, BUYER, ORGANIZER, "370500",
                List.of(line(2)), NOW.plusSeconds(900)));
        OpenedOrder activity = sourceOrders.open(command(ENROLLMENT, 1, NOW.plusSeconds(900)));

        assertThat(venue.orderNo()).startsWith("VO20261119");
        // F3：设备租赁的订单编号以 RO 开头。
        OpenedOrder rental = sourceOrders.open(new OpenCommand("RENTAL", ENROLLMENT, BUYER, ORGANIZER, "370500",
                List.of(line(1)), NOW.plusSeconds(1800)));
        assertThat(rental.orderNo()).startsWith("RO20261119");
        assertThat(store.order(rental.orderId()).source).isEqualTo("RENTAL");
        assertThat(activity.orderNo()).startsWith("AO20261119");
        assertThat(venue.orderId()).isNotEqualTo(activity.orderId());
        assertThat(store.order(venue.orderId()).source).isEqualTo("VENUE");
        // 监听者只认自己的来源：到馆订单关闭时，收费报名的监听者收不到。
        sourceOrders.cancelUnpaid(BUYER, venue.orderId());
        assertThat(listener.closed).isEmpty();
    }

    @Test
    void openingTheSameSourceReferenceAgainReturnsTheFirstOrder() {
        OpenedOrder first = sourceOrders.open(command(ENROLLMENT, 1, NOW.plusSeconds(900)));
        OpenedOrder again = sourceOrders.open(command(ENROLLMENT, 1, NOW.plusSeconds(900)));

        assertThat(again).isEqualTo(first);
        assertThat(store.orders).hasSize(1);
        assertThat(store.lines).hasSize(1);
        // 别的买家拿同一张业务单来建订单：拒绝，不泄露别人的订单。
        assertRefused(Error.IDEMPOTENCY_CONFLICT, () -> sourceOrders.open(new OpenCommand("ACTIVITY", ENROLLMENT,
                OTHER_BUYER, ORGANIZER, "370500", List.of(line(1)), NOW.plusSeconds(900))));
    }

    @Test
    void anOrganizerWithoutAnActiveMerchantAccountCannotCollect() {
        assertThat(sourceOrders.merchantCanCollect(ORGANIZER)).isTrue();
        assertThat(sourceOrders.merchantCanCollect(UNREGISTERED)).isFalse();
        assertThat(sourceOrders.merchantCanCollect(null)).isFalse();
        assertRefused(Error.MERCHANT_PAYMENT_UNAVAILABLE, () -> sourceOrders.open(new OpenCommand("ACTIVITY",
                ENROLLMENT, BUYER, UNREGISTERED, "370500", List.of(line(1)), NOW.plusSeconds(900))));
        store.accounts.put(ORGANIZER, new TradeOrderMapper.MerchantAccountRow(ORGANIZER, SUB_MCHID, false));
        assertRefused(Error.MERCHANT_PAYMENT_UNAVAILABLE,
                () -> sourceOrders.open(command(ENROLLMENT, 1, NOW.plusSeconds(900))));

        assertThat(store.orders).isEmpty();
    }

    @Test
    void anInvalidSourceOrderIsRejectedBeforeAnythingIsWritten() {
        Instant later = NOW.plusSeconds(900);
        assertInvalid(() -> sourceOrders.open(new OpenCommand("MALL", ENROLLMENT, BUYER, ORGANIZER, "370500",
                List.of(line(1)), later)));
        assertInvalid(() -> sourceOrders.open(new OpenCommand("ACTIVITY", null, BUYER, ORGANIZER, "370500",
                List.of(line(1)), later)));
        assertInvalid(() -> sourceOrders.open(new OpenCommand("ACTIVITY", ENROLLMENT, BUYER, ORGANIZER, "济南",
                List.of(line(1)), later)));
        assertInvalid(() -> sourceOrders.open(new OpenCommand("ACTIVITY", ENROLLMENT, BUYER, ORGANIZER, "370500",
                List.of(), later)));
        assertInvalid(() -> sourceOrders.open(new OpenCommand("ACTIVITY", ENROLLMENT, BUYER, ORGANIZER, "370500",
                List.of(new Line(ACTIVITY, SESSION, "挑战赛", "上午场", null, 0, 1)), later)));
        assertInvalid(() -> sourceOrders.open(new OpenCommand("ACTIVITY", ENROLLMENT, BUYER, ORGANIZER, "370500",
                List.of(new Line(ACTIVITY, SESSION, " ", "上午场", null, UNIT, 1)), later)));
        assertInvalid(() -> sourceOrders.open(command(ENROLLMENT, 1, NOW)));

        assertThat(store.orders).isEmpty();
    }

    // ---- 付款 ----

    @Test
    void aPaymentIsHandedToTheSourceInTheSameStepAndNothingIsRefundedWhenItIsAccepted() {
        OpenedOrder opened = sourceOrders.open(command(ENROLLMENT, 2, NOW.plusSeconds(900)));
        Store.Payment payment = store.launch(opened.orderId());

        assertThat(payments.apply(channel, channel.paid(payment), "PAYMENT_NOTIFIED"))
                .isEqualTo(TradePaymentService.Applied.APPLIED);

        assertThat(listener.paid).containsExactly(List.of(opened.orderId(), ENROLLMENT));
        assertThat(store.order(opened.orderId()).status).isEqualTo("PAID");
        assertThat(store.refunds).isEmpty();
        assertThat(afterCommit).isEmpty();
        // 已付款的收费报名订单不能在交易这边取消，退款由报名单的退改发起。
        TradeOrderView view = orders.read(BUYER, opened.orderId());
        assertThat(view.cancellable()).isFalse();
        assertRefused(Error.ORDER_STATE_INVALID, () -> orders.cancel(BUYER, opened.orderId(), view.version()));
        // 同一笔付款再报一次：已入过账，不再通知业务模块。
        assertThat(payments.apply(channel, channel.paid(payment), "PAYMENT_QUERIED"))
                .isEqualTo(TradePaymentService.Applied.ALREADY);
        assertThat(listener.paid).hasSize(1);
    }

    @Test
    void aLatePaymentTheSourceCannotTakeIsRefundedInFullAfterTheCommit() {
        OpenedOrder opened = sourceOrders.open(command(ENROLLMENT, 2, NOW.plusSeconds(900)));
        Store.Payment payment = store.launch(opened.orderId());
        store.closeLocally(opened.orderId(), payment);
        listener.accept = false;

        payments.apply(channel, channel.paid(payment), "PAYMENT_NOTIFIED");

        Store.Order order = store.order(opened.orderId());
        assertThat(order.status).isEqualTo("REFUNDING");
        assertThat(order.paidMinor).isEqualTo(2 * UNIT);
        Store.Refund refund = store.onlyRefund();
        assertThat(refund.amountMinor).isEqualTo(2 * UNIT);
        assertThat(refund.initiator).isEqualTo("SOURCE");
        assertThat(refund.requestedBy).isNull();
        assertThat(refund.reason).isEqualTo(TradeSourceEvents.AUTO_REFUND_REASON);
        assertThat(store.transitions).extracting(Store.Transition::toStatus, Store.Transition::cause)
                .containsSubsequence(
                        org.assertj.core.groups.Tuple.tuple("PAID", "PAYMENT_NOTIFIED"),
                        org.assertj.core.groups.Tuple.tuple("REFUNDING", "SOURCE_REFUND"));
        // 事务提交之前不交给渠道。
        assertThat(channel.refunds).isEmpty();
        assertThat(refund.status).isEqualTo("REQUESTED");

        commit();

        assertThat(channel.refunds).containsExactly(refund.outRefundNo + ":" + 2 * UNIT);
        assertThat(refund.status).isEqualTo("PROCESSING");
        // 同一笔付款再报一次：不会再建一笔退款。
        payments.apply(channel, channel.paid(payment), "PAYMENT_QUERIED");
        assertThat(store.refunds).hasSize(1);
        assertThat(listener.paid).hasSize(1);
    }

    @Test
    void aLatePaymentTheSourceTakesBackReopensTheOrderAsPaid() {
        OpenedOrder opened = sourceOrders.open(command(ENROLLMENT, 1, NOW.plusSeconds(900)));
        Store.Payment payment = store.launch(opened.orderId());
        store.closeLocally(opened.orderId(), payment);

        payments.apply(channel, channel.paid(payment), "PAYMENT_NOTIFIED");

        assertThat(store.order(opened.orderId()).status).isEqualTo("PAID");
        assertThat(listener.paid).hasSize(1);
        assertThat(store.refunds).isEmpty();
    }

    @Test
    void mallOrdersNeverReachAListener() {
        UUID mallOrder = store.mallOrder();

        // 买家取消，之后才到账的付款把它重新打开成已付款：两步都不通知任何业务模块。
        orders.cancel(BUYER, mallOrder, 0);
        payments.apply(channel, channel.paid(store.launch(mallOrder)), "PAYMENT_NOTIFIED");

        assertThat(listener.paid).isEmpty();
        assertThat(listener.closed).isEmpty();
        assertThat(store.order(mallOrder).status).isEqualTo("PAID");
        assertThat(store.refunds).isEmpty();
    }

    @Test
    void withoutListenersTheSourceOfAnOrderIsNotEvenLookedUp() {
        listeners.clear();
        TradeSourceEvents quiet = new TradeSourceEvents(List::of, null, () -> null, afterCommit::add);

        quiet.paid(UUID.randomUUID(), UUID.randomUUID());
        quiet.closed(UUID.randomUUID());
        TradeSourceEvents.none().closed(UUID.randomUUID());

        assertThat(afterCommit).isEmpty();
    }

    // ---- 关闭 ----

    @Test
    void cancellingAnUnpaidOrderClosesItAndTellsTheSourceOnce() {
        OpenedOrder opened = sourceOrders.open(command(ENROLLMENT, 2, NOW.plusSeconds(900)));

        assertThat(sourceOrders.cancelUnpaid(BUYER, opened.orderId())).isEqualTo(TradeOrderStatus.CLOSED);
        assertThat(sourceOrders.cancelUnpaid(BUYER, opened.orderId())).isEqualTo(TradeOrderStatus.CLOSED);

        assertThat(listener.closed).containsExactly(List.of(opened.orderId(), ENROLLMENT));
        assertThat(store.transitions).last()
                .isEqualTo(new Store.Transition(opened.orderId(), "PENDING_PAYMENT", "CLOSED", "BUYER_CANCEL", BUYER));
        // 不是自己的订单：当作不存在。
        assertRefused(Error.NOT_FOUND, () -> sourceOrders.cancelUnpaid(OTHER_BUYER, opened.orderId()));
    }

    @Test
    void cancellingAnOrderWithALaunchedPaymentQueriesAndClosesItAtTheChannelFirst() {
        OpenedOrder opened = sourceOrders.open(command(ENROLLMENT, 1, NOW.plusSeconds(900)));
        Store.Payment payment = store.launch(opened.orderId());
        channel.answers.add((sub, no) -> channel.state(payment, PaymentState.NOTPAY));

        assertThat(sourceOrders.cancelUnpaid(BUYER, opened.orderId())).isEqualTo(TradeOrderStatus.CLOSED);

        assertThat(channel.calls).containsExactly("query:" + payment.outTradeNo, "close:" + payment.outTradeNo);
        assertThat(payment.status).isEqualTo("CLOSED");
        assertThat(listener.closed).hasSize(1);
    }

    @Test
    void cancellingAnOrderTheBuyerHasJustPaidKeepsItPaid() {
        OpenedOrder opened = sourceOrders.open(command(ENROLLMENT, 1, NOW.plusSeconds(900)));
        Store.Payment payment = store.launch(opened.orderId());
        channel.answers.add((sub, no) -> channel.paid(payment));

        assertThat(sourceOrders.cancelUnpaid(BUYER, opened.orderId())).isEqualTo(TradeOrderStatus.PAID);

        assertThat(listener.paid).hasSize(1);
        assertThat(listener.closed).isEmpty();
    }

    @Test
    void timedOutOrdersAreClosedAndTheSourceIsTold() {
        OpenedOrder quiet = sourceOrders.open(command(ENROLLMENT, 1, NOW.plusSeconds(900)));
        UUID secondRef = UUID.fromString("e1000000-0000-4000-8000-000000000002");
        OpenedOrder launched = sourceOrders.open(command(secondRef, 1, NOW.plusSeconds(900)));
        Store.Payment payment = store.launch(launched.orderId());
        clock.set(NOW.plusSeconds(901));
        channel.answers.add((sub, no) -> channel.state(payment, PaymentState.NOTPAY));

        assertThat(orders.closeExpired()).isEqualTo(1);
        assertThat(payments.closeExpiredWithPayments()).isEqualTo(1);

        assertThat(listener.closed).containsExactly(
                List.of(quiet.orderId(), ENROLLMENT), List.of(launched.orderId(), secondRef));
        assertThat(store.transitions).filteredOn(transition -> "CLOSED".equals(transition.toStatus()))
                .extracting(Store.Transition::cause)
                .containsExactly("TIMEOUT", "TIMEOUT");
    }

    // ---- 业务模块发起的退款 ----

    @Test
    void sourceRefundsArePartialUntilTheRemainderAndReachTheChannelOnlyAfterTheCommit() {
        OpenedOrder opened = sourceOrders.open(command(ENROLLMENT, 3, NOW.plusSeconds(900)));
        payments.apply(channel, channel.paid(store.launch(opened.orderId())), "PAYMENT_NOTIFIED");
        UUID firstKey = UUID.randomUUID();

        UUID first = sourceOrders.requestRefund(opened.orderId(), UNIT, "取消报名 1 人", firstKey, BUYER);

        assertThat(store.order(opened.orderId()).status).isEqualTo("PAID");
        assertThat(store.refund(first).initiator).isEqualTo("SOURCE");
        assertThat(store.refund(first).requestedBy).isEqualTo(BUYER);
        assertThat(channel.refunds).isEmpty();
        commit();
        assertThat(channel.refunds).hasSize(1);
        // 同一个键重放：同一笔退款；换了金额：拒绝。
        assertThat(sourceOrders.requestRefund(opened.orderId(), UNIT, "取消报名 1 人", firstKey, BUYER))
                .isEqualTo(first);
        assertRefused(Error.IDEMPOTENCY_CONFLICT,
                () -> sourceOrders.requestRefund(opened.orderId(), 2 * UNIT, "取消报名 1 人", firstKey, BUYER));
        assertRefused(Error.REFUND_EXCEEDS_PAID, () -> sourceOrders.requestRefund(
                opened.orderId(), 2 * UNIT + 1, "取消报名 2 人", UUID.randomUUID(), BUYER));

        UUID rest = sourceOrders.requestRefund(opened.orderId(), 2 * UNIT, "取消报名 2 人", UUID.randomUUID(), BUYER);

        assertThat(store.order(opened.orderId()).status).isEqualTo("REFUNDING");
        assertThat(store.transitions).last()
                .isEqualTo(new Store.Transition(opened.orderId(), "PAID", "REFUNDING", "SOURCE_REFUND", BUYER));
        assertThat(sourceOrders.refundStates(List.of(first, rest, UUID.randomUUID())))
                .containsExactlyInAnyOrder(
                        new RefundState(first, "PROCESSING", UNIT),
                        new RefundState(rest, "REQUESTED", 2 * UNIT));
        assertThat(sourceOrders.refundStates(List.of())).isEmpty();
    }

    @Test
    void anUnpaidOrderCannotBeRefundedAndLockingReportsTheStatus() {
        OpenedOrder opened = sourceOrders.open(command(ENROLLMENT, 1, NOW.plusSeconds(900)));

        assertRefused(Error.ORDER_STATE_INVALID,
                () -> sourceOrders.requestRefund(opened.orderId(), UNIT, "取消", UUID.randomUUID(), BUYER));
        assertThat(sourceOrders.lockOrder(opened.orderId())).isEqualTo(TradeOrderStatus.PENDING_PAYMENT);
        // 商城订单不给业务模块锁。
        assertThat(sourceOrders.lockOrder(store.mallOrder())).isNull();
        assertThat(store.refunds).isEmpty();
    }

    // 正式环境的“提交之后”：有 Spring 事务时登记到提交之后，提交前不做；动作失败只记日志，不让已提交的请求失败。
    @Test
    void theSpringAfterCommitRunsOnlyAfterTheCommitAndSwallowsFailures() {
        List<String> done = new ArrayList<>();
        AfterCommit spring = AfterCommit.spring();
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            spring.run(() -> done.add("refund"));
            spring.run(() -> {
                throw new IllegalStateException("channel down");
            });
            assertThat(done).isEmpty();
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(done).containsExactly("refund");
        // 没有事务时直接做。
        spring.run(() -> done.add("now"));
        assertThat(done).containsExactly("refund", "now");
    }

    // ---- 辅助 ----

    private void commit() {
        List<Runnable> actions = List.copyOf(afterCommit);
        afterCommit.clear();
        actions.forEach(Runnable::run);
    }

    private static OpenCommand command(UUID sourceRef, int people, Instant payBefore) {
        return new OpenCommand("ACTIVITY", sourceRef, BUYER, ORGANIZER, "370500", List.of(line(people)), payBefore);
    }

    private static Line line(int people) {
        return new Line(ACTIVITY, SESSION, " 青少年无人机编程挑战赛 ", "11月28日 上午场", null, UNIT, people);
    }

    private static void assertRefused(Error error, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(TradeException.class,
                refused -> assertThat(refused.error()).isEqualTo(error));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class RecordingListener implements TradeSourceListener {
        final List<List<UUID>> paid = new ArrayList<>();
        final List<List<UUID>> closed = new ArrayList<>();
        boolean accept = true;

        @Override
        public String source() {
            return "ACTIVITY";
        }

        @Override
        public boolean onPaid(UUID orderId, UUID sourceRef) {
            paid.add(List.of(orderId, sourceRef));
            return accept;
        }

        @Override
        public void onClosed(UUID orderId, UUID sourceRef) {
            closed.add(List.of(orderId, sourceRef));
        }
    }

    /** 查单按顺序编排；退款受理时记下单号和金额。 */
    private final class FakeChannel implements PaymentChannel {
        final List<String> calls = new ArrayList<>();
        final List<String> refunds = new ArrayList<>();
        final Deque<BiFunction<String, String, PaymentQuery>> answers = new ArrayDeque<>();

        @Override
        public String providerId() {
            return SP_MCHID;
        }

        @Override
        public String createAppPayment(PrepayRequest request) {
            throw new AssertionError("no prepay in these tests");
        }

        @Override
        public AppPayParams appPayParams(String prepayId) {
            throw new AssertionError("no prepay in these tests");
        }

        @Override
        public PaymentQuery queryPayment(String subMchid, String outTradeNo) {
            calls.add("query:" + outTradeNo);
            BiFunction<String, String, PaymentQuery> answer = answers.poll();
            if (answer == null) {
                throw new AssertionError("unexpected query of " + outTradeNo);
            }
            return answer.apply(subMchid, outTradeNo);
        }

        @Override
        public void closePayment(String subMchid, String outTradeNo) {
            calls.add("close:" + outTradeNo);
        }

        @Override
        public RefundAccepted applyRefund(RefundRequest request) {
            refunds.add(request.outRefundNo() + ":" + request.refundMinor());
            return new RefundAccepted("wx_refund_" + refunds.size(), request.outRefundNo(), request.refundMinor(),
                    clock.instant());
        }

        @Override
        public RefundQuery queryRefund(String subMchid, String outRefundNo) {
            throw new AssertionError("no refund query in these tests");
        }

        @Override
        public ChannelNotification parseNotification(Map<String, String> headers, String body) {
            throw new AssertionError("notifications go through apply in these tests");
        }

        @Override
        public TradeBill tradeBill(String subMchid, java.time.LocalDate billDate) {
            throw new AssertionError("no bills in these tests");
        }

        PaymentQuery paid(Store.Payment payment) {
            return state(payment, PaymentState.SUCCESS);
        }

        PaymentQuery state(Store.Payment payment, PaymentState state) {
            boolean success = state == PaymentState.SUCCESS;
            return new PaymentQuery(state, state.name(), SP_MCHID, payment.subMchid, payment.outTradeNo,
                    success ? "4200" + payment.outTradeNo : null, payment.amountMinor, payment.amountMinor, "CNY",
                    success ? clock.instant() : null);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * 订单、行、支付单、退款单、状态变化共用一份数据。只实现这些流程用到的语句，其余的调用会让测试失败。
     * 约束按 V001–V005 写：来源和业务单一一对应、业务来源的订单不发货、只有 SOURCE 退款可以没有操作人。
     */
    private static final class Store {

        private static final Set<String> CAUSES = Set.of("SUBMIT", "PAYMENT_NOTIFIED", "PAYMENT_QUERIED",
                "BUYER_CANCEL", "TIMEOUT", "SHIPMENT", "PICKUP", "RECEIPT", "AUTO_RECEIPT", "REFUND_NOTIFIED",
                "REFUND_QUERIED", "MERCHANT_REFUND", "REFUND_REJECTED", "SOURCE_REFUND");

        final Map<UUID, TradeOrderMapper.MerchantAccountRow> accounts = new HashMap<>();
        final Map<UUID, String> organizations = new HashMap<>();
        final List<Order> orders = new ArrayList<>();
        final List<LineData> lines = new ArrayList<>();
        final List<Payment> payments = new ArrayList<>();
        final List<Refund> refunds = new ArrayList<>();
        final List<Transition> transitions = new ArrayList<>();
        final OrderMapper orderMapper = new OrderMapper();
        final PaymentMapper paymentMapper = new PaymentMapper();
        final RefundMapper refundMapper = new RefundMapper();
        final SourceMapper sourceMapper = new SourceMapper();
        private UUID mallOrder;
        private int launched;

        Order order(UUID id) {
            return orders.stream().filter(order -> order.id.equals(id)).findFirst().orElseThrow();
        }

        Refund refund(UUID id) {
            return refunds.stream().filter(refund -> refund.id.equals(id)).findFirst().orElseThrow();
        }

        Refund onlyRefund() {
            assertThat(refunds).hasSize(1);
            return refunds.getFirst();
        }

        /** 一张自提的商城订单（只建一次）。 */
        UUID mallOrder() {
            if (mallOrder == null) {
                Order order = new Order();
                order.orderNo = "MO2026111900000099";
                order.buyerId = BUYER;
                order.merchantId = ORGANIZER;
                order.merchantKind = "LOCAL_STORE";
                order.merchantName = "示例商家";
                order.subMchid = SUB_MCHID;
                order.source = "MALL";
                order.deliveryMode = "PICKUP";
                order.payableMinor = 5_000;
                order.payBefore = NOW.plusSeconds(1800);
                order.idempotencyKey = UUID.randomUUID();
                orders.add(order);
                mallOrder = order.id;
            }
            return mallOrder;
        }

        /** 买家发起过一次支付：一张拿到预支付的支付单。 */
        Payment launch(UUID orderId) {
            Order order = order(orderId);
            Payment payment = new Payment();
            payment.orderId = orderId;
            payment.outTradeNo = String.format("P20261119%012d", ++launched);
            payment.subMchid = order.subMchid;
            payment.amountMinor = order.payableMinor;
            payment.status = "PREPAID";
            payments.add(payment);
            return payment;
        }

        /** 订单已经在别处关闭（支付单也关了）。 */
        void closeLocally(UUID orderId, Payment payment) {
            Order order = order(orderId);
            order.status = "CLOSED";
            order.closedAt = NOW;
            order.version++;
            payment.status = "CLOSED";
        }

        static final class Order {
            final UUID id = UUID.randomUUID();
            String orderNo;
            UUID buyerId;
            UUID merchantId;
            String merchantKind;
            String merchantName;
            String subMchid;
            String source;
            UUID sourceRef;
            String status = "PENDING_PAYMENT";
            String deliveryMode;
            long payableMinor;
            long paidMinor;
            long refundedMinor;
            Instant payBefore;
            Instant paidAt;
            Instant closedAt;
            UUID idempotencyKey;
            String requestHash;
            int version;
            final Instant createdAt = NOW;

            TradeOrderMapper.OrderRow toRow() {
                return new TradeOrderMapper.OrderRow(id, orderNo, buyerId, merchantId, merchantKind, merchantName,
                        source, sourceRef, status, deliveryMode, payableMinor, 0, payableMinor, paidMinor, refundedMinor,
                        "CNY", null, payBefore, paidAt, null, null, closedAt, createdAt, version);
            }

            TradePaymentMapper.PaymentOrderRow toPaymentRow() {
                return new TradePaymentMapper.PaymentOrderRow(id, buyerId, status, subMchid, merchantName, payableMinor,
                        payBefore, version);
            }

            TradeRefundMapper.RefundOrderRow toRefundRow() {
                return new TradeRefundMapper.RefundOrderRow(id, buyerId, merchantId, status, subMchid, paidMinor,
                        refundedMinor, paidAt, version);
            }

            TradeSourceMapper.SourceOrderRow toSourceRow() {
                return new TradeSourceMapper.SourceOrderRow(id, orderNo, buyerId, source, sourceRef, status, payBefore,
                        version);
            }
        }

        static final class LineData {
            UUID orderId;
            UUID productId;
            UUID offerId;
            String productName;
            String skuName;
            int quantity;
            long unitAmountMinor;
            long subtotalMinor;
        }

        static final class Payment {
            final UUID id = UUID.randomUUID();
            UUID orderId;
            String outTradeNo;
            String subMchid;
            long amountMinor;
            String status;
            String transactionId;

            TradePaymentMapper.PaymentRow toRow() {
                return new TradePaymentMapper.PaymentRow(id, orderId, outTradeNo, subMchid, amountMinor, status,
                        "wx_prepay", NOW.plusSeconds(6900), transactionId, null);
            }
        }

        static final class Refund {
            final UUID id = UUID.randomUUID();
            UUID orderId;
            UUID paymentId;
            String outRefundNo;
            long amountMinor;
            String reason;
            String initiator;
            UUID requestedBy;
            String status = "REQUESTED";
            String refundId;
            UUID idempotencyKey;

            TradeRefundMapper.RefundRow toRow() {
                return new TradeRefundMapper.RefundRow(id, orderId, paymentId, outRefundNo, amountMinor, reason,
                        initiator, status, refundId, idempotencyKey, NOW, null);
            }
        }

        record Transition(UUID orderId, String fromStatus, String toStatus, String cause, UUID actorId) {
        }

        private Order find(UUID id) {
            return orders.stream().filter(order -> order.id.equals(id)).findFirst().orElse(null);
        }

        private Payment payment(UUID id) {
            return payments.stream().filter(payment -> payment.id.equals(id)).findFirst().orElse(null);
        }

        private boolean hasOpenPayment(UUID orderId) {
            return payments.stream().anyMatch(payment -> payment.orderId.equals(orderId)
                    && ("CREATED".equals(payment.status) || "PREPAID".equals(payment.status)));
        }

        private long sum(UUID orderId, Set<String> statuses) {
            return refunds.stream()
                    .filter(refund -> refund.orderId.equals(orderId) && statuses.contains(refund.status))
                    .mapToLong(refund -> refund.amountMinor)
                    .sum();
        }

        final class OrderMapper implements TradeOrderMapper {
        // ---- S5-7、S5-8（交易迁移 V010）：付款主体、企业订单、注销 ----
        final java.util.Map<java.util.UUID, String[]> payers = new java.util.HashMap<>();

        @Override
        public int updatePayer(java.util.UUID orderId, String payerType, java.util.UUID organizationId, String payerName) {
            payers.put(orderId, new String[] {payerType, organizationId == null ? null : organizationId.toString(), payerName});
            return 1;
        }

        @Override
        public java.util.List<EnterpriseOrderRow> findEnterpriseOrders(java.util.UUID organizationId, int limit, int offset) {
            return java.util.List.of();
        }

        @Override
        public EnterpriseCountRow countEnterpriseOrders(java.util.UUID organizationId) {
            return new EnterpriseCountRow(0, 0, 0, 0);
        }

        @Override
        public long countOpenOrders(java.util.UUID buyerId) {
            return 0;
        }

        @Override
        public int anonymizeBuyerDeliveries(java.util.UUID buyerId) {
            return 0;
        }

            @Override
            public MerchantAccountRow findMerchantAccount(UUID organizationId) {
                return accounts.get(organizationId);
            }

            @Override
            public Integer lockOrderNo(String orderNo) {
                return 1;
            }

            @Override
            public int countOrderNo(String orderNo) {
                return (int) orders.stream().filter(order -> order.orderNo.equals(orderNo)).count();
            }

            @Override
            public int insertLine(UUID orderId, UUID productId, UUID offerId, String productName, String skuName,
                                  String coverUrl, long unitAmountMinor, int quantity, long subtotalMinor,
                                  int sortOrder, Instant at) {
                if (subtotalMinor != unitAmountMinor * quantity || quantity < 1 || quantity > 99) {
                    throw new IllegalStateException("trade_order_line check");
                }
                LineData line = new LineData();
                line.orderId = orderId;
                line.productId = productId;
                line.offerId = offerId;
                line.productName = productName;
                line.skuName = skuName;
                line.quantity = quantity;
                line.unitAmountMinor = unitAmountMinor;
                line.subtotalMinor = subtotalMinor;
                lines.add(line);
                return 1;
            }

            @Override
            public int insertTransition(UUID orderId, String fromStatus, String toStatus, String cause, UUID actorId,
                                        Instant at) {
                if (!CAUSES.contains(cause)) {
                    throw new IllegalStateException("trade_state_transition_cause_check");
                }
                transitions.add(new Transition(orderId, fromStatus, toStatus, cause, actorId));
                return 1;
            }

            @Override
            public int closeUnpaid(UUID orderId, int version, Instant at) {
                Order order = find(orderId);
                if (order == null || !"PENDING_PAYMENT".equals(order.status) || order.version != version) {
                    return 0;
                }
                order.status = "CLOSED";
                order.closedAt = at;
                order.version++;
                return 1;
            }

            @Override
            public List<ExpiredRow> lockExpired(Instant now, int limit) {
                return orders.stream()
                        .filter(order -> "PENDING_PAYMENT".equals(order.status) && !order.payBefore.isAfter(now))
                        .filter(order -> !hasOpenPayment(order.id))
                        .limit(limit)
                        .map(order -> new ExpiredRow(order.id, order.version))
                        .toList();
            }

            @Override
            public int countOpenPayments(UUID orderId) {
                return hasOpenPayment(orderId) ? 1 : 0;
            }

            @Override
            public OrderRow findBuyerOrder(UUID buyerId, UUID orderId) {
                Order order = find(orderId);
                return order == null || !order.buyerId.equals(buyerId) ? null : order.toRow();
            }

            @Override
            public OrderRow lockBuyerOrder(UUID buyerId, UUID orderId) {
                return findBuyerOrder(buyerId, orderId);
            }

            @Override
            public List<LineRow> findLines(List<UUID> orderIds) {
                return lines.stream()
                        .filter(line -> orderIds.contains(line.orderId))
                        .map(line -> new LineRow(UUID.randomUUID(), line.orderId, line.productId, line.offerId,
                                line.productName, line.skuName, null, line.unitAmountMinor, line.quantity,
                                line.subtotalMinor))
                        .toList();
            }

            @Override
            public List<DeliveryRow> findDeliveries(List<UUID> orderIds) {
                return List.of();
            }

            @Override
            public List<RefundRow> findRefunds(List<UUID> orderIds) {
                return refunds.stream()
                        .filter(refund -> orderIds.contains(refund.orderId))
                        .map(refund -> new RefundRow(refund.id, refund.orderId, refund.amountMinor, refund.reason,
                                refund.status, NOW, null))
                        .toList();
            }

            @Override
            public List<CloseCauseRow> findCloseCauses(List<UUID> orderIds) {
                List<CloseCauseRow> causes = new ArrayList<>();
                for (Transition transition : transitions.reversed()) {
                    if (orderIds.contains(transition.orderId()) && "CLOSED".equals(transition.toStatus())
                            && causes.stream().noneMatch(row -> row.orderId().equals(transition.orderId()))) {
                        causes.add(new CloseCauseRow(transition.orderId(), transition.cause()));
                    }
                }
                return causes;
            }

            // ---- 这些流程用不到 ----

            @Override
            public Integer lockBuyer(UUID buyerId) {
                throw unused();
            }

            @Override
            public KeyRow findByKey(UUID buyerId, UUID idempotencyKey) {
                throw unused();
            }

            @Override
            public int countOrganization(UUID organizationId) {
                throw unused();
            }

            @Override
            public MerchantAccountRow saveMerchantAccount(UUID organizationId, String subMchid, boolean active,
                                                          Instant at) {
                throw unused();
            }

            @Override
            public UUID insertOrder(String orderNo, UUID buyerId, UUID merchantId, String merchantKind,
                                    String merchantName, String subMchid, String cityCode, String deliveryMode,
                                    long goodsMinor, long shippingMinor, long payableMinor, String buyerNote,
                                    Instant payBefore, UUID idempotencyKey, String requestHash, Instant at) {
                throw unused();
            }

            @Override
            public int insertDelivery(UUID orderId, String mode, String recipientName, String phone,
                                      String provinceName, String cityName, String addressDetail, String pickupPoint,
                                      Instant at) {
                throw unused();
            }

            @Override
            public OrderRow findOrder(UUID orderId) {
                throw unused();
            }

            @Override
            public long countBuyerOrders(UUID buyerId, List<String> statuses) {
                throw unused();
            }

            @Override
            public List<OrderRow> findBuyerOrders(UUID buyerId, List<String> statuses, int limit, int offset) {
                throw unused();
            }

            @Override
            public OrderRow findMerchantOrder(UUID merchantId, UUID orderId) {
                throw unused();
            }

            @Override
            public OrderRow lockMerchantOrder(UUID merchantId, UUID orderId) {
                throw unused();
            }

            @Override
            public long countMerchantOrders(UUID merchantId, String status, String deliveryMode) {
                throw unused();
            }

            @Override
            public List<OrderRow> findMerchantOrders(UUID merchantId, String status, String deliveryMode, int limit,
                                                   int offset) {
                throw unused();
            }

            @Override
            public int markShipped(UUID orderId, int version, Instant at) {
                throw unused();
            }

            @Override
            public int saveTracking(UUID orderId, String carrierName, String trackingNo, Instant at) {
                throw unused();
            }

            @Override
            public int markCompleted(UUID orderId, String fromStatus, int version, Instant at) {
                throw unused();
            }

            @Override
            public PickupRow lockPickup(UUID orderId) {
                throw unused();
            }

            @Override
            public int recordPickupFailure(UUID orderId, Instant at) {
                throw unused();
            }

            @Override
            public List<ExpiredRow> lockReceiptDue(Instant shippedBefore, int limit) {
                throw unused();
            }
        }

        final class PaymentMapper implements TradePaymentMapper {
            @Override
            public PaymentOrderRow lockBuyerOrder(UUID buyerId, UUID orderId) {
                Order order = find(orderId);
                return order == null || !order.buyerId.equals(buyerId) ? null : order.toPaymentRow();
            }

            @Override
            public PaymentOrderRow findBuyerOrder(UUID buyerId, UUID orderId) {
                return lockBuyerOrder(buyerId, orderId);
            }

            @Override
            public PaymentOrderRow lockOrder(UUID orderId) {
                Order order = find(orderId);
                return order == null ? null : order.toPaymentRow();
            }

            @Override
            public int markOrderPaid(UUID orderId, Instant paidAt, Instant at) {
                Order order = find(orderId);
                if (!"PENDING_PAYMENT".equals(order.status) && !"CLOSED".equals(order.status)) {
                    return 0;
                }
                order.status = "PAID";
                order.paidMinor = order.payableMinor;
                order.paidAt = paidAt;
                order.closedAt = null;
                order.version++;
                return 1;
            }

            @Override
            public int assignPickupCode(UUID orderId, String pickupCode, Instant at) {
                // 只有配送行是自提的商城订单才有取货码；业务来源的订单没有配送行，不改任何行。
                return 0;
            }

            @Override
            public PaymentRow findOpenPayment(UUID orderId) {
                return payments.stream()
                        .filter(payment -> payment.orderId.equals(orderId))
                        .filter(payment -> "CREATED".equals(payment.status) || "PREPAID".equals(payment.status))
                        .map(Payment::toRow)
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public PaymentRow lockPayment(UUID paymentId) {
                Payment payment = payment(paymentId);
                return payment == null ? null : payment.toRow();
            }

            @Override
            public PaymentRow findPaymentByOutTradeNo(String outTradeNo) {
                return payments.stream()
                        .filter(payment -> payment.outTradeNo.equals(outTradeNo))
                        .map(Payment::toRow)
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public int markSucceeded(UUID paymentId, String transactionId, Instant succeededAt, Instant at) {
                Payment payment = payment(paymentId);
                payment.status = "SUCCEEDED";
                payment.transactionId = transactionId;
                return 1;
            }

            @Override
            public int markMismatch(UUID paymentId, Instant at) {
                payment(paymentId).status = "MISMATCH";
                return 1;
            }

            @Override
            public int closePayment(UUID paymentId, Instant at) {
                Payment payment = payment(paymentId);
                if (!"CREATED".equals(payment.status) && !"PREPAID".equals(payment.status)) {
                    return 0;
                }
                payment.status = "CLOSED";
                return 1;
            }

            @Override
            public List<PaymentRow> findExpiredWithOpenPayment(Instant now, int limit) {
                return payments.stream()
                        .filter(payment -> "CREATED".equals(payment.status) || "PREPAID".equals(payment.status))
                        .filter(payment -> "PENDING_PAYMENT".equals(find(payment.orderId).status)
                                && !find(payment.orderId).payBefore.isAfter(now))
                        .limit(limit)
                        .map(Payment::toRow)
                        .toList();
            }

            @Override
            public int touchPayment(UUID paymentId, Instant at) {
                return 1;
            }

            // ---- 这些流程用不到 ----

            @Override
            public PaymentRow lockOpenPayment(UUID orderId) {
                throw unused();
            }

            @Override
            public PaymentRow findPaymentByKey(UUID orderId, UUID idempotencyKey) {
                throw unused();
            }

            @Override
            public int countOutTradeNo(String outTradeNo) {
                throw unused();
            }

            @Override
            public UUID insertPayment(UUID orderId, String outTradeNo, String subMchid, long amountMinor,
                                      UUID idempotencyKey, Instant at) {
                throw unused();
            }

            @Override
            public int markPrepaid(UUID paymentId, String prepayId, Instant expiresAt, Instant at) {
                throw unused();
            }

            @Override
            public List<PaymentRow> findPaymentsToPoll(Instant createdBefore, Instant now, int limit) {
                throw unused();
            }

            @Override
            public UUID insertNotification(String notificationId, String eventType, String outTradeNo,
                                           String outRefundNo, Instant at) {
                throw unused();
            }

            @Override
            public NotificationRow findNotification(String notificationId) {
                throw unused();
            }

            @Override
            public int finishNotification(UUID id, String outcome, String detail) {
                throw unused();
            }
        }

        final class RefundMapper implements TradeRefundMapper {
            @Override
            public RefundOrderRow lockOrder(UUID orderId) {
                Order order = find(orderId);
                return order == null ? null : order.toRefundRow();
            }

            @Override
            public int markOrderRefunding(UUID orderId, Instant at) {
                Order order = find(orderId);
                if (!Set.of("PAID", "SHIPPED", "COMPLETED", "REFUND_FAILED").contains(order.status)) {
                    return 0;
                }
                order.status = "REFUNDING";
                order.version++;
                return 1;
            }

            @Override
            public PaidPaymentRow findSucceededPayment(UUID orderId) {
                return payments.stream()
                        .filter(payment -> payment.orderId.equals(orderId) && "SUCCEEDED".equals(payment.status))
                        .map(payment -> new PaidPaymentRow(payment.id, payment.outTradeNo, payment.subMchid,
                                payment.amountMinor))
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public long sumOpenRefunds(UUID orderId) {
                return sum(orderId, Set.of("REQUESTED", "PROCESSING"));
            }

            @Override
            public long sumReservedRefunds(UUID orderId) {
                return sum(orderId, Set.of("REQUESTED", "PROCESSING", "ABNORMAL"));
            }

            @Override
            public int countRefunds(UUID orderId) {
                return (int) refunds.stream().filter(refund -> refund.orderId.equals(orderId)).count();
            }

            @Override
            public RefundRow findRefundByKey(UUID orderId, UUID idempotencyKey) {
                return refunds.stream()
                        .filter(refund -> refund.orderId.equals(orderId) && refund.idempotencyKey.equals(idempotencyKey))
                        .map(Refund::toRow)
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public RefundRow lockRefund(UUID refundId) {
                return refunds.stream().filter(refund -> refund.id.equals(refundId)).map(Refund::toRow)
                        .findFirst().orElse(null);
            }

            @Override
            public int countOutRefundNo(String outRefundNo) {
                return (int) refunds.stream().filter(refund -> refund.outRefundNo.equals(outRefundNo)).count();
            }

            @Override
            public UUID insertRefund(UUID orderId, UUID paymentId, String outRefundNo, long amountMinor, String reason,
                                     String initiator, UUID requestedBy, UUID idempotencyKey, Instant at) {
                if (!Set.of("BUYER_CANCEL", "MERCHANT", "SOURCE").contains(initiator)) {
                    throw new IllegalStateException("trade_refund_initiator_check");
                }
                if (requestedBy == null && !"SOURCE".equals(initiator)) {
                    throw new IllegalStateException("ck_trade_refund_requested_by");
                }
                if (findRefundByKey(orderId, idempotencyKey) != null) {
                    throw new IllegalStateException("UNIQUE (order_id, idempotency_key)");
                }
                Refund refund = new Refund();
                refund.orderId = orderId;
                refund.paymentId = paymentId;
                refund.outRefundNo = outRefundNo;
                refund.amountMinor = amountMinor;
                refund.reason = reason;
                refund.initiator = initiator;
                refund.requestedBy = requestedBy;
                refund.idempotencyKey = idempotencyKey;
                refunds.add(refund);
                return refund.id;
            }

            @Override
            public int markProcessing(UUID id, String refundId, Instant acceptedAt, Instant at) {
                Refund refund = refund(id);
                if (!"REQUESTED".equals(refund.status)) {
                    return 0;
                }
                refund.status = "PROCESSING";
                refund.refundId = refundId;
                return 1;
            }

            @Override
            public int touch(UUID id, Instant at) {
                return 1;
            }

            // ---- 这些流程用不到 ----

            @Override
            public RefundOrderRow lockBuyerOrder(UUID buyerId, UUID orderId) {
                throw unused();
            }

            @Override
            public RefundOrderRow lockMerchantOrder(UUID merchantId, UUID orderId) {
                throw unused();
            }

            @Override
            public int addRefunded(UUID orderId, long amountMinor, String status, Instant at) {
                throw unused();
            }

            @Override
            public int changeOrderStatus(UUID orderId, String fromStatus, String status, Instant at) {
                throw unused();
            }

            @Override
            public RefundRow findRefundByOutRefundNo(String outRefundNo) {
                throw unused();
            }

            @Override
            public int markSucceeded(UUID id, String refundId, Instant succeededAt, Instant at) {
                throw unused();
            }

            @Override
            public int markFinished(UUID id, String status, Instant at) {
                throw unused();
            }

            @Override
            public List<RefundRow> findRefundsToSubmit(Instant before, int limit) {
                throw unused();
            }

            @Override
            public List<RefundRow> findRefundsToQuery(Instant processingBefore, Instant abnormalBefore, int limit) {
                throw unused();
            }
        }

        final class SourceMapper implements TradeSourceMapper {
            @Override
            public String findOrganizationName(UUID organizationId) {
                return organizations.get(organizationId);
            }

            @Override
            public SourceOrderRow findBySourceRef(String source, UUID sourceRef) {
                return orders.stream()
                        .filter(order -> source.equals(order.source) && sourceRef.equals(order.sourceRef))
                        .map(Order::toSourceRow)
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public SourceOrderRow findSourceOrder(UUID orderId) {
                Order order = find(orderId);
                return order == null ? null : order.toSourceRow();
            }

            @Override
            public SourceOrderRow lockSourceOrder(UUID orderId) {
                Order order = find(orderId);
                return order == null || "MALL".equals(order.source) ? null : order.toSourceRow();
            }

            @Override
            public UUID insertSourceOrder(String orderNo, UUID buyerId, UUID merchantId, String merchantName,
                                          String subMchid, String source, UUID sourceRef, String cityCode,
                                          long goodsMinor, Instant payBefore, String requestHash, Instant at) {
                if (!Set.of("ACTIVITY", "VENUE", "RENTAL").contains(source) || sourceRef == null) {
                    throw new IllegalStateException("trade_order_source_check / ck_trade_order_source_ref");
                }
                if (findBySourceRef(source, sourceRef) != null) {
                    throw new IllegalStateException("uq_trade_order_source_ref");
                }
                if (goodsMinor <= 0 || requestHash.length() != 64 || countOrderNo(orderNo) > 0) {
                    throw new IllegalStateException("trade_order constraint");
                }
                Order order = new Order();
                order.orderNo = orderNo;
                order.buyerId = buyerId;
                order.merchantId = merchantId;
                order.merchantName = merchantName;
                order.subMchid = subMchid;
                order.source = source;
                order.sourceRef = sourceRef;
                order.deliveryMode = "NONE";
                order.payableMinor = goodsMinor;
                order.payBefore = payBefore;
                order.idempotencyKey = sourceRef;
                order.requestHash = requestHash;
                orders.add(order);
                return order.id;
            }

            @Override
            public List<SourceRefundRow> findRefunds(List<UUID> refundIds) {
                if (refundIds.isEmpty()) {
                    throw new IllegalStateException("IN () is not valid SQL");
                }
                return refunds.stream()
                        .filter(refund -> refundIds.contains(refund.id))
                        .map(refund -> new SourceRefundRow(refund.id, refund.orderId, refund.amountMinor, refund.status))
                        .toList();
            }

            private int countOrderNo(String orderNo) {
                return (int) orders.stream().filter(order -> Objects.equals(order.orderNo, orderNo)).count();
            }
        }

        private static AssertionError unused() {
            return new AssertionError("not used by these flows");
        }
    }
}
