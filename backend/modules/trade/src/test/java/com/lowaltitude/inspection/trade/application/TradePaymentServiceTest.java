package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.lowaltitude.inspection.trade.application.PaymentChannel.ChannelNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.OtherNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentState;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PrepayRequest;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundState;
import com.lowaltitude.inspection.trade.application.PaymentChannelException.Kind;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradePaymentService.Applied;
import com.lowaltitude.inspection.trade.application.TradePaymentService.NotificationResult;
import com.lowaltitude.inspection.trade.application.TradeViews.PaymentLaunchView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.application.TradeViews.WechatAppCallView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradePaymentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 支付的规则测试（E04；E05 加了自提单的取货码，以及把退款回调、已付款订单的取消交给退款服务——
 * 退款服务在这里换成只记调用的替身，退款规则见 TradeRefundServiceTest）。
 * 两个 mapper 换成共用一份数据的内存实现（按 TradePaymentMapper / TradeOrderMapper 的 SQL 条件
 * 和 V001 的约束写，违反约束时抛异常），渠道换成可以逐次编排结果的假渠道，事务用 withoutTransaction，时钟可拨动。
 * 取消走真的 TradeOrderService。SQL 文本本身由 TradePaymentMapperContractTest 检查。
 *
 * <p>内存实现没有回滚：需要“事务中途失败”的用例，故障都放在第一处写之前。
 */
class TradePaymentServiceTest {

    /** 北京时间 2026-11-19 00:30：支付单号的日期按北京时间。 */
    private static final Instant NOW = Instant.parse("2026-11-18T16:30:00Z");
    private static final Instant PAID_AT = NOW.plusSeconds(90);
    private static final UUID BUYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_BUYER = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID KEY = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID KEY_2 = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final String SP_MCHID = "1900000001";
    private static final String SUB_MCHID = "1900000109";
    private static final String MERCHANT_NAME = "示例无人机专卖店";
    private static final long PAYABLE = 141_000;
    private static final Duration PREPAY_LIFETIME = Duration.ofMinutes(115);

    private final Store store = new Store();
    private final FakeChannel channel = new FakeChannel();
    private final MutableClock clock = new MutableClock(NOW);
    private final Deque<String> suffixes = new ArrayDeque<>();
    private PaymentChannel configured = channel;
    private int channelLookups;
    private long suffixCounter;
    private final Deque<String> pickupCodes = new ArrayDeque<>();
    private int pickupCodesDrawn;
    private final RecordingRefunds refunds = new RecordingRefunds();
    private final TradeOrderService orderService =
            new TradeOrderService(store.orderMapper, null, clock, () -> "00000001");
    private final TradePaymentService service = new TradePaymentService(
            store.paymentMapper,
            store.orderMapper,
            orderService,
            () -> {
                channelLookups++;
                return configured;
            },
            TransactionOperations.withoutTransaction(),
            clock,
            this::nextSuffix,
            () -> {
                pickupCodesDrawn++;
                return pickupCodes.isEmpty() ? "000000" : pickupCodes.poll();
            },
            refunds);

    // 预支付本地记 2 小时减 5 分钟；建了 1 分钟以上的支付单开始查单；每轮查 50 张、关 20 张。
    @Test
    void theTimingsAreTheAgreedOnes() {
        assertThat(TradePaymentService.PREPAY_LIFETIME).isEqualTo(PREPAY_LIFETIME);
        assertThat(TradePaymentService.POLL_AFTER).isEqualTo(Duration.ofMinutes(1));
        assertThat(TradePaymentService.POLL_BATCH).isEqualTo(50);
        assertThat(TradePaymentService.EXPIRE_BATCH).isEqualTo(20);
    }

    // ---- 发起支付 ----

    @Test
    void theFirstLaunchCreatesAPaymentGetsAPrepayAndSignsTheAppCall() {
        UUID orderId = store.pendingOrder(BUYER, NOW.minusSeconds(300));
        suffixes.add("000000000042");

        PaymentLaunchView launched = service.launch(BUYER, orderId, KEY, "WECHAT_APP");

        Payment payment = store.onlyPayment();
        assertThat(payment.outTradeNo).isEqualTo("P20261119000000000042").hasSize(21);
        assertThat(payment.status).isEqualTo("PREPAID");
        assertThat(payment.subMchid).isEqualTo(SUB_MCHID);
        assertThat(payment.amountMinor).isEqualTo(PAYABLE);
        assertThat(payment.idempotencyKey).isEqualTo(KEY);
        assertThat(payment.createdAt).isEqualTo(NOW);
        assertThat(payment.prepayId).isEqualTo("wx_prepay_1");
        // 微信的预支付 2 小时有效，本地记早 5 分钟。
        assertThat(payment.prepayExpiresAt).isEqualTo(NOW.plus(PREPAY_LIFETIME));
        // 下单带订单快照上的子商户号、金额和付款截止时间。
        assertThat(channel.prepays).containsExactly(new PrepayRequest(
                SUB_MCHID, "P20261119000000000042", MERCHANT_NAME + " 示例无人机 等3件", PAYABLE,
                store.order(orderId).payBefore));
        // 返回的有效期取预支付和订单截止时间里早的那个；调起参数每次现签。
        assertThat(launched).isEqualTo(new PaymentLaunchView(payment.id, "WECHAT_APP", store.order(orderId).payBefore,
                new WechatAppCallView("wx_app", SP_MCHID, "wx_prepay_1", "Sign=WXPay", "nonce-1", "1763483400",
                        "sign-wx_prepay_1")));
        assertThat(channel.calls).containsExactly("prepay:P20261119000000000042", "sign:wx_prepay_1");
        // 订单不因发起支付而变化。
        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");
        assertThat(store.order(orderId).version).isZero();
        assertThat(store.transitions).isEmpty();
        // 第一个事务先锁买家的订单再锁进行中的支付单；记预支付的事务先锁订单再锁支付单。
        assertThat(store.locks).containsExactly(
                "lockBuyerOrder:" + orderId,
                "lockOpenPayment:" + orderId,
                "lockOrder:" + orderId,
                "lockPayment:" + payment.id,
                "lockPayment:" + payment.id);
    }

    @Test
    void theOutTradeNoUsesTheBeijingDateAndIsPickedAgainWhenTaken() {
        clock.now = Instant.parse("2026-11-18T15:59:59Z");
        UUID before = store.pendingOrder(BUYER, clock.now);
        suffixes.addAll(List.of("000000000001", "000000000002"));
        store.takenOutTradeNos.add("P20261118000000000001");

        service.launch(BUYER, before, KEY, "WECHAT_APP");

        clock.now = Instant.parse("2026-11-18T16:00:00Z");
        UUID after = store.pendingOrder(BUYER, clock.now);
        suffixes.add("000000000003");
        service.launch(BUYER, after, KEY, "WECHAT_APP");

        assertThat(store.payments).extracting(payment -> payment.outTradeNo)
                .containsExactly("P20261118000000000002", "P20261119000000000003");
        assertThat(store.outTradeNoChecks).containsExactly(
                "P20261118000000000001", "P20261118000000000002", "P20261119000000000003");
    }

    @Test
    void fiveTakenOutTradeNumbersInARowGiveUpBeforeAskingTheChannel() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        for (int i = 1; i <= 5; i++) {
            store.takenOutTradeNos.add("P2026111900000000000" + i);
            suffixes.add("00000000000" + i);
        }

        assertThatThrownBy(() -> service.launch(BUYER, orderId, KEY, "WECHAT_APP"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.outTradeNoChecks).hasSize(5);
        assertThat(store.payments).isEmpty();
        assertThat(channel.calls).isEmpty();
    }

    @Test
    void theDescriptionIsTheMerchantAndTheFirstProductWithTheCountWhenThereIsMoreThanOnePiece() {
        UUID one = store.order(BUYER, "PENDING_PAYMENT", NOW);
        store.line(one, "示例无人机", 1);
        UUID twoOfOne = store.order(BUYER, "PENDING_PAYMENT", NOW);
        store.line(twoOfOne, "备用电池", 2);
        UUID none = store.order(BUYER, "PENDING_PAYMENT", NOW);

        service.launch(BUYER, one, KEY, "WECHAT_APP");
        service.launch(BUYER, twoOfOne, KEY, "WECHAT_APP");
        service.launch(BUYER, none, KEY, "WECHAT_APP");

        assertThat(channel.prepays).extracting(PrepayRequest::description).containsExactly(
                MERCHANT_NAME + " 示例无人机",
                MERCHANT_NAME + " 备用电池 等2件",
                MERCHANT_NAME);
    }

    @Test
    void aSecondLaunchReusesThePrepaidPaymentAndOnlySignsAgain() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        PaymentLaunchView first = service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        clock.now = NOW.plusSeconds(60);

        // 换了幂等键（App 重装、换设备）也沿用：微信那边不会开第二单。
        PaymentLaunchView second = service.launch(BUYER, orderId, KEY_2, "WECHAT_APP");

        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(second.appCall().prepayId()).isEqualTo(first.appCall().prepayId());
        assertThat(second.appCall().nonceStr()).isEqualTo("nonce-2");
        assertThat(store.payments).hasSize(1);
        assertThat(channel.prepays).hasSize(1);
        assertThat(channel.calls).containsExactly(
                "prepay:" + store.onlyPayment().outTradeNo, "sign:wx_prepay_1", "sign:wx_prepay_1");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aFailedPrepayLeavesThePaymentCreatedAndTheRetryUsesTheSameOutTradeNo(Kind kind) {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        channel.prepayAnswers.add(() -> {
            throw new PaymentChannelException(kind, "SYSTEM_ERROR", 500, "wechat pay failed");
        });

        assertRefused(() -> service.launch(BUYER, orderId, KEY, "WECHAT_APP"), Error.CHANNEL_UNAVAILABLE);

        Payment payment = store.onlyPayment();
        assertThat(payment.status).isEqualTo("CREATED");
        assertThat(payment.prepayId).isNull();
        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");

        // 再发起（同一个键或别的键都一样）：同一个商户单号、同样的内容再下一次单。
        clock.now = NOW.plusSeconds(30);
        PaymentLaunchView retried = service.launch(BUYER, orderId, KEY, "WECHAT_APP");

        assertThat(retried.paymentId()).isEqualTo(payment.id);
        assertThat(store.payments).hasSize(1);
        assertThat(channel.prepays).hasSize(2);
        assertThat(channel.prepays.get(1)).isEqualTo(channel.prepays.get(0));
        assertThat(payment.status).isEqualTo("PREPAID");
        assertThat(payment.prepayExpiresAt).isEqualTo(NOW.plusSeconds(30).plus(PREPAY_LIFETIME));
    }

    @Test
    void onlyThePendingOrdersOfTheBuyerCanBePaidBeforeTheirDeadline() {
        UUID theirs = store.pendingOrder(OTHER_BUYER, NOW);
        UUID paid = store.order(BUYER, "PAID", NOW);
        UUID closed = store.order(BUYER, "CLOSED", NOW);
        UUID dueNow = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(30)));
        UUID overdue = store.pendingOrder(BUYER, NOW.minus(Duration.ofHours(1)));

        assertRefused(() -> service.launch(BUYER, theirs, KEY, "WECHAT_APP"), Error.NOT_FOUND);
        assertRefused(() -> service.launch(BUYER, UUID.randomUUID(), KEY, "WECHAT_APP"), Error.NOT_FOUND);
        assertRefused(() -> service.launch(BUYER, paid, KEY, "WECHAT_APP"), Error.ORDER_STATE_INVALID);
        assertRefused(() -> service.launch(BUYER, closed, KEY, "WECHAT_APP"), Error.ORDER_STATE_INVALID);
        // 截止时间那一刻就不能再付。
        assertRefused(() -> service.launch(BUYER, dueNow, KEY, "WECHAT_APP"), Error.ORDER_EXPIRED);
        assertRefused(() -> service.launch(BUYER, overdue, KEY, "WECHAT_APP"), Error.ORDER_EXPIRED);

        assertThat(store.payments).isEmpty();
        assertThat(channel.calls).isEmpty();
        // 一秒都没过的单可以付。
        clock.now = store.order(dueNow).payBefore.minusSeconds(1);
        assertThat(service.launch(BUYER, dueNow, KEY, "WECHAT_APP").expiresAt())
                .isEqualTo(store.order(dueNow).payBefore);
    }

    @Test
    void withoutAChannelLaunchingIsUnavailableAndNothingIsWritten() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        configured = null;

        assertRefused(() -> service.launch(BUYER, orderId, KEY, "WECHAT_APP"), Error.CHANNEL_UNAVAILABLE);

        assertThat(store.payments).isEmpty();
        assertThat(store.locks).isEmpty();
    }

    @Test
    void invalidArgumentsAreRefusedBeforeTheChannelIsLookedUp() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        configured = null;

        for (Runnable call : List.<Runnable>of(
                () -> service.launch(null, orderId, KEY, "WECHAT_APP"),
                () -> service.launch(BUYER, null, KEY, "WECHAT_APP"),
                () -> service.launch(BUYER, orderId, null, "WECHAT_APP"),
                () -> service.launch(BUYER, orderId, KEY, null),
                () -> service.launch(BUYER, orderId, KEY, "WECHAT_JSAPI"),
                () -> service.launch(BUYER, orderId, KEY, "wechat_app"),
                () -> service.check(null, orderId),
                () -> service.check(BUYER, null))) {
            assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(channelLookups).isZero();
        assertThat(store.locks).isEmpty();
    }

    // 一个键建过的支付单已经不在进行中（被关了），说明订单正在被关闭：让买家刷新，不再用这个键开新单。
    @Test
    void aKeyWhosePaymentIsNoLongerOpenIsRefusedAndANewKeyStartsANewPayment() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        Payment first = store.onlyPayment();
        first.status = "CLOSED";
        first.closedAt = NOW;

        assertRefused(() -> service.launch(BUYER, orderId, KEY, "WECHAT_APP"), Error.ORDER_STATE_INVALID);
        assertThat(store.payments).hasSize(1);

        PaymentLaunchView second = service.launch(BUYER, orderId, KEY_2, "WECHAT_APP");

        assertThat(second.paymentId()).isNotEqualTo(first.id);
        assertThat(store.payments).hasSize(2);
        assertThat(store.payment(second.paymentId()).outTradeNo).isNotEqualTo(first.outTradeNo);
        assertThat(channel.prepays).hasSize(2);
    }

    // 下单在事务外：拿预支付的这段时间里买家取消了订单（支付单被关），拿到的预支付不再交给 App。
    @Test
    void anOrderClosedWhileThePrepayWasRequestedIsRefused() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        channel.prepayAnswers.add(() -> {
            channel.queryAnswers.add(no -> channel.result(no, PaymentState.NOT_FOUND));
            assertThat(service.cancel(BUYER, orderId, 0, KEY).status()).isEqualTo(TradeOrderStatus.CLOSED);
            return "wx_prepay_late";
        });

        assertRefused(() -> service.launch(BUYER, orderId, KEY, "WECHAT_APP"), Error.ORDER_STATE_INVALID);

        Payment payment = store.onlyPayment();
        assertThat(payment.status).isEqualTo("CLOSED");
        assertThat(payment.prepayId).isNull();
        assertThat(store.order(orderId).status).isEqualTo("CLOSED");
        assertThat(channel.calls).noneMatch(call -> call.startsWith("sign:"));
        // 微信那边刚建、没交给任何人的单顺手关掉。
        assertThat(channel.closes).containsExactly(SUB_MCHID + "/" + payment.outTradeNo);
    }

    // 拿预支付的这段时间里订单已经不是待付款（被关闭，或由别的支付单付过），而这张支付单还开着：
    // 本地关掉它，微信那边也关，不交出调起参数。
    @ParameterizedTest
    @ValueSource(strings = {"CLOSED", "PAID"})
    void anOrderThatStoppedWaitingForPaymentClosesThePaymentStillOpen(String status) {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        channel.prepayAnswers.add(() -> {
            Order order = store.order(orderId);
            order.status = status;
            if ("CLOSED".equals(status)) {
                order.closedAt = clock.now;
            } else {
                order.paidAt = clock.now;
                order.paidMinor = order.payableMinor;
            }
            order.version++;
            order.check();
            return "wx_prepay_late";
        });

        assertRefused(() -> service.launch(BUYER, orderId, KEY, "WECHAT_APP"), Error.ORDER_STATE_INVALID);

        Payment payment = store.onlyPayment();
        assertThat(payment.status).isEqualTo("CLOSED");
        assertThat(payment.closedAt).isEqualTo(NOW);
        assertThat(payment.prepayId).isNull();
        assertThat(channel.calls).containsExactly("prepay:" + payment.outTradeNo, "close:" + payment.outTradeNo);
        assertThat(store.order(orderId).status).isEqualTo(status);
        // 锁的顺序不变：先订单、后支付单。
        assertThat(store.locks.subList(2, store.locks.size()))
                .containsExactly("lockOrder:" + orderId, "lockPayment:" + payment.id, "lockPayment:" + payment.id);
    }

    // 在微信那边关不掉也一样拒绝（那张预支付到订单的付款截止时间自己失效）。
    @Test
    void aFailedCloseAfterTheRaceStillRefuses() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        channel.prepayAnswers.add(() -> {
            Order order = store.order(orderId);
            order.status = "CLOSED";
            order.closedAt = clock.now;
            order.version++;
            return "wx_prepay_late";
        });
        channel.closeAnswers.add(() -> {
            throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
        });

        assertRefused(() -> service.launch(BUYER, orderId, KEY, "WECHAT_APP"), Error.ORDER_STATE_INVALID);

        assertThat(store.onlyPayment().status).isEqualTo("CLOSED");
        assertThat(channel.calls).noneMatch(call -> call.startsWith("sign:"));
    }

    @Test
    void aPrepayThatHasExpiredIsNotHandedOut() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW.minusSeconds(120));
        payment.prepayExpiresAt = NOW;

        assertRefused(() -> service.launch(BUYER, orderId, KEY, "WECHAT_APP"), Error.ORDER_EXPIRED);
        assertThat(channel.calls).isEmpty();

        // 预支付比订单先到期时，返回的有效期是预支付的。
        payment.prepayExpiresAt = NOW.plusSeconds(600);
        assertThat(service.launch(BUYER, orderId, KEY, "WECHAT_APP").expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(channel.prepays).isEmpty();
    }

    // ---- App 回来后查一次单 ----

    @Test
    void aCheckAppliesAPaidResultAndReturnsTheOrder() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        channel.queryAnswers.add(no -> channel.paid(store.payment(no)));
        clock.now = NOW.plusSeconds(120);

        TradeOrderView order = service.check(BUYER, orderId);

        assertThat(order.status()).isEqualTo(TradeOrderStatus.PAID);
        assertThat(order.paidAt()).isEqualTo(PAID_AT);
        assertThat(order.amounts().paidMinor()).isEqualTo(PAYABLE);
        assertThat(order.version()).isEqualTo(1);
        Payment payment = store.onlyPayment();
        assertThat(payment.status).isEqualTo("SUCCEEDED");
        assertThat(payment.transactionId).isEqualTo(channel.transactionOf(payment.outTradeNo));
        assertThat(payment.succeededAt).isEqualTo(PAID_AT);
        assertThat(store.transitions).containsExactly(new Transition(
                orderId, "PENDING_PAYMENT", "PAID", "PAYMENT_QUERIED", null, NOW.plusSeconds(120)));
        assertThat(channel.queries).containsExactly(SUB_MCHID + "/" + payment.outTradeNo);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentState.class, names = {"NOTPAY", "CLOSED", "NOT_FOUND", "OTHER"})
    void aCheckThatFindsNoPaymentLeavesEverythingAsItWas(PaymentState state) {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        channel.queryAnswers.add(no -> channel.result(no, state));

        TradeOrderView order = service.check(BUYER, orderId);

        assertThat(order.status()).isEqualTo(TradeOrderStatus.PENDING_PAYMENT);
        assertThat(order.version()).isZero();
        assertThat(store.onlyPayment().status).isEqualTo("PREPAID");
        assertThat(store.transitions).isEmpty();
    }

    @Test
    void aCheckWithoutAnOpenPaymentDoesNotAskTheChannel() {
        UUID pending = store.pendingOrder(BUYER, NOW);
        UUID paid = store.order(BUYER, "PAID", NOW);
        store.payment(paid, "SUCCEEDED", NOW);
        UUID closedWithPayment = store.order(BUYER, "CLOSED", NOW);
        store.payment(closedWithPayment, "CLOSED", NOW);
        configured = null;

        assertThat(service.check(BUYER, pending).status()).isEqualTo(TradeOrderStatus.PENDING_PAYMENT);
        assertThat(service.check(BUYER, paid).status()).isEqualTo(TradeOrderStatus.PAID);
        assertThat(service.check(BUYER, closedWithPayment).status()).isEqualTo(TradeOrderStatus.CLOSED);
        assertThat(channelLookups).isZero();
        assertThat(store.locks).isEmpty();
    }

    @Test
    void aCheckOfAnotherBuyersOrderIsNotFound() {
        UUID theirs = store.pendingOrder(OTHER_BUYER, NOW);
        store.payment(theirs, "PREPAID", NOW);

        assertRefused(() -> service.check(BUYER, theirs), Error.NOT_FOUND);
        assertRefused(() -> service.check(BUYER, UUID.randomUUID()), Error.NOT_FOUND);
        assertThat(channel.calls).isEmpty();
    }

    @Test
    void aCheckWhenTheChannelFailsOrIsMissingIsUnavailable() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        channel.queryAnswers.add(no -> {
            throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
        });

        assertRefused(() -> service.check(BUYER, orderId), Error.CHANNEL_UNAVAILABLE);

        configured = null;
        assertRefused(() -> service.check(BUYER, orderId), Error.CHANNEL_UNAVAILABLE);
        assertThat(store.onlyPayment().status).isEqualTo("PREPAID");
        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");
    }

    // App 回来后会连着查几次：同一张订单 2 秒内只真正向渠道查一次，其余的直接返回订单。
    @Test
    void anOrderIsCheckedWithTheChannelAtMostOnceInTwoSeconds() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        UUID otherOrder = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        service.launch(BUYER, otherOrder, KEY, "WECHAT_APP");
        channel.queryDefault = no -> channel.result(no, PaymentState.NOTPAY);

        assertThat(service.check(BUYER, orderId).status()).isEqualTo(TradeOrderStatus.PENDING_PAYMENT);
        assertThat(service.check(BUYER, orderId).status()).isEqualTo(TradeOrderStatus.PENDING_PAYMENT);
        clock.now = NOW.plusMillis(1_999);
        assertThat(service.check(BUYER, orderId).status()).isEqualTo(TradeOrderStatus.PENDING_PAYMENT);
        assertThat(channel.queries).hasSize(1);
        // 别的订单不受影响。
        service.check(BUYER, otherOrder);
        assertThat(channel.queries).hasSize(2);

        // 满 2 秒再查一次；这次付了。
        clock.now = NOW.plusSeconds(2);
        channel.queryAnswers.add(no -> channel.paid(store.payment(no)));
        assertThat(service.check(BUYER, orderId).status()).isEqualTo(TradeOrderStatus.PAID);
        assertThat(channel.queries).hasSize(3);
        // 2 秒内被挡回去的调用也还是先核对订单归属。
        assertRefused(() -> service.check(OTHER_BUYER, orderId), Error.NOT_FOUND);
        assertThat(TradePaymentService.CHECK_INTERVAL).isEqualTo(Duration.ofSeconds(2));
    }

    // 记查单时间的表有上限：超过 10000 张单时先清掉已过期的，还超就整个清空（最多多查几次）。
    @Test
    void theCheckTimesAreBounded() {
        assertThat(TradePaymentService.MAX_TRACKED_CHECKS).isEqualTo(10_000);
        for (int i = 0; i <= TradePaymentService.MAX_TRACKED_CHECKS; i++) {
            assertThat(service.takeCheckSlot(UUID.randomUUID(), NOW)).isTrue();
        }
        assertThat(service.trackedChecks()).isEqualTo(10_001);

        // 都还没过期：整个清空，再记这一张。
        UUID recent = UUID.randomUUID();
        assertThat(service.takeCheckSlot(recent, NOW.plusSeconds(1))).isTrue();
        assertThat(service.trackedChecks()).isEqualTo(1);
        assertThat(service.takeCheckSlot(recent, NOW.plusSeconds(2))).isFalse();

        for (int i = 0; i < TradePaymentService.MAX_TRACKED_CHECKS; i++) {
            service.takeCheckSlot(UUID.randomUUID(), NOW);
        }
        // 过期的清掉，2 秒内的留下。
        assertThat(service.takeCheckSlot(UUID.randomUUID(), NOW.plusMillis(2_500))).isTrue();
        assertThat(service.trackedChecks()).isEqualTo(2);
        assertThat(service.takeCheckSlot(recent, NOW.plusMillis(2_500))).isFalse();
    }

    // ---- 入账 ----

    // 服务商号、子商户号、金额、币种任一对不上：支付单停在 MISMATCH 等人工，订单一点不动。
    @ParameterizedTest
    @ValueSource(strings = {"provider", "no provider", "sub merchant", "no sub merchant", "amount", "no amount",
            "currency"})
    void aResultThatDoesNotMatchThePaymentHoldsThePaymentAndLeavesTheOrderAlone(String wrong) {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        PaymentQuery good = channel.paid(payment);
        PaymentQuery result = switch (wrong) {
            case "provider" -> with(good, "1900000999", good.subMchid(), good.totalMinor(), good.currency());
            case "no provider" -> with(good, null, good.subMchid(), good.totalMinor(), good.currency());
            case "sub merchant" -> with(good, good.spMchid(), "1900000110", good.totalMinor(), good.currency());
            case "no sub merchant" -> with(good, good.spMchid(), null, good.totalMinor(), good.currency());
            case "amount" -> with(good, good.spMchid(), good.subMchid(), PAYABLE - 1, good.currency());
            case "no amount" -> with(good, good.spMchid(), good.subMchid(), null, good.currency());
            case "currency" -> with(good, good.spMchid(), good.subMchid(), good.totalMinor(), "USD");
            default -> throw new IllegalArgumentException(wrong);
        };

        assertThat(service.apply(channel, result, "PAYMENT_NOTIFIED")).isEqualTo(Applied.MISMATCH);

        assertThat(payment.status).isEqualTo("MISMATCH");
        assertThat(payment.transactionId).isNull();
        assertUntouched(orderId);
        // 之后再来对得上的结果也不入账：人工处理前这张支付单不再自动推进。
        assertThat(service.apply(channel, good, "PAYMENT_QUERIED")).isEqualTo(Applied.MISMATCH);
        assertUntouched(orderId);
    }

    @Test
    void aMissingCurrencyIsTakenAsYuanAndARefundStateCountsAsPaid() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        PaymentQuery good = channel.paid(payment);
        // 转入退款的单说明钱到过；没有付款时间时按入账时间记。
        PaymentQuery refunded = new PaymentQuery(PaymentState.REFUND, "REFUND", good.spMchid(), good.subMchid(),
                good.outTradeNo(), good.transactionId(), good.totalMinor(), good.payerTotalMinor(), null, null);
        clock.now = NOW.plusSeconds(500);

        assertThat(service.apply(channel, refunded, "PAYMENT_QUERIED")).isEqualTo(Applied.APPLIED);

        assertThat(store.order(orderId).status).isEqualTo("PAID");
        assertThat(store.order(orderId).paidAt).isEqualTo(NOW.plusSeconds(500));
        assertThat(payment.succeededAt).isEqualTo(NOW.plusSeconds(500));
    }

    // 重复的回调、回调和查单同时到：只入账一次，第二次是 ALREADY。
    @Test
    void theSameTransactionTwiceIsBookedOnce() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        PaymentQuery result = channel.paid(payment);

        assertThat(service.apply(channel, result, "PAYMENT_NOTIFIED")).isEqualTo(Applied.APPLIED);
        assertThat(service.apply(channel, result, "PAYMENT_QUERIED")).isEqualTo(Applied.ALREADY);

        assertThat(store.order(orderId).version).isEqualTo(1);
        assertThat(store.transitions).containsExactly(
                new Transition(orderId, "PENDING_PAYMENT", "PAID", "PAYMENT_NOTIFIED", null, NOW));
        assertThat(payment.version).isEqualTo(1);
    }

    @Test
    void aSucceededPaymentKeepsItsTransactionWhateverArrivesLater() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        PaymentQuery result = channel.paid(payment);
        service.apply(channel, result, "PAYMENT_NOTIFIED");

        PaymentQuery otherTransaction = new PaymentQuery(PaymentState.SUCCESS, "SUCCESS", SP_MCHID, SUB_MCHID,
                payment.outTradeNo, "4200009999202611190000000001", PAYABLE, PAYABLE, "CNY", PAID_AT);
        PaymentQuery wrongAmount = with(result, SP_MCHID, SUB_MCHID, PAYABLE + 1, "CNY");

        assertThat(service.apply(channel, otherTransaction, "PAYMENT_NOTIFIED")).isEqualTo(Applied.MISMATCH);
        assertThat(service.apply(channel, wrongAmount, "PAYMENT_NOTIFIED")).isEqualTo(Applied.MISMATCH);

        // 已成功的支付单不改成 MISMATCH，交易号也不换。
        assertThat(payment.status).isEqualTo("SUCCEEDED");
        assertThat(payment.transactionId).isEqualTo(result.transactionId());
        assertThat(store.order(orderId).status).isEqualTo("PAID");
        assertThat(store.order(orderId).version).isEqualTo(1);
        assertThat(store.transitions).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentState.class, names = {"NOTPAY", "CLOSED", "NOT_FOUND", "OTHER"})
    void aResultThatIsNotAPaymentTouchesNothing(PaymentState state) {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);

        assertThat(service.apply(channel, channel.result(payment.outTradeNo, state), "PAYMENT_QUERIED"))
                .isEqualTo(Applied.NOT_PAID);

        assertThat(store.locks).isEmpty();
        assertThat(payment.status).isEqualTo("PREPAID");
    }

    @Test
    void aPaymentNobodyKnowsIsUnknown() {
        Payment stranger = new Payment();
        stranger.outTradeNo = "P20261119999999999999";
        stranger.subMchid = SUB_MCHID;
        stranger.amountMinor = PAYABLE;

        assertThat(service.apply(channel, channel.paid(stranger), "PAYMENT_NOTIFIED")).isEqualTo(Applied.UNKNOWN);
        assertThat(store.locks).isEmpty();
    }

    // 关单后才到的付款（钱以渠道为准）：已关闭的订单重新打开成已付款，由商家发货或退款。
    @Test
    void aPaymentThatArrivesAfterTheOrderClosedReopensItAsPaid() {
        UUID orderId = store.order(BUYER, "CLOSED", NOW.minus(Duration.ofHours(1)));
        Payment payment = store.payment(orderId, "CLOSED", NOW.minus(Duration.ofHours(1)));
        int version = store.order(orderId).version;

        assertThat(service.apply(channel, channel.paid(payment), "PAYMENT_NOTIFIED")).isEqualTo(Applied.APPLIED);

        Order order = store.order(orderId);
        assertThat(order.status).isEqualTo("PAID");
        assertThat(order.closedAt).isNull();
        assertThat(order.paidAt).isEqualTo(PAID_AT);
        assertThat(order.paidMinor).isEqualTo(PAYABLE);
        assertThat(order.version).isEqualTo(version + 1);
        assertThat(payment.status).isEqualTo("SUCCEEDED");
        assertThat(store.transitions).containsExactly(
                new Transition(orderId, "CLOSED", "PAID", "PAYMENT_NOTIFIED", null, NOW));
    }

    // 同一订单两张支付单都付了：只入账先到的那张，后到的停在 MISMATCH 等退款。
    @Test
    void twoSuccessfulPaymentsForOneOrderAreBookedOnce() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment closedEarlier = store.payment(orderId, "CLOSED", NOW.minusSeconds(600));
        Payment current = store.payment(orderId, "PREPAID", NOW.minusSeconds(60));

        assertThat(service.apply(channel, channel.paid(current), "PAYMENT_NOTIFIED")).isEqualTo(Applied.APPLIED);
        assertThat(service.apply(channel, channel.paid(closedEarlier), "PAYMENT_NOTIFIED")).isEqualTo(Applied.MISMATCH);

        assertThat(current.status).isEqualTo("SUCCEEDED");
        assertThat(closedEarlier.status).isEqualTo("MISMATCH");
        assertThat(closedEarlier.transactionId).isNull();
        assertThat(store.order(orderId).version).isEqualTo(1);
        assertThat(store.transitions).hasSize(1);
    }

    @Test
    void applyingLocksTheOrderBeforeThePayment() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);

        service.apply(channel, channel.paid(payment), "PAYMENT_NOTIFIED");

        assertThat(store.locks).containsExactly("lockOrder:" + orderId, "lockPayment:" + payment.id);
    }

    // ---- 取货码（E05） ----

    // 自提单付款入账时生成 6 位取货码，只生成一次；重复的结果不再生成。
    @Test
    void aPickupOrderGetsItsPickupCodeWhenThePaymentIsBooked() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        pickupCodes.add("042042");

        assertThat(service.apply(channel, channel.paid(payment), "PAYMENT_NOTIFIED")).isEqualTo(Applied.APPLIED);
        assertThat(service.apply(channel, channel.paid(payment), "PAYMENT_QUERIED")).isEqualTo(Applied.ALREADY);

        assertThat(store.order(orderId).pickupCode).isEqualTo("042042");
        assertThat(store.pickupCodeWrites).containsExactly(orderId);
        assertThat(pickupCodesDrawn).isEqualTo(1);
    }

    // 快递单没有取货码（SQL 只改 mode = 'PICKUP' 的行）。
    @Test
    void anExpressOrderGetsNoPickupCode() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        store.order(orderId).deliveryMode = "EXPRESS";
        Payment payment = store.payment(orderId, "PREPAID", NOW);

        assertThat(service.apply(channel, channel.paid(payment), "PAYMENT_NOTIFIED")).isEqualTo(Applied.APPLIED);

        assertThat(store.order(orderId).status).isEqualTo("PAID");
        assertThat(store.order(orderId).pickupCode).isNull();
    }

    // 已经有取货码的单（关单后才到的付款把它重新打开）不换码。
    @Test
    void aPickupCodeIsNeverReplaced() {
        UUID orderId = store.order(BUYER, "CLOSED", NOW.minus(Duration.ofHours(1)));
        store.order(orderId).pickupCode = "111111";
        Payment payment = store.payment(orderId, "CLOSED", NOW.minus(Duration.ofHours(1)));
        pickupCodes.add("222222");

        assertThat(service.apply(channel, channel.paid(payment), "PAYMENT_NOTIFIED")).isEqualTo(Applied.APPLIED);

        assertThat(store.order(orderId).status).isEqualTo("PAID");
        assertThat(store.order(orderId).pickupCode).isEqualTo("111111");
    }

    // R6-5：付款入账时告诉买家一次（是不是自提由 TradeBuyerEvents 判断）；重复的结果、核对不上的结果不说。
    @Test
    void aBookedPaymentIsReportedToTheBuyerOnce() {
        RecordingBuyerNotifier notifier = new RecordingBuyerNotifier();
        TradeBuyerEvents buyerEvents = new TradeBuyerEvents(
                id -> new TradeBuyerEvents.Loaded(new TradeBuyerNotifier.OrderFacts(id, store.order(id).buyerId,
                        "MALL", null, MERCHANT_NAME, "示例无人机", 3), "PICKUP"),
                notifier);
        TradePaymentService telling = new TradePaymentService(store.paymentMapper, store.orderMapper, orderService,
                () -> channel, TransactionOperations.withoutTransaction(), clock, this::nextSuffix, () -> "042042",
                refunds, TradeSourceEvents.none(), buyerEvents);
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        UUID mismatchedOrder = store.pendingOrder(BUYER, NOW);
        Payment mismatched = store.payment(mismatchedOrder, "PREPAID", NOW);

        assertThat(telling.apply(channel, channel.paid(payment), "PAYMENT_NOTIFIED")).isEqualTo(Applied.APPLIED);
        assertThat(telling.apply(channel, channel.paid(payment), "PAYMENT_QUERIED")).isEqualTo(Applied.ALREADY);
        assertThat(telling.apply(channel, with(channel.paid(mismatched), SP_MCHID, SUB_MCHID, 1L, "CNY"),
                "PAYMENT_NOTIFIED")).isEqualTo(Applied.MISMATCH);

        assertThat(notifier.told).containsExactly("readyForPickup " + orderId + " " + NOW);
    }

    // 核对不上的结果不入账，也不生成取货码。
    @Test
    void aMismatchedPaymentGetsNoPickupCode() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);

        assertThat(service.apply(channel, with(channel.paid(payment), SP_MCHID, SUB_MCHID, 1L, "CNY"),
                "PAYMENT_NOTIFIED")).isEqualTo(Applied.MISMATCH);

        assertThat(store.order(orderId).pickupCode).isNull();
        assertThat(store.pickupCodeWrites).isEmpty();
    }

    // ---- 已付款订单的取消（E05） ----

    // 订单服务说要退款：连同页面上的版本和幂等键交给退款服务，返回它给的订单；支付服务自己不找渠道。
    @Test
    void cancellingAPaidOrderIsHandedToTheRefundServiceWithTheKey() {
        UUID orderId = store.order(BUYER, "PAID", NOW);
        refunds.cancelAnswer = (buyer, order) -> orderService.read(buyer, order);

        TradeOrderView cancelled = service.cancel(BUYER, orderId, 1, KEY_2);

        assertThat(cancelled.orderId()).isEqualTo(orderId);
        assertThat(refunds.cancels).containsExactly(List.of(BUYER, orderId, 1, KEY_2));
        assertThat(channelLookups).isZero();
        // 只有订单服务锁过一次买家的订单，然后放手给退款服务。
        assertThat(store.locks).containsExactly("lockBuyerOrder:" + orderId);
        assertThat(store.transitions).isEmpty();
    }

    @Test
    void aRefusalOfTheRefundServicePassesThrough() {
        UUID orderId = store.order(BUYER, "PAID", NOW);
        refunds.cancelAnswer = (buyer, order) -> {
            throw new TradeException(Error.IDEMPOTENCY_CONFLICT);
        };

        assertRefused(() -> service.cancel(BUYER, orderId, 1, KEY), Error.IDEMPOTENCY_CONFLICT);
        assertThat(refunds.cancels).hasSize(1);
        assertThat(channel.calls).isEmpty();
    }

    // ---- 取消 ----

    @Test
    void cancellingAnOrderWithoutAPaymentDoesNotNeedTheChannel() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        configured = null;

        TradeOrderView cancelled = service.cancel(BUYER, orderId, 0, KEY);

        assertThat(cancelled.status()).isEqualTo(TradeOrderStatus.CLOSED);
        assertThat(channelLookups).isZero();
        assertThat(store.transitions).containsExactly(
                new Transition(orderId, "PENDING_PAYMENT", "CLOSED", "BUYER_CANCEL", BUYER, NOW));
    }

    // 没付：先在渠道关单，再关本地支付单，最后关订单。
    @Test
    void anUnpaidPaymentIsClosedAtTheChannelThenLocallyThenTheOrderIsCancelled() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        Payment payment = store.onlyPayment();
        channel.queryAnswers.add(no -> channel.result(no, PaymentState.NOTPAY));
        clock.now = NOW.plusSeconds(200);

        TradeOrderView cancelled = service.cancel(BUYER, orderId, 0, KEY);

        assertThat(cancelled.status()).isEqualTo(TradeOrderStatus.CLOSED);
        assertThat(cancelled.version()).isEqualTo(1);
        assertThat(payment.status).isEqualTo("CLOSED");
        assertThat(payment.closedAt).isEqualTo(NOW.plusSeconds(200));
        assertThat(channel.calls.subList(2, channel.calls.size()))
                .containsExactly("query:" + payment.outTradeNo, "close:" + payment.outTradeNo);
        assertThat(channel.closes).containsExactly(SUB_MCHID + "/" + payment.outTradeNo);
        assertThat(store.transitions).containsExactly(new Transition(
                orderId, "PENDING_PAYMENT", "CLOSED", "BUYER_CANCEL", BUYER, NOW.plusSeconds(200)));
    }

    // 查单发现已经付了：按付款入账，拒绝取消，绝不关单。
    @Test
    void aPaymentMadeJustBeforeCancellingIsBookedAndTheCancelRefused() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        channel.queryAnswers.add(no -> channel.paid(store.payment(no)));

        assertRefused(() -> service.cancel(BUYER, orderId, 0, KEY), Error.ORDER_STATE_INVALID);

        assertThat(store.order(orderId).status).isEqualTo("PAID");
        assertThat(store.onlyPayment().status).isEqualTo("SUCCEEDED");
        assertThat(channel.closes).isEmpty();
        assertThat(store.transitions).extracting(Transition::cause).containsExactly("PAYMENT_QUERIED");
    }

    // 付了但核对不上：也不能关（钱可能要退），支付单等人工；之后这张单没有进行中的支付单了，可以取消。
    @Test
    void aPaymentThatDoesNotMatchAlsoStopsTheCancel() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        channel.queryAnswers.add(no -> with(channel.paid(store.payment(no)), SP_MCHID, SUB_MCHID, 1L, "CNY"));

        assertRefused(() -> service.cancel(BUYER, orderId, 0, KEY), Error.ORDER_STATE_INVALID);

        assertThat(store.onlyPayment().status).isEqualTo("MISMATCH");
        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");
        assertThat(channel.closes).isEmpty();
        assertThat(service.cancel(BUYER, orderId, 0, KEY).status()).isEqualTo(TradeOrderStatus.CLOSED);
    }

    // 关单被拒（多半是查单之后买家刚付了）：再查一次，以第二次的结果为准。
    @ParameterizedTest
    @EnumSource(value = PaymentState.class)
    void aRejectedCloseIsFollowedByAnotherQuery(PaymentState second) {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        Payment payment = store.onlyPayment();
        channel.queryAnswers.add(no -> channel.result(no, PaymentState.NOTPAY));
        channel.closeAnswers.add(() -> {
            throw new PaymentChannelException(Kind.REJECTED, "ORDER_PAID", 400, "order paid");
        });
        channel.queryAnswers.add(no -> second == PaymentState.SUCCESS || second == PaymentState.REFUND
                ? channel.paidAs(store.payment(no), second)
                : channel.result(no, second));

        switch (second) {
            case SUCCESS, REFUND -> {
                assertRefused(() -> service.cancel(BUYER, orderId, 0, KEY), Error.ORDER_STATE_INVALID);
                assertThat(store.order(orderId).status).isEqualTo("PAID");
                assertThat(payment.status).isEqualTo("SUCCEEDED");
            }
            case CLOSED, NOT_FOUND -> {
                assertThat(service.cancel(BUYER, orderId, 0, KEY).status()).isEqualTo(TradeOrderStatus.CLOSED);
                assertThat(payment.status).isEqualTo("CLOSED");
            }
            case NOTPAY, OTHER -> {
                assertRefused(() -> service.cancel(BUYER, orderId, 0, KEY), Error.CHANNEL_UNAVAILABLE);
                assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");
                assertThat(payment.status).isEqualTo("PREPAID");
            }
        }
        assertThat(channel.calls.subList(2, channel.calls.size())).containsExactly(
                "query:" + payment.outTradeNo, "close:" + payment.outTradeNo, "query:" + payment.outTradeNo);
    }

    @Test
    void aSecondQueryThatFailsAfterARejectedCloseKeepsTheOrder() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        channel.queryAnswers.add(no -> channel.result(no, PaymentState.NOTPAY));
        channel.closeAnswers.add(() -> {
            throw new PaymentChannelException(Kind.RETRYABLE, "SYSTEM_ERROR", 500, "busy");
        });
        channel.queryAnswers.add(no -> {
            throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
        });

        assertRefused(() -> service.cancel(BUYER, orderId, 0, KEY), Error.CHANNEL_UNAVAILABLE);

        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");
        assertThat(store.onlyPayment().status).isEqualTo("PREPAID");
    }

    // 渠道没有这一单或已经关了：不用再关单，直接关本地支付单再取消。
    @ParameterizedTest
    @EnumSource(value = PaymentState.class, names = {"CLOSED", "NOT_FOUND"})
    void aPaymentTheChannelDoesNotHaveOpenIsClosedLocallyOnly(PaymentState state) {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        channel.prepayAnswers.add(() -> {
            throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
        });
        assertRefused(() -> service.launch(BUYER, orderId, KEY, "WECHAT_APP"), Error.CHANNEL_UNAVAILABLE);
        channel.queryAnswers.add(no -> channel.result(no, state));

        assertThat(service.cancel(BUYER, orderId, 0, KEY).status()).isEqualTo(TradeOrderStatus.CLOSED);

        assertThat(store.onlyPayment().status).isEqualTo("CLOSED");
        assertThat(channel.closes).isEmpty();
    }

    @Test
    void anUnexpectedChannelStateOrAFailedQueryKeepsTheOrder() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        channel.queryAnswers.add(no -> channel.result(no, PaymentState.OTHER));
        channel.queryAnswers.add(no -> {
            throw new PaymentChannelException(Kind.UNTRUSTED, "SIGNATURE", 200, "bad signature");
        });

        assertRefused(() -> service.cancel(BUYER, orderId, 0, KEY), Error.CHANNEL_UNAVAILABLE);
        assertRefused(() -> service.cancel(BUYER, orderId, 0, KEY), Error.CHANNEL_UNAVAILABLE);

        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");
        assertThat(store.onlyPayment().status).isEqualTo("PREPAID");
        assertThat(channel.closes).isEmpty();
        assertThat(store.transitions).isEmpty();
    }

    @Test
    void withoutAChannelAnOrderWithAPaymentCannotBeCancelled() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        store.payment(orderId, "PREPAID", NOW);
        configured = null;

        assertRefused(() -> service.cancel(BUYER, orderId, 0, KEY), Error.CHANNEL_UNAVAILABLE);
        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");
    }

    // 关单的同时买家又发起了一次支付：新支付单让第二次取消失败，报版本冲突让页面刷新。
    @Test
    void aNewPaymentStartedWhileCancellingIsAVersionConflict() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        service.launch(BUYER, orderId, KEY, "WECHAT_APP");
        channel.queryAnswers.add(no -> channel.result(no, PaymentState.NOTPAY));
        store.afterClosePayment = closed -> store.payment(orderId, "CREATED", clock.now);

        assertRefused(() -> service.cancel(BUYER, orderId, 0, KEY), Error.VERSION_CONFLICT);

        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");
        assertThat(store.payments).extracting(payment -> payment.status).containsExactly("CLOSED", "CREATED");
    }

    @Test
    void refusalsOfTheOrderServiceComeFirstAndPassThrough() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        store.payment(orderId, "PREPAID", NOW);
        UUID shipped = store.order(BUYER, "SHIPPED", NOW);

        assertRefused(() -> service.cancel(BUYER, orderId, 1, KEY), Error.VERSION_CONFLICT);
        assertRefused(() -> service.cancel(OTHER_BUYER, orderId, 0, KEY), Error.NOT_FOUND);
        assertRefused(() -> service.cancel(BUYER, shipped, 1, KEY), Error.ORDER_STATE_INVALID);
        assertThatThrownBy(() -> service.cancel(null, orderId, 0, KEY)).isInstanceOf(IllegalArgumentException.class);

        assertThat(channel.calls).isEmpty();
        assertThat(refunds.cancels).isEmpty();
    }

    // ---- 查单兜底 ----

    @Test
    void pollingAppliesPaymentsOlderThanAMinuteWhoseOrderIsStillPayable() {
        UUID paidOrder = store.pendingOrder(BUYER, NOW.minusSeconds(300));
        Payment twoMinutes = store.payment(paidOrder, "PREPAID", NOW.minusSeconds(120));
        UUID boundaryOrder = store.pendingOrder(BUYER, NOW.minusSeconds(300));
        Payment oneMinute = store.payment(boundaryOrder, "CREATED", NOW.minusSeconds(60));
        UUID youngOrder = store.pendingOrder(BUYER, NOW.minusSeconds(300));
        store.payment(youngOrder, "PREPAID", NOW.minusSeconds(59));
        UUID expiredOrder = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(30)));
        store.payment(expiredOrder, "PREPAID", NOW.minus(Duration.ofMinutes(20)));
        UUID alreadyPaid = store.order(BUYER, "PAID", NOW.minusSeconds(600));
        store.payment(alreadyPaid, "SUCCEEDED", NOW.minusSeconds(600));
        UUID closedPayment = store.pendingOrder(BUYER, NOW.minusSeconds(300));
        store.payment(closedPayment, "CLOSED", NOW.minusSeconds(200));
        channel.queryDefault = no -> no.equals(twoMinutes.outTradeNo)
                ? channel.paid(twoMinutes)
                : channel.result(no, PaymentState.NOTPAY);

        assertThat(service.pollOpenPayments()).isEqualTo(1);

        assertThat(store.pollQueries).containsExactly(List.of(NOW.minusSeconds(60), NOW, 50));
        assertThat(channel.calls).containsExactly("query:" + twoMinutes.outTradeNo, "query:" + oneMinute.outTradeNo);
        assertThat(store.order(paidOrder).status).isEqualTo("PAID");
        assertThat(store.order(boundaryOrder).status).isEqualTo("PENDING_PAYMENT");
        assertThat(store.transitions).extracting(Transition::cause).containsExactly("PAYMENT_QUERIED");
    }

    @Test
    void oneFailedQueryDoesNotStopTheOthers() {
        List<Payment> payments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID orderId = store.pendingOrder(BUYER, NOW.minusSeconds(300));
            payments.add(store.payment(orderId, "PREPAID", NOW.minusSeconds(240 - i)));
        }
        channel.queryDefault = no -> {
            if (no.equals(payments.get(0).outTradeNo)) {
                throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
            }
            if (no.equals(payments.get(1).outTradeNo)) {
                throw new IllegalStateException("bug in the channel adapter");
            }
            if (no.equals(payments.get(2).outTradeNo)) {
                return with(channel.paid(store.payment(no)), SP_MCHID, SUB_MCHID, 1L, "CNY");
            }
            return channel.paid(store.payment(no));
        };

        // 核对不上的不算入账。
        assertThat(service.pollOpenPayments()).isEqualTo(1);

        assertThat(channel.calls).hasSize(4);
        assertThat(payments).extracting(payment -> payment.status)
                .containsExactly("PREPAID", "PREPAID", "MISMATCH", "SUCCEEDED");
    }

    // 查单失败（渠道不通、适配器出错）和查到还没付的都只更新支付单的时间，排到后面；入账的不用。
    @Test
    void pollingTouchesThePaymentsItCouldNotSettle() {
        List<Payment> payments = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID orderId = store.pendingOrder(BUYER, NOW.minusSeconds(300));
            payments.add(store.payment(orderId, "PREPAID", NOW.minusSeconds(240 - i)));
        }
        channel.queryDefault = no -> {
            if (no.equals(payments.get(0).outTradeNo)) {
                throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
            }
            if (no.equals(payments.get(1).outTradeNo)) {
                throw new IllegalStateException("bug in the channel adapter");
            }
            if (no.equals(payments.get(2).outTradeNo)) {
                return channel.result(no, PaymentState.NOTPAY);
            }
            return channel.paid(store.payment(no));
        };

        assertThat(service.pollOpenPayments()).isEqualTo(1);

        assertThat(store.touchedPayments).containsExactly(payments.get(0).id, payments.get(1).id, payments.get(2).id);
        assertThat(payments.subList(0, 3)).allSatisfy(payment -> {
            assertThat(payment.updatedAt).isEqualTo(NOW);
            assertThat(payment.status).isEqualTo("PREPAID");
        });
        assertThat(payments.get(3).status).isEqualTo("SUCCEEDED");
    }

    // 一整批都查不通：它们被排到后面，下一轮先查别的支付单，不会一直卡住。
    @Test
    void paymentsThatCannotBeQueriedDoNotHoldUpTheOthers() {
        List<Payment> unreachable = new ArrayList<>();
        for (int i = 0; i < TradePaymentService.POLL_BATCH; i++) {
            UUID orderId = store.pendingOrder(BUYER, NOW.minusSeconds(600));
            unreachable.add(store.payment(orderId, "PREPAID", NOW.minusSeconds(500 - i)));
        }
        UUID waitingOrder = store.pendingOrder(BUYER, NOW.minusSeconds(300));
        Payment waiting = store.payment(waitingOrder, "PREPAID", NOW.minusSeconds(200));
        channel.queryDefault = no -> {
            if (!no.equals(waiting.outTradeNo)) {
                throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
            }
            return channel.paid(waiting);
        };

        assertThat(service.pollOpenPayments()).isZero();
        assertThat(channel.queries).hasSize(TradePaymentService.POLL_BATCH);

        clock.now = NOW.plusSeconds(1);
        assertThat(service.pollOpenPayments()).isEqualTo(1);
        assertThat(channel.queries.get(TradePaymentService.POLL_BATCH)).endsWith("/" + waiting.outTradeNo);
        assertThat(store.order(waitingOrder).status).isEqualTo("PAID");
    }

    @Test
    void pollingWithoutAChannelDoesNothing() {
        UUID orderId = store.pendingOrder(BUYER, NOW.minusSeconds(300));
        store.payment(orderId, "PREPAID", NOW.minusSeconds(120));
        configured = null;

        assertThat(service.pollOpenPayments()).isZero();
        assertThat(store.pollQueries).isEmpty();
    }

    // ---- 过期订单 ----

    @Test
    void anExpiredOrderWhosePaymentWasNotMadeIsClosedAtTheChannelThenLocally() {
        UUID orderId = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(31)));
        Payment payment = store.payment(orderId, "PREPAID", NOW.minus(Duration.ofMinutes(25)));
        channel.queryAnswers.add(no -> channel.result(no, PaymentState.NOTPAY));

        assertThat(service.closeExpiredWithPayments()).isEqualTo(1);

        assertThat(store.expiredQueries).containsExactly(List.of(NOW, 20));
        assertThat(channel.calls).containsExactly("query:" + payment.outTradeNo, "close:" + payment.outTradeNo);
        assertThat(payment.status).isEqualTo("CLOSED");
        Order order = store.order(orderId);
        assertThat(order.status).isEqualTo("CLOSED");
        assertThat(order.closedAt).isEqualTo(NOW);
        assertThat(order.version).isEqualTo(1);
        assertThat(store.transitions).containsExactly(
                new Transition(orderId, "PENDING_PAYMENT", "CLOSED", "TIMEOUT", null, NOW));
        assertThat(service.closeExpiredWithPayments()).isZero();
    }

    @Test
    void anExpiredOrderPaidAtTheLastMomentIsBookedNotClosed() {
        UUID orderId = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(31)));
        store.payment(orderId, "PREPAID", NOW.minus(Duration.ofMinutes(25)));
        channel.queryAnswers.add(no -> channel.paid(store.payment(no)));

        assertThat(service.closeExpiredWithPayments()).isZero();

        assertThat(store.order(orderId).status).isEqualTo("PAID");
        assertThat(channel.closes).isEmpty();
    }

    // 渠道不通时绝不在不知道付没付的情况下关单：留到下一轮，一张失败也不影响别的单。
    @Test
    void aChannelFailureDefersOnlyThatOrder() {
        UUID first = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(40)));
        Payment failing = store.payment(first, "PREPAID", NOW.minus(Duration.ofMinutes(35)));
        UUID second = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(35)));
        store.payment(second, "CREATED", NOW.minus(Duration.ofMinutes(30)));
        channel.queryDefault = no -> {
            if (no.equals(failing.outTradeNo)) {
                throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
            }
            return channel.result(no, PaymentState.NOT_FOUND);
        };

        assertThat(service.closeExpiredWithPayments()).isEqualTo(1);

        assertThat(store.order(first).status).isEqualTo("PENDING_PAYMENT");
        assertThat(failing.status).isEqualTo("PREPAID");
        assertThat(store.order(second).status).isEqualTo("CLOSED");

        channel.queryDefault = no -> channel.result(no, PaymentState.CLOSED);
        assertThat(service.closeExpiredWithPayments()).isEqualTo(1);
        assertThat(store.order(first).status).isEqualTo("CLOSED");
    }

    @Test
    void aRuntimeFailureOnOneOrderDoesNotStopTheOthers() {
        UUID first = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(40)));
        Payment broken = store.payment(first, "PREPAID", NOW.minus(Duration.ofMinutes(35)));
        UUID second = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(35)));
        store.payment(second, "PREPAID", NOW.minus(Duration.ofMinutes(30)));
        channel.queryDefault = no -> {
            if (no.equals(broken.outTradeNo)) {
                throw new IllegalStateException("bug in the channel adapter");
            }
            return channel.result(no, PaymentState.NOTPAY);
        };

        assertThat(service.closeExpiredWithPayments()).isEqualTo(1);
        assertThat(store.order(first).status).isEqualTo("PENDING_PAYMENT");
        assertThat(store.order(second).status).isEqualTo("CLOSED");
    }

    // 处理失败的（渠道不通、状态不明、适配器出错）只更新支付单的时间，排到后面；关掉的、付了的不用。
    @Test
    void anExpiredOrderThatCouldNotBeSettledIsTouched() {
        UUID unreachableOrder = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(50)));
        Payment unreachable = store.payment(unreachableOrder, "PREPAID", NOW.minus(Duration.ofMinutes(45)));
        UUID payingOrder = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(45)));
        Payment paying = store.payment(payingOrder, "PREPAID", NOW.minus(Duration.ofMinutes(40)));
        UUID brokenOrder = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(40)));
        Payment broken = store.payment(brokenOrder, "PREPAID", NOW.minus(Duration.ofMinutes(35)));
        UUID closedOrder = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(35)));
        Payment closing = store.payment(closedOrder, "PREPAID", NOW.minus(Duration.ofMinutes(30)));
        channel.queryDefault = no -> {
            if (no.equals(unreachable.outTradeNo)) {
                throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
            }
            if (no.equals(paying.outTradeNo)) {
                return channel.result(no, PaymentState.OTHER);
            }
            if (no.equals(broken.outTradeNo)) {
                throw new IllegalStateException("bug in the channel adapter");
            }
            return channel.result(no, PaymentState.CLOSED);
        };

        assertThat(service.closeExpiredWithPayments()).isEqualTo(1);

        assertThat(store.touchedPayments).containsExactly(unreachable.id, paying.id, broken.id);
        for (Payment payment : List.of(unreachable, paying, broken)) {
            assertThat(payment.updatedAt).isEqualTo(NOW);
            assertThat(payment.status).isEqualTo("PREPAID");
            assertThat(store.order(payment.orderId).status).isEqualTo("PENDING_PAYMENT");
        }
        assertThat(store.order(closedOrder).status).isEqualTo("CLOSED");
        assertThat(closing.status).isEqualTo("CLOSED");
    }

    // 一整批（20 张）都查不通：它们被排到后面，下一轮先处理别的过期订单，不会一直卡住。
    @Test
    void expiredOrdersThatCannotBeSettledDoNotHoldUpTheOthers() {
        for (int i = 0; i < TradePaymentService.EXPIRE_BATCH; i++) {
            UUID orderId = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(90)));
            store.payment(orderId, "PREPAID", NOW.minus(Duration.ofMinutes(80)).plusSeconds(i));
        }
        UUID waitingOrder = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(40)));
        Payment waiting = store.payment(waitingOrder, "PREPAID", NOW.minus(Duration.ofMinutes(35)));
        channel.queryDefault = no -> {
            if (!no.equals(waiting.outTradeNo)) {
                throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
            }
            return channel.result(no, PaymentState.CLOSED);
        };

        assertThat(service.closeExpiredWithPayments()).isZero();
        assertThat(channel.queries).hasSize(TradePaymentService.EXPIRE_BATCH);
        assertThat(store.order(waitingOrder).status).isEqualTo("PENDING_PAYMENT");

        clock.now = NOW.plusSeconds(60);
        assertThat(service.closeExpiredWithPayments()).isEqualTo(1);
        assertThat(channel.queries.get(TradePaymentService.EXPIRE_BATCH)).endsWith("/" + waiting.outTradeNo);
        assertThat(store.order(waitingOrder).status).isEqualTo("CLOSED");
    }

    @Test
    void withoutAChannelExpiredOrdersWithPaymentsStayOpen() {
        UUID orderId = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(31)));
        store.payment(orderId, "PREPAID", NOW.minus(Duration.ofMinutes(25)));
        configured = null;

        assertThat(service.closeExpiredWithPayments()).isZero();

        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");
        assertThat(store.onlyPayment().status).isEqualTo("PREPAID");
        assertThat(store.expiredQueries).hasSize(1);
    }

    @Test
    void nothingExpiredNeedsNoChannel() {
        store.pendingOrder(BUYER, NOW);
        UUID expiredWithoutPayment = store.pendingOrder(BUYER, NOW.minus(Duration.ofHours(1)));
        configured = null;

        assertThat(service.closeExpiredWithPayments()).isZero();

        assertThat(channelLookups).isZero();
        // 没有支付单的过期订单归 TradeOrderService.closeExpired 关。
        assertThat(store.order(expiredWithoutPayment).status).isEqualTo("PENDING_PAYMENT");
    }

    // 关本地支付单的同时又开了一张新的：订单不关（closeExpiredOrder 再数一次进行中的支付单）。
    @Test
    void anExpiredOrderThatGotANewPaymentMeanwhileIsNotClosed() {
        UUID orderId = store.pendingOrder(BUYER, NOW.minus(Duration.ofMinutes(31)));
        store.payment(orderId, "PREPAID", NOW.minus(Duration.ofMinutes(25)));
        channel.queryAnswers.add(no -> channel.result(no, PaymentState.NOTPAY));
        store.afterClosePayment = closed -> store.payment(orderId, "CREATED", clock.now);

        assertThat(service.closeExpiredWithPayments()).isZero();

        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");
        assertThat(store.transitions).isEmpty();
    }

    // ---- 回调 ----

    @Test
    void aPaymentNotificationIsRecordedBookedAndAcknowledged() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        Map<String, String> headers = Map.of("Wechatpay-Serial", "PUB_KEY_ID_0001");
        channel.notifications = (h, body) -> new PaymentNotification("n-1", "TRANSACTION.SUCCESS", channel.paid(payment));
        clock.now = NOW.plusSeconds(100);

        assertThat(service.handleNotification(headers, "{\"id\":\"n-1\"}")).isEqualTo(NotificationResult.ACKNOWLEDGED);

        // 原始请求头和请求体原样交给渠道验签。
        assertThat(channel.notificationRequests).containsExactly(List.of(headers, "{\"id\":\"n-1\"}"));
        Notice notice = store.notices.get("n-1");
        assertThat(notice.eventType).isEqualTo("TRANSACTION.SUCCESS");
        assertThat(notice.outTradeNo).isEqualTo(payment.outTradeNo);
        assertThat(notice.outRefundNo).isNull();
        assertThat(notice.receivedAt).isEqualTo(NOW.plusSeconds(100));
        assertThat(notice.outcome).isEqualTo("APPLIED");
        assertThat(notice.detail).isEqualTo("APPLIED");
        assertThat(store.order(orderId).status).isEqualTo("PAID");
        assertThat(store.transitions).containsExactly(new Transition(
                orderId, "PENDING_PAYMENT", "PAID", "PAYMENT_NOTIFIED", null, NOW.plusSeconds(100)));
    }

    @Test
    void theSameNotificationAgainIsAcknowledgedWithoutBeingProcessed() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        channel.notifications = (h, body) -> new PaymentNotification("n-1", "TRANSACTION.SUCCESS", channel.paid(payment));
        service.handleNotification(Map.of(), "{}");
        int locks = store.locks.size();

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);

        assertThat(store.locks).hasSize(locks);
        assertThat(store.notices).hasSize(1);
        assertThat(store.notices.get("n-1").outcome).isEqualTo("APPLIED");
        assertThat(store.transitions).hasSize(1);
    }

    // 上次处理到一半失败（记录还是 RECEIVED）：微信重发时再处理一遍。
    @Test
    void aNotificationLeftReceivedIsProcessedAgain() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        channel.notifications = (h, body) -> new PaymentNotification("n-1", "TRANSACTION.SUCCESS", channel.paid(payment));
        store.failNextLockOrder = new IllegalStateException("connection reset");

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.FAILED);
        assertThat(store.notices.get("n-1").outcome).isEqualTo("RECEIVED");
        assertThat(store.order(orderId).status).isEqualTo("PENDING_PAYMENT");

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);
        assertThat(store.notices).hasSize(1);
        assertThat(store.notices.get("n-1").outcome).isEqualTo("APPLIED");
        assertThat(store.order(orderId).status).isEqualTo("PAID");
    }

    // 入账成功但记结果时失败：回 500 让微信重发，重发时入账是幂等的（ALREADY），订单只改一次。
    @Test
    void aFailureWhileFinishingTheRecordIsRetriedWithoutBookingTwice() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        channel.notifications = (h, body) -> new PaymentNotification("n-1", "TRANSACTION.SUCCESS", channel.paid(payment));
        store.failNextFinish = new IllegalStateException("connection reset");

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.FAILED);
        assertThat(store.order(orderId).status).isEqualTo("PAID");
        assertThat(store.notices.get("n-1").outcome).isEqualTo("RECEIVED");

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);
        assertThat(store.transitions).hasSize(1);
        assertThat(store.order(orderId).version).isEqualTo(1);
        // 第二次处理看到的是“已经入过账”，记录据此写成 IGNORED / ALREADY。
        assertThat(store.notices.get("n-1").outcome).isEqualTo("IGNORED");
        assertThat(store.notices.get("n-1").detail).isEqualTo("ALREADY");
    }

    @Test
    void aMismatchedPaymentNotificationIsRejectedButAcknowledged() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        channel.notifications = (h, body) -> new PaymentNotification("n-1", "TRANSACTION.SUCCESS",
                with(channel.paid(payment), SP_MCHID, SUB_MCHID, 100L, "CNY"));

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);

        assertThat(store.notices.get("n-1").outcome).isEqualTo("REJECTED");
        assertThat(store.notices.get("n-1").detail).isEqualTo("MISMATCH");
        assertThat(payment.status).isEqualTo("MISMATCH");
        assertUntouched(orderId);
    }

    @Test
    void paymentNotificationsThatChangeNothingAreIgnored() {
        UUID orderId = store.pendingOrder(BUYER, NOW);
        Payment payment = store.payment(orderId, "PREPAID", NOW);
        Payment stranger = new Payment();
        stranger.outTradeNo = "P20261119999999999999";
        stranger.subMchid = SUB_MCHID;
        stranger.amountMinor = PAYABLE;
        Deque<ChannelNotification> arriving = new ArrayDeque<>(List.of(
                new PaymentNotification("n-unknown", "TRANSACTION.SUCCESS", channel.paid(stranger)),
                new PaymentNotification("n-first", "TRANSACTION.SUCCESS", channel.paid(payment)),
                new PaymentNotification("n-again", "TRANSACTION.SUCCESS", channel.paid(payment)),
                new PaymentNotification("n-notpay", "TRANSACTION.SUCCESS",
                        channel.result(payment.outTradeNo, PaymentState.NOTPAY))));
        channel.notifications = (h, body) -> arriving.poll();

        for (int i = 0; i < 4; i++) {
            assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);
        }

        assertThat(store.notices.values()).extracting(notice -> notice.notificationId, notice -> notice.outcome,
                        notice -> notice.detail)
                .containsExactly(
                        tuple("n-unknown", "IGNORED", "UNKNOWN"),
                        tuple("n-first", "APPLIED", "APPLIED"),
                        tuple("n-again", "IGNORED", "ALREADY"),
                        tuple("n-notpay", "IGNORED", "NOT_PAID"));
        assertThat(store.transitions).hasSize(1);
    }

    // 退款回调交给退款服务（E05）：APPLIED 记成 APPLIED，MISMATCH 记成 REJECTED，其余 IGNORED；明细是退款服务的结果。
    @ParameterizedTest
    @EnumSource(TradeRefundService.Applied.class)
    void aRefundNotificationIsHandedToTheRefundService(TradeRefundService.Applied applied) {
        UUID orderId = store.order(BUYER, "REFUNDING", NOW);
        Payment payment = store.payment(orderId, "SUCCEEDED", NOW);
        RefundQuery refund = new RefundQuery(RefundState.SUCCESS, "SUCCESS", SUB_MCHID, payment.outTradeNo,
                "R20261119000000000001", "50300000000000000000000000001", PAYABLE, PAYABLE, NOW);
        channel.notifications = (h, body) -> new RefundNotification("n-refund", "REFUND.SUCCESS", refund);
        refunds.applyAnswer = query -> applied;

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);

        assertThat(refunds.applied).containsExactly(refund);
        assertThat(refunds.causes).containsExactly("REFUND_NOTIFIED");
        Notice notice = store.notices.get("n-refund");
        assertThat(notice.eventType).isEqualTo("REFUND.SUCCESS");
        assertThat(notice.outTradeNo).isEqualTo(payment.outTradeNo);
        assertThat(notice.outRefundNo).isEqualTo("R20261119000000000001");
        assertThat(notice.outcome).isEqualTo(switch (applied) {
            case APPLIED -> "APPLIED";
            case MISMATCH -> "REJECTED";
            default -> "IGNORED";
        });
        assertThat(notice.detail).isEqualTo(applied.name());
        // 支付这一侧什么都不动：不锁订单和支付单，不记迁移。
        assertThat(store.locks).isEmpty();
        assertThat(store.transitions).isEmpty();

        // 同一条通知再来：直接回成功，不再交给退款服务。
        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);
        assertThat(refunds.applied).hasSize(1);
    }

    // 退款服务处理失败：回 500 让微信重发，记录留在 RECEIVED，重发时再交给退款服务（它的入账是幂等的）。
    @Test
    void aRefundNotificationThatFailsIsProcessedAgainWhenResent() {
        RefundQuery refund = new RefundQuery(RefundState.CLOSED, "CLOSED", SUB_MCHID, "P20261119000000000001",
                "R20261119000000000001", null, PAYABLE, PAYABLE, null);
        channel.notifications = (h, body) -> new RefundNotification("n-refund", "REFUND.CLOSED", refund);
        refunds.applyAnswer = query -> {
            throw new IllegalStateException("connection reset");
        };

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.FAILED);
        assertThat(store.notices.get("n-refund").outcome).isEqualTo("RECEIVED");

        refunds.applyAnswer = query -> TradeRefundService.Applied.APPLIED;
        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);
        assertThat(store.notices.get("n-refund").outcome).isEqualTo("APPLIED");
        assertThat(refunds.applied).hasSize(2);
    }

    @Test
    void otherEventsAreRecordedAndIgnored() {
        channel.notifications = (h, body) -> new OtherNotification("n-other", "TRANSACTION.SOMETHING_NEW");

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);

        assertThat(store.notices.values()).extracting(notice -> notice.notificationId, notice -> notice.eventType,
                        notice -> notice.outTradeNo, notice -> notice.outRefundNo, notice -> notice.outcome,
                        notice -> notice.detail)
                .containsExactly(tuple("n-other", "TRANSACTION.SOMETHING_NEW", null, null, "IGNORED",
                        "EVENT_NOT_HANDLED"));
        assertThat(store.locks).isEmpty();
        assertThat(refunds.applied).isEmpty();
    }

    @Test
    void anUntrustedNotificationIsNotRecorded() {
        channel.notifications = (h, body) -> {
            throw new PaymentChannelException(Kind.UNTRUSTED, "SIGNATURE", 0, "signature does not verify");
        };

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.UNTRUSTED);
        assertThat(store.notices).isEmpty();
    }

    @Test
    void oversizedIdentifiersAreUntrustedAndTheLimitsThemselvesAreFine() {
        // 长度正好到上限的退款通知照常交给退款服务（它不认识这个单号）。
        refunds.applyAnswer = query -> TradeRefundService.Applied.UNKNOWN;
        String outTradeNo32 = "P" + "1".repeat(31);
        List<ChannelNotification> oversized = List.of(
                new OtherNotification("n".repeat(65), "TRANSACTION.SOMETHING_NEW"),
                new PaymentNotification("n-1", "TRANSACTION.SUCCESS", channel.result("P" + "1".repeat(32),
                        PaymentState.NOTPAY)),
                new RefundNotification("n-2", "REFUND.SUCCESS", new RefundQuery(RefundState.SUCCESS, "SUCCESS",
                        SUB_MCHID, outTradeNo32, "R".repeat(65), null, null, null, null)));
        for (ChannelNotification notification : oversized) {
            channel.notifications = (h, body) -> notification;
            assertThat(service.handleNotification(Map.of(), "{}")).as(notification.notificationId())
                    .isEqualTo(NotificationResult.UNTRUSTED);
        }
        assertThat(store.notices).isEmpty();

        channel.notifications = (h, body) -> new RefundNotification("n".repeat(64), "REFUND.SUCCESS",
                new RefundQuery(RefundState.SUCCESS, "SUCCESS", SUB_MCHID, outTradeNo32, "R".repeat(64), null, null,
                        null, null));
        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);
        // 事件类型列只有 40 个字符：长的截断后照样记下。
        channel.notifications = (h, body) -> new OtherNotification("n-long", "E".repeat(41));
        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);

        assertThat(store.notices.get("n-long").eventType).isEqualTo("E".repeat(40));
        assertThat(store.notices).hasSize(2);
    }

    @Test
    void withoutAChannelANotificationFailsSoWechatSendsItAgain() {
        configured = null;

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.FAILED);
        assertThat(store.notices).isEmpty();
    }

    @Test
    void aFailureWhileRecordingTheNotificationIsAFailure() {
        channel.notifications = (h, body) -> new OtherNotification("n-1", "TRANSACTION.SOMETHING_NEW");
        store.failNextInsertNotification = new IllegalStateException("database is down");

        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.FAILED);
        assertThat(service.handleNotification(Map.of(), "{}")).isEqualTo(NotificationResult.ACKNOWLEDGED);
        assertThat(store.notices.get("n-1").outcome).isEqualTo("IGNORED");
    }

    // ---- 辅助 ----

    private String nextSuffix() {
        return suffixes.isEmpty() ? String.format("%012d", ++suffixCounter) : suffixes.poll();
    }

    private void assertUntouched(UUID orderId) {
        Order order = store.order(orderId);
        assertThat(order.status).isEqualTo("PENDING_PAYMENT");
        assertThat(order.version).isZero();
        assertThat(order.paidAt).isNull();
        assertThat(order.paidMinor).isZero();
        assertThat(store.transitions).isEmpty();
    }

    private static PaymentQuery with(PaymentQuery query, String spMchid, String subMchid, Long total, String currency) {
        return new PaymentQuery(query.state(), query.rawState(), spMchid, subMchid, query.outTradeNo(),
                query.transactionId(), total, total, currency, query.succeededAt());
    }

    private static void assertRefused(Runnable action, Error error) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TradeException.class, refused -> assertThat(refused.error()).isEqualTo(error));
    }

    // ---- 假渠道 ----

    /** 每次下单、查单、关单的结果按顺序编排；没编排的查单直接让测试失败（AssertionError 不会被服务吞掉）。 */
    private static final class FakeChannel implements PaymentChannel {

        final List<String> calls = new ArrayList<>();
        final List<PrepayRequest> prepays = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        final List<String> closes = new ArrayList<>();
        final List<List<Object>> notificationRequests = new ArrayList<>();
        final Deque<Supplier<String>> prepayAnswers = new ArrayDeque<>();
        final Deque<Function<String, PaymentQuery>> queryAnswers = new ArrayDeque<>();
        final Deque<Runnable> closeAnswers = new ArrayDeque<>();
        Function<String, PaymentQuery> queryDefault;
        BiFunction<Map<String, String>, String, ChannelNotification> notifications = (headers, body) -> {
            throw new AssertionError("no notification is expected");
        };
        private int signatures;

        @Override
        public String providerId() {
            return SP_MCHID;
        }

        @Override
        public String createAppPayment(PrepayRequest request) {
            calls.add("prepay:" + request.outTradeNo());
            prepays.add(request);
            Supplier<String> answer = prepayAnswers.poll();
            return answer == null ? "wx_prepay_" + prepays.size() : answer.get();
        }

        @Override
        public AppPayParams appPayParams(String prepayId) {
            calls.add("sign:" + prepayId);
            signatures++;
            return new AppPayParams("wx_app", SP_MCHID, prepayId, "Sign=WXPay", "nonce-" + signatures, "1763483400",
                    "sign-" + prepayId);
        }

        @Override
        public PaymentQuery queryPayment(String subMchid, String outTradeNo) {
            calls.add("query:" + outTradeNo);
            queries.add(subMchid + "/" + outTradeNo);
            Function<String, PaymentQuery> answer = queryAnswers.poll();
            if (answer == null) {
                answer = queryDefault;
            }
            if (answer == null) {
                throw new AssertionError("unexpected query of " + outTradeNo);
            }
            return answer.apply(outTradeNo);
        }

        @Override
        public void closePayment(String subMchid, String outTradeNo) {
            calls.add("close:" + outTradeNo);
            closes.add(subMchid + "/" + outTradeNo);
            Runnable answer = closeAnswers.poll();
            if (answer != null) {
                answer.run();
            }
        }

        @Override
        public RefundAccepted applyRefund(RefundRequest request) {
            throw new AssertionError("refunds are not part of E04");
        }

        @Override
        public RefundQuery queryRefund(String subMchid, String outRefundNo) {
            throw new AssertionError("refunds are not part of E04");
        }

        @Override
        public TradeBill tradeBill(String subMchid, java.time.LocalDate billDate) {
            throw new AssertionError("bills are not part of E04");
        }

        @Override
        public ChannelNotification parseNotification(Map<String, String> headers, String body) {
            notificationRequests.add(List.of(headers, body));
            return notifications.apply(headers, body);
        }

        /** 微信的交易号 28 位；这里由商户单号推出来，每张支付单一个。 */
        String transactionOf(String outTradeNo) {
            return "4200000" + outTradeNo.substring(1);
        }

        /** 一笔和支付单完全对得上的成功付款。 */
        PaymentQuery paid(Payment payment) {
            return paidAs(payment, PaymentState.SUCCESS);
        }

        PaymentQuery paidAs(Payment payment, PaymentState state) {
            return new PaymentQuery(state, state.name(), SP_MCHID, payment.subMchid, payment.outTradeNo,
                    transactionOf(payment.outTradeNo), payment.amountMinor, payment.amountMinor, "CNY", PAID_AT);
        }

        PaymentQuery result(String outTradeNo, PaymentState state) {
            String raw = switch (state) {
                case NOT_FOUND -> "ORDER_NOT_EXIST";
                case OTHER -> "USERPAYING";
                default -> state.name();
            };
            return new PaymentQuery(state, raw, SP_MCHID, SUB_MCHID, outTradeNo, null,
                    state == PaymentState.NOT_FOUND ? null : PAYABLE, null, null, null);
        }
    }

    /**
     * 退款服务的替身：记下支付服务交给它的退款结果和已付款订单的取消，结果由测试给出。
     * 退款本身的规则由 TradeRefundServiceTest 覆盖。
     */
    private final class RecordingRefunds extends TradeRefundService {

        final List<RefundQuery> applied = new ArrayList<>();
        final List<String> causes = new ArrayList<>();
        final List<List<Object>> cancels = new ArrayList<>();
        Function<RefundQuery, TradeRefundService.Applied> applyAnswer = query -> {
            throw new AssertionError("no refund result is expected");
        };
        BiFunction<UUID, UUID, TradeOrderView> cancelAnswer = (buyer, order) -> {
            throw new AssertionError("no refund is expected");
        };

        RecordingRefunds() {
            super(null, null, null, () -> null, TransactionOperations.withoutTransaction(), Clock.systemUTC(),
                    () -> "000000000000");
        }

        @Override
        TradeRefundService.Applied apply(RefundQuery result, String cause) {
            applied.add(result);
            causes.add(cause);
            return applyAnswer.apply(result);
        }

        @Override
        public TradeOrderView cancelPaid(UUID buyerId, UUID orderId, int version, UUID idempotencyKey) {
            cancels.add(List.of(buyerId, orderId, version, idempotencyKey));
            return cancelAnswer.apply(buyerId, orderId);
        }

        @Override
        public int pollRefunds() {
            throw new AssertionError("the payment service does not poll refunds");
        }
    }

    private static final class MutableClock extends Clock {

        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // ---- 内存数据 ----

    private record Transition(UUID orderId, String from, String to, String cause, UUID actor, Instant at) {
    }

    private static final class Order {
        UUID id;
        String orderNo;
        UUID buyerId;
        String status;
        long payableMinor = PAYABLE;
        long paidMinor;
        Instant payBefore;
        Instant paidAt;
        Instant closedAt;
        Instant createdAt;
        int version;
        String deliveryMode = "PICKUP";
        String pickupCode;

        void check() {
            // V001 trade_order 的检查约束。
            if (("PENDING_PAYMENT".equals(status)) != (paidAt == null && closedAt == null)
                    || ("CLOSED".equals(status)) != (closedAt != null)
                    || (paidMinor != 0 && paidMinor != payableMinor)
                    || (!Set.of("PENDING_PAYMENT", "CLOSED").contains(status) && paidAt == null)) {
                throw new IllegalStateException("trade_order check violated by " + this.status);
            }
        }

        TradeOrderMapper.OrderRow toOrderRow() {
            return new TradeOrderMapper.OrderRow(id, orderNo, buyerId, UUID.nameUUIDFromBytes(new byte[] {1}),
                    "LOCAL_STORE", MERCHANT_NAME, "MALL", null, status, deliveryMode, payableMinor, 0, payableMinor, paidMinor,
                    0, "CNY", null, payBefore, paidAt, null, null, closedAt, createdAt, version);
        }

        TradePaymentMapper.PaymentOrderRow toPaymentOrderRow() {
            return new TradePaymentMapper.PaymentOrderRow(id, buyerId, status, SUB_MCHID, MERCHANT_NAME, payableMinor,
                    payBefore, version);
        }
    }

    private static final class Payment {
        UUID id;
        UUID orderId;
        String outTradeNo;
        String subMchid;
        long amountMinor;
        String status;
        String prepayId;
        Instant prepayExpiresAt;
        String transactionId;
        Instant succeededAt;
        Instant closedAt;
        UUID idempotencyKey;
        Instant createdAt;
        Instant updatedAt;
        int version;

        boolean open() {
            return "CREATED".equals(status) || "PREPAID".equals(status);
        }

        TradePaymentMapper.PaymentRow toRow() {
            return new TradePaymentMapper.PaymentRow(id, orderId, outTradeNo, subMchid, amountMinor, status, prepayId,
                    prepayExpiresAt, transactionId, idempotencyKey);
        }
    }

    private static final class Notice {
        UUID id;
        String notificationId;
        String eventType;
        String outTradeNo;
        String outRefundNo;
        String outcome;
        String detail;
        Instant receivedAt;
    }

    /**
     * 订单、支付单、回调记录的一份内存数据，两个 mapper 共用。写入时检查 V001 的约束（唯一索引、检查约束），
     * 锁语句记到 locks 里，好检查加锁顺序。
     */
    private final class Store {

        final Map<UUID, Order> orders = new LinkedHashMap<>();
        final List<TradeOrderMapper.LineRow> lines = new ArrayList<>();
        final List<Payment> payments = new ArrayList<>();
        final Map<String, Notice> notices = new LinkedHashMap<>();
        final List<Transition> transitions = new ArrayList<>();
        final List<String> locks = new ArrayList<>();
        final Set<String> takenOutTradeNos = new HashSet<>();
        final List<String> outTradeNoChecks = new ArrayList<>();
        final List<List<Object>> pollQueries = new ArrayList<>();
        final List<List<Object>> expiredQueries = new ArrayList<>();
        final List<UUID> pickupCodeWrites = new ArrayList<>();
        final List<UUID> touchedPayments = new ArrayList<>();
        java.util.function.Consumer<Payment> afterClosePayment;
        RuntimeException failNextLockOrder;
        RuntimeException failNextInsertNotification;
        RuntimeException failNextFinish;
        private long sequence;

        final TradeOrderMapper orderMapper = new OrderMapper();
        final TradePaymentMapper paymentMapper = new PaymentMapper();

        /** 两件商品（1 + 2 件）的待付款单，30 分钟后到期。 */
        UUID pendingOrder(UUID buyerId, Instant createdAt) {
            UUID id = order(buyerId, "PENDING_PAYMENT", createdAt);
            line(id, "示例无人机", 1);
            line(id, "备用电池", 2);
            return id;
        }

        UUID order(UUID buyerId, String status, Instant createdAt) {
            Order order = new Order();
            order.id = UUID.randomUUID();
            order.orderNo = "MO20261119" + String.format("%08d", ++sequence);
            order.buyerId = buyerId;
            order.status = status;
            order.payBefore = createdAt.plus(Duration.ofMinutes(30));
            order.createdAt = createdAt;
            if ("CLOSED".equals(status)) {
                order.closedAt = createdAt.plus(Duration.ofMinutes(30));
                order.version = 1;
            } else if (!"PENDING_PAYMENT".equals(status)) {
                order.paidAt = createdAt;
                order.paidMinor = order.payableMinor;
                order.version = 1;
            }
            order.check();
            orders.put(order.id, order);
            return order.id;
        }

        void line(UUID orderId, String productName, int quantity) {
            lines.add(new TradeOrderMapper.LineRow(UUID.randomUUID(), orderId, UUID.randomUUID(), UUID.randomUUID(),
                    productName, "标准版", null, 10_000, quantity, 10_000L * quantity));
        }

        /** 直接放一张某个状态的支付单（模拟以前的发起、关单或入账）。 */
        Payment payment(UUID orderId, String status, Instant createdAt) {
            Payment payment = new Payment();
            payment.id = UUID.randomUUID();
            payment.orderId = orderId;
            payment.outTradeNo = "P20261119" + String.format("%012d", 900_000_000_000L + ++sequence);
            payment.subMchid = SUB_MCHID;
            payment.amountMinor = order(orderId).payableMinor;
            payment.status = status;
            payment.idempotencyKey = UUID.randomUUID();
            payment.createdAt = createdAt;
            payment.updatedAt = createdAt;
            if ("PREPAID".equals(status)) {
                payment.prepayId = "wx_prepay_seeded_" + sequence;
                payment.prepayExpiresAt = createdAt.plus(PREPAY_LIFETIME);
            }
            if ("SUCCEEDED".equals(status)) {
                payment.transactionId = "4200000000000000000" + String.format("%09d", sequence);
                payment.succeededAt = createdAt;
            }
            if ("CLOSED".equals(status)) {
                payment.closedAt = createdAt;
            }
            payments.add(payment);
            checkPayments();
            return payment;
        }

        Order order(UUID id) {
            return Objects.requireNonNull(orders.get(id), "order " + id);
        }

        Payment payment(UUID id) {
            return payments.stream().filter(payment -> payment.id.equals(id)).findFirst().orElseThrow();
        }

        Payment payment(String outTradeNo) {
            return payments.stream().filter(payment -> payment.outTradeNo.equals(outTradeNo)).findFirst().orElseThrow();
        }

        Payment onlyPayment() {
            assertThat(payments).hasSize(1);
            return payments.getFirst();
        }

        /** uq_trade_payment_open、uq_trade_payment_succeeded、唯一的交易号和支付单的检查约束。 */
        void checkPayments() {
            for (Payment payment : payments) {
                if (("SUCCEEDED".equals(payment.status)) != (payment.succeededAt != null)
                        || ("SUCCEEDED".equals(payment.status) && payment.transactionId == null)
                        || ("PREPAID".equals(payment.status) && payment.prepayId == null)) {
                    throw new IllegalStateException("trade_payment check violated");
                }
            }
            Map<UUID, Long> open = new LinkedHashMap<>();
            Map<UUID, Long> succeeded = new LinkedHashMap<>();
            Set<String> transactions = new HashSet<>();
            Set<String> outTradeNos = new HashSet<>();
            for (Payment payment : payments) {
                if (payment.open()) {
                    open.merge(payment.orderId, 1L, Long::sum);
                }
                if ("SUCCEEDED".equals(payment.status)) {
                    succeeded.merge(payment.orderId, 1L, Long::sum);
                }
                if (payment.transactionId != null && !transactions.add(payment.transactionId)) {
                    throw new IllegalStateException("duplicate transaction_id");
                }
                if (!outTradeNos.add(payment.outTradeNo)) {
                    throw new IllegalStateException("duplicate out_trade_no");
                }
            }
            if (open.values().stream().anyMatch(count -> count > 1)) {
                throw new IllegalStateException("uq_trade_payment_open violated");
            }
            if (succeeded.values().stream().anyMatch(count -> count > 1)) {
                throw new IllegalStateException("uq_trade_payment_succeeded violated");
            }
        }

        private Payment openPaymentOf(UUID orderId) {
            return payments.stream().filter(payment -> payment.orderId.equals(orderId) && payment.open())
                    .findFirst().orElse(null);
        }

        private TradePaymentMapper.PaymentRow row(Payment payment) {
            return payment == null ? null : payment.toRow();
        }

        private final class OrderMapper implements TradeOrderMapper {
        // ---- S5-7、S5-8（交易迁移 V013）：付款主体、企业订单、注销 ----
        final java.util.Map<java.util.UUID, String[]> payers = new java.util.HashMap<>();

        @Override
        public int updatePayer(java.util.UUID orderId, String payerType, java.util.UUID organizationId, String payerName) {
            payers.put(orderId, new String[] {payerType, organizationId == null ? null : organizationId.toString(), payerName});
            return 1;
        }

        // ---- 0.6.0 新加的读写：这些测试不关心，什么都不做 ----

        @Override
        public int saveParties(UUID orderId, String fulfilledFrom, String invoiceIssuer, String aftersaleParty) {
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
        public int saveParticipantNames(UUID orderId, String participantNames) {
            return 1;
        }

        @Override
        public int markLinesWithoutNoReasonReturn(UUID orderId, java.util.List<UUID> offerIds) {
            return offerIds.size();
        }

        @Override
        public java.util.List<com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.OrderExtrasRow>
                findOrderExtras(java.util.List<UUID> orderIds) {
            return java.util.List.of();
        }


            @Override
            public OrderRow lockBuyerOrder(UUID buyerId, UUID orderId) {
                locks.add("lockBuyerOrder:" + orderId);
                return findBuyerOrder(buyerId, orderId);
            }

            @Override
            public OrderRow findBuyerOrder(UUID buyerId, UUID orderId) {
                Order order = orders.get(orderId);
                return order == null || !order.buyerId.equals(buyerId) ? null : order.toOrderRow();
            }

            @Override
            public int countOpenPayments(UUID orderId) {
                return openPaymentOf(orderId) == null ? 0 : 1;
            }

            @Override
            public int closeUnpaid(UUID orderId, int version, Instant at) {
                Order order = orders.get(orderId);
                if (order == null || !"PENDING_PAYMENT".equals(order.status) || order.version != version) {
                    return 0;
                }
                order.status = "CLOSED";
                order.closedAt = at;
                order.version++;
                order.check();
                return 1;
            }

            @Override
            public int insertTransition(UUID orderId, String fromStatus, String toStatus, String cause, UUID actorId,
                                        Instant at) {
                if (!Set.of("SUBMIT", "PAYMENT_NOTIFIED", "PAYMENT_QUERIED", "BUYER_CANCEL", "TIMEOUT").contains(cause)) {
                    throw new IllegalStateException("trade_state_transition cause check violated: " + cause);
                }
                transitions.add(new Transition(orderId, fromStatus, toStatus, cause, actorId, at));
                return 1;
            }

            @Override
            public List<LineRow> findLines(List<UUID> orderIds) {
                return lines.stream().filter(line -> orderIds.contains(line.orderId())).toList();
            }

            @Override
            public List<DeliveryRow> findDeliveries(List<UUID> orderIds) {
                return List.of();
            }

            @Override
            public List<RefundRow> findRefunds(List<UUID> orderIds) {
                return List.of();
            }

            @Override
            public List<CloseCauseRow> findCloseCauses(List<UUID> orderIds) {
                // 和 SQL 一样：每张单最后一次进入 CLOSED 的原因（迁移记录按写入顺序排）。
                Map<UUID, String> last = new LinkedHashMap<>();
                for (Transition transition : transitions) {
                    if ("CLOSED".equals(transition.to()) && orderIds.contains(transition.orderId())) {
                        last.put(transition.orderId(), transition.cause());
                    }
                }
                return last.entrySet().stream().map(entry -> new CloseCauseRow(entry.getKey(), entry.getValue())).toList();
            }

            // 下面这些支付不用。

            @Override
            public Integer lockBuyer(UUID buyerId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public KeyRow findByKey(UUID buyerId, UUID idempotencyKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MerchantAccountRow findMerchantAccount(UUID organizationId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int countOrganization(UUID organizationId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MerchantAccountRow saveMerchantAccount(UUID organizationId, String subMchid, boolean active,
                                                          Instant at) {
                throw new UnsupportedOperationException();
            }

            @Override
            public UUID insertOrder(String orderNo, UUID buyerId, UUID merchantId, String merchantKind,
                                    String merchantName, String subMchid, String cityCode, String deliveryMode,
                                    long goodsMinor, long shippingMinor, long payableMinor, String buyerNote,
                                    Instant payBefore, UUID idempotencyKey, String requestHash, Instant at) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Integer lockOrderNo(String orderNo) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int countOrderNo(String orderNo) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int insertLine(UUID orderId, UUID productId, UUID offerId, String productName, String skuName,
                                  String coverUrl, long unitAmountMinor, int quantity, long subtotalMinor,
                                  int sortOrder, Instant at) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int insertDelivery(UUID orderId, String mode, String recipientName, String phone,
                                      String provinceName, String cityName, String addressDetail, String pickupPoint,
                                      Instant at) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ExpiredRow> lockExpired(Instant now, int limit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OrderRow findOrder(UUID orderId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long countBuyerOrders(UUID buyerId, List<String> statuses) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<OrderRow> findBuyerOrders(UUID buyerId, List<String> statuses, int limit, int offset) {
                throw new UnsupportedOperationException();
            }

            // 发货、核销、确认收货（E05）由 TradeOrderServiceTest 覆盖，支付不用。

            @Override
            public OrderRow findMerchantOrder(UUID merchantId, UUID orderId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OrderRow lockMerchantOrder(UUID merchantId, UUID orderId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long countMerchantOrders(UUID merchantId, String status, String deliveryMode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<OrderRow> findMerchantOrders(UUID merchantId, String status, String deliveryMode, int limit,
                                                   int offset) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int markShipped(UUID orderId, int version, Instant at) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int saveTracking(UUID orderId, String carrierName, String trackingNo, Instant at) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int markCompleted(UUID orderId, String fromStatus, int version, Instant at) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PickupRow lockPickup(UUID orderId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int recordPickupFailure(UUID orderId, Instant at) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ExpiredRow> lockReceiptDue(Instant shippedBefore, int limit) {
                throw new UnsupportedOperationException();
            }
        }

        private final class PaymentMapper implements TradePaymentMapper {

            @Override
            public PaymentOrderRow lockBuyerOrder(UUID buyerId, UUID orderId) {
                locks.add("lockBuyerOrder:" + orderId);
                return findBuyerOrder(buyerId, orderId);
            }

            @Override
            public PaymentOrderRow findBuyerOrder(UUID buyerId, UUID orderId) {
                Order order = orders.get(orderId);
                return order == null || !order.buyerId.equals(buyerId) ? null : order.toPaymentOrderRow();
            }

            @Override
            public PaymentOrderRow lockOrder(UUID orderId) {
                if (failNextLockOrder != null) {
                    RuntimeException failure = failNextLockOrder;
                    failNextLockOrder = null;
                    throw failure;
                }
                locks.add("lockOrder:" + orderId);
                Order order = orders.get(orderId);
                return order == null ? null : order.toPaymentOrderRow();
            }

            @Override
            public int markOrderPaid(UUID orderId, Instant paidAt, Instant at) {
                Order order = order(orderId);
                if (!"PENDING_PAYMENT".equals(order.status) && !"CLOSED".equals(order.status)) {
                    return 0;
                }
                order.status = "PAID";
                order.paidMinor = order.payableMinor;
                order.paidAt = paidAt;
                order.closedAt = null;
                order.version++;
                order.check();
                return 1;
            }

            /** 和 SQL 一样：只给还没有取货码的自提单写一次。取货码列是 char(6)。 */
            @Override
            public int assignPickupCode(UUID orderId, String pickupCode, Instant at) {
                pickupCodeWrites.add(orderId);
                Order order = order(orderId);
                if (!"PICKUP".equals(order.deliveryMode) || order.pickupCode != null) {
                    return 0;
                }
                if (pickupCode == null || pickupCode.length() != 6) {
                    throw new IllegalStateException("trade_order_delivery pickup_code is char(6)");
                }
                order.pickupCode = pickupCode;
                return 1;
            }

            @Override
            public PaymentRow lockOpenPayment(UUID orderId) {
                locks.add("lockOpenPayment:" + orderId);
                return row(openPaymentOf(orderId));
            }

            @Override
            public PaymentRow findOpenPayment(UUID orderId) {
                return row(openPaymentOf(orderId));
            }

            @Override
            public PaymentRow findPaymentByKey(UUID orderId, UUID idempotencyKey) {
                return row(payments.stream()
                        .filter(payment -> payment.orderId.equals(orderId) && payment.idempotencyKey.equals(idempotencyKey))
                        .findFirst().orElse(null));
            }

            @Override
            public PaymentRow lockPayment(UUID paymentId) {
                locks.add("lockPayment:" + paymentId);
                return row(payments.stream().filter(payment -> payment.id.equals(paymentId)).findFirst().orElse(null));
            }

            @Override
            public PaymentRow findPaymentByOutTradeNo(String outTradeNo) {
                return row(payments.stream().filter(payment -> payment.outTradeNo.equals(outTradeNo))
                        .findFirst().orElse(null));
            }

            @Override
            public int countOutTradeNo(String outTradeNo) {
                outTradeNoChecks.add(outTradeNo);
                boolean taken = takenOutTradeNos.contains(outTradeNo)
                        || payments.stream().anyMatch(payment -> payment.outTradeNo.equals(outTradeNo));
                return taken ? 1 : 0;
            }

            @Override
            public UUID insertPayment(UUID orderId, String outTradeNo, String subMchid, long amountMinor,
                                      UUID idempotencyKey, Instant at) {
                if (!outTradeNo.matches("^[0-9A-Za-z_*-]{6,32}$") || amountMinor <= 0 || subMchid == null) {
                    throw new IllegalStateException("trade_payment check violated");
                }
                if (payments.stream().anyMatch(payment -> payment.orderId.equals(orderId)
                        && payment.idempotencyKey.equals(idempotencyKey))) {
                    throw new IllegalStateException("duplicate (order_id, idempotency_key)");
                }
                Payment payment = new Payment();
                payment.id = UUID.randomUUID();
                payment.orderId = order(orderId).id;
                payment.outTradeNo = outTradeNo;
                payment.subMchid = subMchid;
                payment.amountMinor = amountMinor;
                payment.status = "CREATED";
                payment.idempotencyKey = idempotencyKey;
                payment.createdAt = at;
                payment.updatedAt = at;
                payments.add(payment);
                checkPayments();
                return payment.id;
            }

            @Override
            public int markPrepaid(UUID paymentId, String prepayId, Instant expiresAt, Instant at) {
                Payment payment = payment(paymentId);
                if (!"CREATED".equals(payment.status)) {
                    return 0;
                }
                payment.status = "PREPAID";
                payment.prepayId = prepayId;
                payment.prepayExpiresAt = expiresAt;
                payment.version++;
                payment.updatedAt = at;
                checkPayments();
                return 1;
            }

            @Override
            public int markSucceeded(UUID paymentId, String transactionId, Instant succeededAt, Instant at) {
                Payment payment = payment(paymentId);
                if (!Set.of("CREATED", "PREPAID", "CLOSED").contains(payment.status)) {
                    return 0;
                }
                payment.status = "SUCCEEDED";
                payment.transactionId = transactionId;
                payment.succeededAt = succeededAt;
                payment.version++;
                payment.updatedAt = at;
                checkPayments();
                return 1;
            }

            @Override
            public int markMismatch(UUID paymentId, Instant at) {
                Payment payment = payment(paymentId);
                if ("SUCCEEDED".equals(payment.status)) {
                    return 0;
                }
                payment.status = "MISMATCH";
                payment.version++;
                payment.updatedAt = at;
                checkPayments();
                return 1;
            }

            @Override
            public int closePayment(UUID paymentId, Instant at) {
                Payment payment = payment(paymentId);
                if (!payment.open()) {
                    return 0;
                }
                payment.status = "CLOSED";
                payment.closedAt = at;
                payment.version++;
                payment.updatedAt = at;
                checkPayments();
                if (afterClosePayment != null) {
                    java.util.function.Consumer<Payment> hook = afterClosePayment;
                    afterClosePayment = null;
                    hook.accept(payment);
                }
                return 1;
            }

            @Override
            public List<PaymentRow> findPaymentsToPoll(Instant createdBefore, Instant now, int limit) {
                pollQueries.add(List.of(createdBefore, now, limit));
                return payments.stream()
                        .filter(Payment::open)
                        .filter(payment -> "PENDING_PAYMENT".equals(order(payment.orderId).status))
                        .filter(payment -> !payment.createdAt.isAfter(createdBefore))
                        .filter(payment -> order(payment.orderId).payBefore.isAfter(now))
                        .sorted(Comparator.comparing((Payment payment) -> payment.updatedAt)
                                .thenComparing(payment -> payment.id))
                        .limit(limit)
                        .map(Payment::toRow)
                        .toList();
            }

            @Override
            public List<PaymentRow> findExpiredWithOpenPayment(Instant now, int limit) {
                expiredQueries.add(List.of(now, limit));
                return payments.stream()
                        .filter(Payment::open)
                        .filter(payment -> "PENDING_PAYMENT".equals(order(payment.orderId).status))
                        .filter(payment -> !order(payment.orderId).payBefore.isAfter(now))
                        .sorted(Comparator.comparing((Payment payment) -> payment.updatedAt)
                                .thenComparing(payment -> payment.id))
                        .limit(limit)
                        .map(Payment::toRow)
                        .toList();
            }

            @Override
            public int touchPayment(UUID paymentId, Instant at) {
                touchedPayments.add(paymentId);
                payment(paymentId).updatedAt = at;
                return 1;
            }

            @Override
            public UUID insertNotification(String notificationId, String eventType, String outTradeNo,
                                           String outRefundNo, Instant at) {
                if (failNextInsertNotification != null) {
                    RuntimeException failure = failNextInsertNotification;
                    failNextInsertNotification = null;
                    throw failure;
                }
                if (notificationId == null || notificationId.length() > 64 || eventType == null
                        || eventType.length() > 40 || (outTradeNo != null && outTradeNo.length() > 32)
                        || (outRefundNo != null && outRefundNo.length() > 64)) {
                    throw new IllegalStateException("trade_channel_notification check violated");
                }
                if (notices.containsKey(notificationId)) {
                    return null;
                }
                Notice notice = new Notice();
                notice.id = UUID.randomUUID();
                notice.notificationId = notificationId;
                notice.eventType = eventType;
                notice.outTradeNo = outTradeNo;
                notice.outRefundNo = outRefundNo;
                notice.outcome = "RECEIVED";
                notice.receivedAt = at;
                notices.put(notificationId, notice);
                return notice.id;
            }

            @Override
            public NotificationRow findNotification(String notificationId) {
                Notice notice = notices.get(notificationId);
                return notice == null ? null : new NotificationRow(notice.id, notice.outcome);
            }

            @Override
            public int finishNotification(UUID id, String outcome, String detail) {
                if (failNextFinish != null) {
                    RuntimeException failure = failNextFinish;
                    failNextFinish = null;
                    throw failure;
                }
                if (!Set.of("RECEIVED", "APPLIED", "IGNORED", "REJECTED").contains(outcome)
                        || (detail != null && detail.length() > 200)) {
                    throw new IllegalStateException("trade_channel_notification check violated");
                }
                for (Notice notice : notices.values()) {
                    // 和 SQL 一样只改还是 RECEIVED 的那一条。
                    if (notice.id.equals(id) && "RECEIVED".equals(notice.outcome)) {
                        notice.outcome = outcome;
                        notice.detail = detail;
                        return 1;
                    }
                }
                return 0;
            }
        }
    }
}
