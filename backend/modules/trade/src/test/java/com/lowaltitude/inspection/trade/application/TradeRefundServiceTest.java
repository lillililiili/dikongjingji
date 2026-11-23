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
import java.util.function.Function;

import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundAccepted;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundRequest;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundState;
import com.lowaltitude.inspection.trade.application.PaymentChannelException.Kind;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeRefundService.Applied;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeRefundView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 退款的规则测试（E05，docs/redesign/03-App实现任务计划.md §19.7）。订单、支付单、退款单放在一份内存数据里，
 * TradeRefundMapper 和 TradeOrderMapper 的内存实现按 SQL 的条件和 V001 / V002 的约束写（违反约束时抛异常，
 * 比如已退金额超过实付），渠道换成可以逐次编排结果的假渠道，事务用 withoutTransaction，时钟可拨动。
 * 返回的订单由真的 TradeOrderService 读。SQL 文本本身由 TradeRefundMapperContractTest 检查。
 *
 * <p>内存实现没有回滚：需要“事务中途失败”的用例，故障都放在第一处写之前。
 */
class TradeRefundServiceTest {

    /** 北京时间 2026-11-19 00:30：退款单号的日期按北京时间。 */
    private static final Instant NOW = Instant.parse("2026-11-18T16:30:00Z");
    private static final Instant PAID_AT = NOW.minus(Duration.ofDays(3));
    private static final Instant SUCCEEDED_AT = NOW.plusSeconds(20);
    private static final UUID BUYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_BUYER = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000002");
    private static final UUID OPERATOR = UUID.fromString("c1000000-0000-4000-8000-0000000000a1");
    private static final UUID KEY = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID KEY_2 = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID KEY_3 = UUID.fromString("a0000000-0000-4000-8000-000000000003");
    private static final String SUB_MCHID = "1900000109";
    private static final long PAID = 141_000;
    private static final String CANCEL_REASON = "买家取消订单";

    private final Store store = new Store();
    private final FakeChannel channel = new FakeChannel();
    private final MutableClock clock = new MutableClock(NOW);
    private final Deque<String> suffixes = new ArrayDeque<>();
    private PaymentChannel configured = channel;
    private int channelLookups;
    private long suffixCounter;
    private final TradeOrderService orderService =
            new TradeOrderService(store.orderMapper, null, clock, () -> "00000001");
    private final TradeRefundService service = new TradeRefundService(
            store.refundMapper,
            store.orderMapper,
            orderService,
            () -> {
                channelLookups++;
                return configured;
            },
            TransactionOperations.withoutTransaction(),
            clock,
            this::nextSuffix);

    // 一张订单最多退 50 次、付款后一年内；已建未提交的 1 分钟后重提，处理中的 1 分钟、异常的 1 小时查一次；每轮 50 笔。
    @Test
    void theLimitsAreTheAgreedOnes() {
        assertThat(TradeRefundService.MAX_REFUNDS_PER_ORDER).isEqualTo(50);
        assertThat(TradeRefundService.REFUND_WINDOW).isEqualTo(Duration.ofDays(365));
        assertThat(TradeRefundService.RETRY_AFTER).isEqualTo(Duration.ofMinutes(1));
        assertThat(TradeRefundService.ABNORMAL_RECHECK).isEqualTo(Duration.ofHours(1));
        assertThat(TradeRefundService.BATCH).isEqualTo(50);
        assertThat(TradeRefundService.MAX_REASON).isEqualTo(80);
        assertThat(TradeRefundService.BUYER_CANCEL_REASON).isEqualTo(CANCEL_REASON);
    }

    // ---- 买家取消已付款的订单 ----

    @Test
    void cancellingAPaidOrderRefundsEverythingAndTheOrderIsRefunding() {
        UUID orderId = store.order(BUYER, "PAID");
        Payment payment = store.paymentOf(orderId);
        suffixes.add("000000000042");

        TradeOrderView order = service.cancelPaid(BUYER, orderId, 1, KEY);

        Refund refund = store.onlyRefund();
        assertThat(refund.outRefundNo).isEqualTo("R20261119000000000042").hasSize(21);
        assertThat(refund.paymentId).isEqualTo(payment.id);
        assertThat(refund.amountMinor).isEqualTo(PAID);
        assertThat(refund.reason).isEqualTo(CANCEL_REASON);
        assertThat(refund.initiator).isEqualTo("BUYER_CANCEL");
        assertThat(refund.requestedBy).isEqualTo(BUYER);
        assertThat(refund.idempotencyKey).isEqualTo(KEY);
        assertThat(refund.createdAt).isEqualTo(NOW);
        // 提交给渠道：订单快照上的子商户号、支付单号、这次退多少、原单多少。
        assertThat(channel.refundRequests).containsExactly(new RefundRequest(
                SUB_MCHID, payment.outTradeNo, "R20261119000000000042", PAID, PAID, CANCEL_REASON));
        assertThat(refund.status).isEqualTo("PROCESSING");
        assertThat(refund.refundId).isEqualTo(channel.refundIdOf(refund.outRefundNo));
        // 订单进入退款中；钱到账前已退金额不变。
        assertThat(order.status()).isEqualTo(TradeOrderStatus.REFUNDING);
        assertThat(order.version()).isEqualTo(2);
        assertThat(order.cancellable()).isFalse();
        assertThat(order.amounts().refundedMinor()).isZero();
        assertThat(order.refunds()).containsExactly(
                new TradeRefundView(refund.id, PAID, CANCEL_REASON, "PROCESSING", NOW, null));
        assertThat(store.transitions).containsExactly(
                new Transition(orderId, "PAID", "REFUNDING", "BUYER_CANCEL", BUYER, NOW));
        // 建退款单时锁买家的订单；提交时不占锁调渠道，记受理结果时先锁订单。
        assertThat(store.locks).containsExactly(
                "lockBuyerOrder:" + orderId, "lockRefund:" + refund.id, "lockOrder:" + orderId);
    }

    // 退的是“实付 − 已退 − 在路上的”；关闭了的退款不算。在路上的和这一笔都到账后订单才是已退款。
    @Test
    void theBuyerGetsBackWhatIsNeitherRefundedNorOnItsWay() {
        UUID orderId = store.order(BUYER, "PAID");
        store.refund(orderId, "SUCCEEDED", 10_000, NOW.minusSeconds(600));
        Refund onItsWay = store.refund(orderId, "PROCESSING", 5_000, NOW.minusSeconds(300));
        store.refund(orderId, "CLOSED", 7_000, NOW.minusSeconds(200));

        service.cancelPaid(BUYER, orderId, 1, KEY);

        Refund cancel = store.refundByKey(orderId, KEY);
        assertThat(cancel.amountMinor).isEqualTo(PAID - 10_000 - 5_000);
        assertThat(store.order(orderId).status).isEqualTo("REFUNDING");

        assertThat(service.apply(result(onItsWay, RefundState.SUCCESS), "REFUND_NOTIFIED")).isEqualTo(Applied.APPLIED);
        assertThat(store.order(orderId).status).as("取消的那笔还在路上").isEqualTo("REFUNDING");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(15_000);

        assertThat(service.apply(result(cancel, RefundState.SUCCESS), "REFUND_NOTIFIED")).isEqualTo(Applied.APPLIED);
        assertThat(store.order(orderId).status).isEqualTo("REFUNDED");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(PAID);
        assertThat(store.transitions).extracting(Transition::to).containsExactly("REFUNDING", "REFUNDED");
    }

    @Test
    void theSameKeyAgainReturnsTheOrderWithoutASecondRefund() {
        UUID orderId = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, orderId, 1, KEY);

        // 版本已经变了（2），重放带的还是旧版本：同一个键照样原样返回。
        TradeOrderView again = service.cancelPaid(BUYER, orderId, 1, KEY);

        assertThat(again).isEqualTo(orderService.read(BUYER, orderId));
        assertThat(store.refunds).hasSize(1);
        assertThat(channel.refundRequests).hasSize(1);
        assertThat(store.transitions).hasSize(1);
    }

    // 渠道不通：退款单留在“已建”；买家重放同一个键时用同一个退款单号再提交一次（微信保证同一单号只退一次）。
    @Test
    void aReplayAfterTheChannelFailedSubmitsTheSameRefundAgain() {
        UUID orderId = store.order(BUYER, "PAID");
        channel.refundAnswers.add(request -> {
            throw new PaymentChannelException(Kind.RETRYABLE, "SYSTEM_ERROR", 500, "busy");
        });

        TradeOrderView first = service.cancelPaid(BUYER, orderId, 1, KEY);

        assertThat(first.status()).isEqualTo(TradeOrderStatus.REFUNDING);
        assertThat(first.refunds()).extracting(TradeRefundView::status).containsExactly("REQUESTED");
        clock.now = NOW.plusSeconds(10);

        TradeOrderView again = service.cancelPaid(BUYER, orderId, 2, KEY);

        assertThat(again.refunds()).extracting(TradeRefundView::status).containsExactly("PROCESSING");
        assertThat(store.refunds).hasSize(1);
        assertThat(channel.refundRequests).hasSize(2);
        assertThat(channel.refundRequests.get(1)).isEqualTo(channel.refundRequests.get(0));
    }

    @Test
    void aKeyUsedForAMerchantRefundCannotCancel() {
        UUID orderId = store.order(BUYER, "PAID");
        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1_000L, "少发一件");

        assertRefused(() -> service.cancelPaid(BUYER, orderId, 1, KEY), Error.IDEMPOTENCY_CONFLICT);

        assertThat(store.refunds).hasSize(1);
        assertThat(store.order(orderId).status).isEqualTo("PAID");
    }

    // 已在退款中、已退款：再取消（换了键）原样返回，不建退款单、不找渠道。
    @ParameterizedTest
    @ValueSource(strings = {"REFUNDING", "REFUNDED"})
    void anOrderBeingOrAlreadyRefundedIsReturnedAsItIs(String status) {
        UUID orderId = store.order(BUYER, status);

        TradeOrderView order = service.cancelPaid(BUYER, orderId, 9, KEY);

        assertThat(order.status()).isEqualTo(TradeOrderStatus.valueOf(status));
        assertThat(store.refunds).isEmpty();
        assertThat(channel.calls).isEmpty();
        assertThat(store.transitions).isEmpty();
    }

    // 已发货、已完成、退款异常的要商家退；没付款、已关闭的没有钱可退。状态不对先于版本不对。
    @ParameterizedTest
    @ValueSource(strings = {"PENDING_PAYMENT", "CLOSED", "SHIPPED", "COMPLETED", "REFUND_FAILED"})
    void onlyAPaidOrderIsRefundedByCancelling(String status) {
        UUID orderId = store.order(BUYER, status);

        assertRefused(() -> service.cancelPaid(BUYER, orderId, 1, KEY), Error.ORDER_STATE_INVALID);
        assertRefused(() -> service.cancelPaid(BUYER, orderId, 9, KEY), Error.ORDER_STATE_INVALID);

        assertThat(store.refunds).isEmpty();
        assertThat(store.order(orderId).status).isEqualTo(status);
    }

    @Test
    void cancellingNeedsTheBuyersOwnOrderAtItsVersionAndAChannel() {
        UUID orderId = store.order(BUYER, "PAID");

        assertRefused(() -> service.cancelPaid(BUYER, orderId, 0, KEY), Error.VERSION_CONFLICT);
        assertRefused(() -> service.cancelPaid(OTHER_BUYER, orderId, 1, KEY), Error.NOT_FOUND);
        assertRefused(() -> service.cancelPaid(BUYER, UUID.randomUUID(), 1, KEY), Error.NOT_FOUND);
        assertThat(store.refunds).isEmpty();

        int lookups = channelLookups;
        for (Runnable call : List.<Runnable>of(
                () -> service.cancelPaid(null, orderId, 1, KEY),
                () -> service.cancelPaid(BUYER, null, 1, KEY),
                () -> service.cancelPaid(BUYER, orderId, 1, null))) {
            assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(channelLookups).isEqualTo(lookups);

        // 没配置渠道：什么都不锁、不写。
        configured = null;
        store.locks.clear();
        assertRefused(() -> service.cancelPaid(BUYER, orderId, 1, KEY), Error.CHANNEL_UNAVAILABLE);
        assertThat(store.locks).isEmpty();
        assertThat(store.order(orderId).status).isEqualTo("PAID");
        assertThat(store.refunds).isEmpty();
    }

    @Test
    void aPaidOrderWithoutASucceededPaymentOrWithNothingLeftIsNotRefunded() {
        UUID noPayment = store.order(BUYER, "PAID");
        store.paymentOf(noPayment).status = "MISMATCH";
        UUID nothingLeft = store.order(BUYER, "PAID");
        store.refund(nothingLeft, "PROCESSING", PAID, NOW.minusSeconds(60));

        assertRefused(() -> service.cancelPaid(BUYER, noPayment, 1, KEY), Error.ORDER_STATE_INVALID);
        assertRefused(() -> service.cancelPaid(BUYER, nothingLeft, 1, KEY), Error.ORDER_STATE_INVALID);

        assertThat(store.refunds).hasSize(1);
        assertThat(store.transitions).isEmpty();
    }

    @Test
    void cancellingAfterAYearOrFiftyRefundsIsOverTheLimit() {
        UUID old = store.order(BUYER, "PAID");
        store.order(old).paidAt = NOW.minus(Duration.ofDays(365)).minusSeconds(1);
        UUID many = store.order(BUYER, "PAID");
        for (int i = 0; i < 50; i++) {
            store.refund(many, "CLOSED", 1, NOW.minusSeconds(1000 - i));
        }

        assertRefused(() -> service.cancelPaid(BUYER, old, 1, KEY), Error.REFUND_LIMIT_REACHED);
        assertRefused(() -> service.cancelPaid(BUYER, many, 1, KEY), Error.REFUND_LIMIT_REACHED);

        assertThat(store.refunds).hasSize(50);
        assertThat(channel.calls).isEmpty();
    }

    // ---- 商家退款 ----

    // 部分退款：订单状态和版本都不变，不记迁移；钱到账后累加已退金额。
    @Test
    void aPartialRefundLeavesTheOrderAsItIs() {
        UUID orderId = store.order(BUYER, "SHIPPED");
        Payment payment = store.paymentOf(orderId);
        suffixes.add("000000000007");

        TradeRefundView view = service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 41_000L, "  少发一件配件  ");

        Refund refund = store.onlyRefund();
        assertThat(view).isEqualTo(new TradeRefundView(refund.id, 41_000, "少发一件配件", "PROCESSING", NOW, null));
        assertThat(refund.outRefundNo).isEqualTo("R20261119000000000007");
        assertThat(refund.initiator).isEqualTo("MERCHANT");
        assertThat(refund.requestedBy).isEqualTo(OPERATOR);
        assertThat(refund.idempotencyKey).isEqualTo(KEY);
        assertThat(channel.refundRequests).containsExactly(new RefundRequest(
                SUB_MCHID, payment.outTradeNo, "R20261119000000000007", 41_000, PAID, "少发一件配件"));
        assertThat(store.order(orderId).status).isEqualTo("SHIPPED");
        assertThat(store.order(orderId).version).isEqualTo(1);
        assertThat(store.transitions).isEmpty();
        assertThat(store.locks).containsExactly("lockMerchantOrder:" + orderId, "lockRefund:" + refund.id,
                "lockOrder:" + orderId, "lockRefund:" + refund.id);

        assertThat(service.apply(result(refund, RefundState.SUCCESS), "REFUND_NOTIFIED")).isEqualTo(Applied.APPLIED);

        Order order = store.order(orderId);
        assertThat(order.status).isEqualTo("SHIPPED");
        assertThat(order.refundedMinor).isEqualTo(41_000);
        assertThat(order.version).isEqualTo(2);
        assertThat(store.transitions).isEmpty();
        assertThat(refund.status).isEqualTo("SUCCEEDED");
        assertThat(refund.succeededAt).isEqualTo(SUCCEEDED_AT);
    }

    // 退的是剩下的全部：订单进入退款中（MERCHANT_REFUND，记操作员），到账后已退款。
    @ParameterizedTest
    @ValueSource(strings = {"PAID", "SHIPPED", "COMPLETED", "REFUND_FAILED"})
    void refundingTheRestPutsTheOrderIntoRefunding(String status) {
        UUID orderId = store.order(BUYER, status);

        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, PAID, "整单退款");

        assertThat(store.order(orderId).status).isEqualTo("REFUNDING");
        assertThat(store.order(orderId).version).isEqualTo(2);
        assertThat(store.transitions).containsExactly(
                new Transition(orderId, status, "REFUNDING", "MERCHANT_REFUND", OPERATOR, NOW));

        clock.now = NOW.plusSeconds(90);
        assertThat(service.apply(result(store.onlyRefund(), RefundState.SUCCESS), "REFUND_NOTIFIED"))
                .isEqualTo(Applied.APPLIED);

        assertThat(store.order(orderId).status).isEqualTo("REFUNDED");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(PAID);
        assertThat(store.transitions).last().isEqualTo(
                new Transition(orderId, "REFUNDING", "REFUNDED", "REFUND_NOTIFIED", null, NOW.plusSeconds(90)));
    }

    // 两笔部分退款加起来退完：第二笔是“剩下的全部”（扣掉在路上的第一笔），订单进入退款中，两笔都到账后已退款。
    @Test
    void twoPartialRefundsThatAddUpToEverythingRefundTheOrder() {
        UUID orderId = store.order(BUYER, "PAID");
        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 41_000L, "少发一件");
        Refund first = store.refundByKey(orderId, KEY);

        // 第一笔还在路上：最多还能退 100_000。被拒的请求什么都没写，同一个键可以再用。
        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY_2, 100_001L, "其余退回"),
                Error.REFUND_EXCEEDS_PAID);
        assertThat(store.refunds).hasSize(1);
        assertThat(store.order(orderId).status).isEqualTo("PAID");

        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY_2, 100_000L, "其余退回");
        Refund second = store.refundByKey(orderId, KEY_2);
        assertThat(store.order(orderId).status).isEqualTo("REFUNDING");

        assertThat(service.apply(result(first, RefundState.SUCCESS), "REFUND_NOTIFIED")).isEqualTo(Applied.APPLIED);
        assertThat(store.order(orderId).status).isEqualTo("REFUNDING");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(41_000);

        assertThat(service.apply(result(second, RefundState.SUCCESS), "REFUND_QUERIED")).isEqualTo(Applied.APPLIED);
        assertThat(store.order(orderId).status).isEqualTo("REFUNDED");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(PAID);
        assertThat(store.transitions).containsExactly(
                new Transition(orderId, "PAID", "REFUNDING", "MERCHANT_REFUND", OPERATOR, NOW),
                new Transition(orderId, "REFUNDING", "REFUNDED", "REFUND_QUERIED", null, NOW));
        assertThat(orderService.read(BUYER, orderId).refunds())
                .extracting(TradeRefundView::amountMinor, TradeRefundView::status)
                .containsExactlyInAnyOrder(
                        tuple(41_000L, "SUCCEEDED"),
                        tuple(100_000L, "SUCCEEDED"));
    }

    // 全额退款里有一笔没退成：订单“退款异常”；商家可以再退（先部分、再剩下的全部），都到账后已退款。
    @Test
    void aFullRefundWithAFailedPartIsRefundFailedAndTheMerchantCanRefundAgain() {
        UUID orderId = store.order(BUYER, "PAID");
        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 41_000L, "少发一件");
        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY_2, 100_000L, "其余退回");
        Refund first = store.refundByKey(orderId, KEY);
        Refund rest = store.refundByKey(orderId, KEY_2);

        service.apply(result(first, RefundState.SUCCESS), "REFUND_NOTIFIED");
        assertThat(service.apply(result(rest, RefundState.CLOSED), "REFUND_NOTIFIED")).isEqualTo(Applied.APPLIED);

        assertThat(rest.status).isEqualTo("CLOSED");
        assertThat(store.order(orderId).status).isEqualTo("REFUND_FAILED");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(41_000);

        // 退款异常时部分退款不改状态；退剩下的全部（扣掉在路上的）才进入退款中。
        UUID partKey = UUID.randomUUID();
        service.merchantRefund(MERCHANT, OPERATOR, orderId, partKey, 1_000L, "补退运费");
        assertThat(store.order(orderId).status).isEqualTo("REFUND_FAILED");
        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY_3, 100_000L, "重新退款"),
                Error.REFUND_EXCEEDS_PAID);
        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY_3, 99_000L, "重新退款");
        assertThat(store.order(orderId).status).isEqualTo("REFUNDING");

        service.apply(result(store.refundByKey(orderId, partKey), RefundState.SUCCESS), "REFUND_NOTIFIED");
        assertThat(store.order(orderId).status).isEqualTo("REFUNDING");
        service.apply(result(store.refundByKey(orderId, KEY_3), RefundState.SUCCESS), "REFUND_NOTIFIED");

        assertThat(store.order(orderId).status).isEqualTo("REFUNDED");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(PAID);
        assertThat(store.transitions).extracting(Transition::from, Transition::to, Transition::cause)
                .containsExactly(
                        tuple("PAID", "REFUNDING", "MERCHANT_REFUND"),
                        tuple("REFUNDING", "REFUND_FAILED", "REFUND_NOTIFIED"),
                        tuple("REFUND_FAILED", "REFUNDING", "MERCHANT_REFUND"),
                        tuple("REFUNDING", "REFUNDED", "REFUND_NOTIFIED"));
    }

    // 不管失败的那笔先到还是后到，最后一笔有结论时订单变成退款异常（先到时由后到的成功判断）。
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theFailedPartMakesTheOrderRefundFailedWhicheverArrivesLast(boolean failureFirst) {
        UUID orderId = store.order(BUYER, "PAID");
        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 41_000L, "少发一件");
        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY_2, 100_000L, "其余退回");
        Refund failing = store.refundByKey(orderId, KEY);
        Refund succeeding = store.refundByKey(orderId, KEY_2);

        if (failureFirst) {
            service.apply(result(failing, RefundState.ABNORMAL), "REFUND_NOTIFIED");
            assertThat(store.order(orderId).status).as("另一笔还在路上").isEqualTo("REFUNDING");
            service.apply(result(succeeding, RefundState.SUCCESS), "REFUND_QUERIED");
        } else {
            service.apply(result(succeeding, RefundState.SUCCESS), "REFUND_NOTIFIED");
            assertThat(store.order(orderId).status).as("另一笔还在路上").isEqualTo("REFUNDING");
            service.apply(result(failing, RefundState.ABNORMAL), "REFUND_QUERIED");
        }

        assertThat(store.order(orderId).status).isEqualTo("REFUND_FAILED");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(100_000);
        assertThat(store.transitions).last().isEqualTo(
                new Transition(orderId, "REFUNDING", "REFUND_FAILED", "REFUND_QUERIED", null, NOW));
    }

    // 金额至少 1 分；原因去掉首尾空白后 1 到 80 个字、不能有控制字符。参数不对时不找渠道、不锁。
    @Test
    void theAmountAndTheReasonAreCheckedFirst() {
        UUID orderId = store.order(BUYER, "PAID");

        for (Runnable call : List.<Runnable>of(
                () -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, null, "少发一件"),
                () -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 0L, "少发一件"),
                () -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, -1L, "少发一件"),
                () -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1L, null),
                () -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1L, ""),
                () -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1L, "   "),
                () -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1L, "退".repeat(81)),
                () -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1L, "少发\n一件"),
                () -> service.merchantRefund(null, OPERATOR, orderId, KEY, 1L, "少发一件"),
                () -> service.merchantRefund(MERCHANT, null, orderId, KEY, 1L, "少发一件"),
                () -> service.merchantRefund(MERCHANT, OPERATOR, null, KEY, 1L, "少发一件"),
                () -> service.merchantRefund(MERCHANT, OPERATOR, orderId, null, 1L, "少发一件"))) {
            assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(channelLookups).isZero();
        assertThat(store.locks).isEmpty();

        TradeRefundView longest = service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1L, " " + "退".repeat(80) + " ");
        assertThat(longest.reason()).isEqualTo("退".repeat(80));
        assertThat(longest.amountMinor()).isEqualTo(1);
    }

    @Test
    void moreThanIsLeftIsRefused() {
        UUID orderId = store.order(BUYER, "COMPLETED");
        store.refund(orderId, "SUCCEEDED", 41_000, NOW.minusSeconds(600));

        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, PAID, "整单退款"),
                Error.REFUND_EXCEEDS_PAID);
        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 100_001L, "整单退款"),
                Error.REFUND_EXCEEDS_PAID);
        assertThat(store.refunds).hasSize(1);
        assertThat(channel.calls).isEmpty();

        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 100_000L, "整单退款");
        assertThat(store.order(orderId).status).isEqualTo("REFUNDING");
    }

    // 同一个键、同样的金额和原因：返回同一笔（订单已经进入退款中也一样）；换了内容或是买家取消用过的键：拒绝。
    @Test
    void theSameKeyReturnsTheSameRefundOnlyForTheSameContent() {
        UUID orderId = store.order(BUYER, "PAID");
        TradeRefundView first = service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, PAID, "整单退款");

        assertThat(service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, PAID, " 整单退款 ")).isEqualTo(first);
        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, PAID - 1, "整单退款"),
                Error.IDEMPOTENCY_CONFLICT);
        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, PAID, "别的原因"),
                Error.IDEMPOTENCY_CONFLICT);

        assertThat(store.refunds).hasSize(1);
        assertThat(channel.refundRequests).hasSize(1);
        assertThat(store.transitions).hasSize(1);

        UUID cancelled = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, cancelled, 1, KEY_2);
        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, cancelled, KEY_2, PAID, CANCEL_REASON),
                Error.IDEMPOTENCY_CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_PAYMENT", "CLOSED", "REFUNDING", "REFUNDED"})
    void onlyAPaidShippedCompletedOrFailedOrderCanBeRefundedByTheMerchant(String status) {
        UUID orderId = store.order(BUYER, status);

        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1L, "少发一件"),
                Error.ORDER_STATE_INVALID);
        assertThat(store.refunds).isEmpty();
    }

    @Test
    void anotherMerchantsOrderIsNotFoundAndNothingWorksWithoutAChannel() {
        UUID orderId = store.order(BUYER, "PAID");

        assertRefused(() -> service.merchantRefund(OTHER_MERCHANT, OPERATOR, orderId, KEY, 1L, "少发一件"),
                Error.NOT_FOUND);
        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, UUID.randomUUID(), KEY, 1L, "少发一件"),
                Error.NOT_FOUND);

        configured = null;
        store.locks.clear();
        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1L, "少发一件"),
                Error.CHANNEL_UNAVAILABLE);
        assertThat(store.locks).isEmpty();
        assertThat(store.refunds).isEmpty();
    }

    // 第 50 笔可以，第 51 笔不行（关闭了的也算）；付款后正好一年的那一刻还可以。
    @Test
    void theFiftiethRefundAndTheLastDayOfTheYearAreStillAllowed() {
        UUID orderId = store.order(BUYER, "PAID");
        for (int i = 0; i < 49; i++) {
            store.refund(orderId, "CLOSED", 1, NOW.minusSeconds(1000 - i));
        }
        store.order(orderId).paidAt = NOW.minus(Duration.ofDays(365));

        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1L, "第五十笔");
        assertThat(store.refunds).hasSize(50);

        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY_2, 1L, "第五十一笔"),
                Error.REFUND_LIMIT_REACHED);

        UUID late = store.order(BUYER, "COMPLETED");
        store.order(late).paidAt = NOW.minus(Duration.ofDays(365)).minusSeconds(1);
        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, late, KEY, 1L, "过期"),
                Error.REFUND_LIMIT_REACHED);
        assertThat(store.refunds).hasSize(50);
    }

    @Test
    void anOrderWithoutASucceededPaymentIsNotRefundedByTheMerchant() {
        UUID orderId = store.order(BUYER, "SHIPPED");
        store.paymentOf(orderId).status = "MISMATCH";

        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1L, "少发一件"),
                Error.ORDER_STATE_INVALID);
        assertThat(store.refunds).isEmpty();
    }

    // ---- 提交给渠道 ----

    // 渠道不通（或应答不可信）：退款单留在“已建”只更新时间；定时任务 1 分钟后用同一个退款单号重新提交。
    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"RETRYABLE", "UNTRUSTED"})
    void aRefundTheChannelDidNotTakeIsRetriedWithTheSameNumber(Kind kind) {
        UUID orderId = store.order(BUYER, "PAID");
        channel.refundAnswers.add(request -> {
            throw new PaymentChannelException(kind, "SYSTEM_ERROR", 500, "busy");
        });
        clock.now = NOW.plusSeconds(5);

        TradeRefundView view = service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 41_000L, "少发一件");

        Refund refund = store.onlyRefund();
        assertThat(view.status()).isEqualTo("REQUESTED");
        assertThat(refund.updatedAt).isEqualTo(NOW.plusSeconds(5));
        assertThat(store.touched).containsExactly(refund.id);
        assertThat(refund.refundId).isNull();

        clock.now = NOW.plusSeconds(64);
        assertThat(service.pollRefunds()).isZero();
        assertThat(channel.refundRequests).as("还不到 1 分钟").hasSize(1);

        clock.now = NOW.plusSeconds(65);
        assertThat(service.pollRefunds()).isZero();

        assertThat(channel.refundRequests).hasSize(2);
        assertThat(channel.refundRequests.get(1)).isEqualTo(channel.refundRequests.get(0));
        assertThat(refund.status).isEqualTo("PROCESSING");
        assertThat(refund.refundId).isEqualTo(channel.refundIdOf(refund.outRefundNo));
        assertThat(store.refunds).hasSize(1);
    }

    // 渠道当场拒绝全额退款（比如商家余额不足）：退款单关闭，订单退款异常（REFUND_REJECTED），之后不再重提。
    @Test
    void aRejectedFullRefundIsClosedAndTheOrderIsRefundFailed() {
        UUID orderId = store.order(BUYER, "PAID");
        channel.refundAnswers.add(request -> {
            throw new PaymentChannelException(Kind.REJECTED, "NOT_ENOUGH", 403, "balance is not enough");
        });

        TradeOrderView order = service.cancelPaid(BUYER, orderId, 1, KEY);

        Refund refund = store.onlyRefund();
        assertThat(refund.status).isEqualTo("CLOSED");
        assertThat(order.status()).isEqualTo(TradeOrderStatus.REFUND_FAILED);
        assertThat(order.refunds()).extracting(TradeRefundView::status).containsExactly("CLOSED");
        assertThat(store.transitions).containsExactly(
                new Transition(orderId, "PAID", "REFUNDING", "BUYER_CANCEL", BUYER, NOW),
                new Transition(orderId, "REFUNDING", "REFUND_FAILED", "REFUND_REJECTED", null, NOW));
        // 关闭时同样先锁订单、后锁退款单。
        assertThat(store.locks).containsExactly("lockBuyerOrder:" + orderId, "lockRefund:" + refund.id,
                "lockOrder:" + orderId, "lockRefund:" + refund.id);

        clock.now = NOW.plus(Duration.ofHours(2));
        assertThat(service.pollRefunds()).isZero();
        assertThat(channel.refundRequests).hasSize(1);
    }

    @Test
    void aRejectedPartialRefundOnlyClosesThatRefund() {
        UUID orderId = store.order(BUYER, "SHIPPED");
        channel.refundAnswers.add(request -> {
            throw new PaymentChannelException(Kind.REJECTED, "INVALID_REQUEST", 400, "rejected");
        });

        TradeRefundView view = service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1_000L, "少发一件");

        assertThat(view.status()).isEqualTo("CLOSED");
        assertThat(store.order(orderId).status).isEqualTo("SHIPPED");
        assertThat(store.order(orderId).version).isEqualTo(1);
        assertThat(store.transitions).isEmpty();
    }

    // 被拒的是“剩下的全部”，但前面还有一笔在路上：订单留在退款中；那一笔到账后没退完，订单退款异常。
    @Test
    void aRejectedRestWhileAnotherPartIsOnItsWayKeepsTheOrderRefunding() {
        UUID orderId = store.order(BUYER, "PAID");
        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 41_000L, "少发一件");
        channel.refundAnswers.add(request -> {
            throw new PaymentChannelException(Kind.REJECTED, "NOT_ENOUGH", 403, "balance is not enough");
        });

        TradeRefundView rest = service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY_2, 100_000L, "其余退回");

        assertThat(rest.status()).isEqualTo("CLOSED");
        assertThat(store.order(orderId).status).isEqualTo("REFUNDING");

        service.apply(result(store.refundByKey(orderId, KEY), RefundState.SUCCESS), "REFUND_NOTIFIED");

        assertThat(store.order(orderId).status).isEqualTo("REFUND_FAILED");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(41_000);
        assertThat(store.transitions).extracting(Transition::cause)
                .containsExactly("MERCHANT_REFUND", "REFUND_NOTIFIED");
    }

    // 只有这五个错误码算确定的业务拒绝（联调时确认）。
    @Test
    void theDefiniteRejectionsAreTheAgreedCodes() {
        assertThat(TradeRefundService.DEFINITE_REFUND_REJECTIONS).containsExactlyInAnyOrder(
                "NOT_ENOUGH", "USER_ACCOUNT_ABNORMAL", "INVALID_REQUEST", "PARAM_ERROR", "RESOURCE_NOT_EXISTS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_ENOUGH", "USER_ACCOUNT_ABNORMAL", "INVALID_REQUEST", "PARAM_ERROR", "RESOURCE_NOT_EXISTS"})
    void eachDefiniteRejectionClosesTheRefund(String code) {
        UUID orderId = store.order(BUYER, "PAID");
        channel.refundAnswers.add(request -> {
            throw new PaymentChannelException(Kind.REJECTED, code, 400, "rejected");
        });

        TradeOrderView order = service.cancelPaid(BUYER, orderId, 1, KEY);

        assertThat(store.onlyRefund().status).isEqualTo("CLOSED");
        assertThat(order.status()).isEqualTo(TradeOrderStatus.REFUND_FAILED);
        assertThat(store.touched).isEmpty();
    }

    // 别的拒绝（签名错、无权限……）多半是配置问题：不关退款单，只更新时间留在“已建”，修好后用同一个退款单号重提。
    @ParameterizedTest
    @ValueSource(strings = {"SIGN_ERROR", "NO_AUTH", "OUT_TRADE_NO_USED", "SOMETHING_NEW"})
    void anyOtherRejectionKeepsTheRefundForARetry(String code) {
        UUID orderId = store.order(BUYER, "PAID");
        channel.refundAnswers.add(request -> {
            throw new PaymentChannelException(Kind.REJECTED, code, 403, "rejected");
        });
        clock.now = NOW.plusSeconds(5);

        TradeOrderView order = service.cancelPaid(BUYER, orderId, 1, KEY);

        Refund refund = store.onlyRefund();
        assertThat(refund.status).isEqualTo("REQUESTED");
        assertThat(refund.updatedAt).isEqualTo(NOW.plusSeconds(5));
        assertThat(store.touched).containsExactly(refund.id);
        assertThat(order.status()).isEqualTo(TradeOrderStatus.REFUNDING);
        assertThat(store.transitions).extracting(Transition::cause).containsExactly("BUYER_CANCEL");

        // 1 分钟后定时任务用同一个退款单号重提，这次受理了。
        clock.now = NOW.plusSeconds(65);
        assertThat(service.pollRefunds()).isZero();
        assertThat(channel.refundRequests).hasSize(2);
        assertThat(channel.refundRequests.get(1)).isEqualTo(channel.refundRequests.get(0));
        assertThat(refund.status).isEqualTo("PROCESSING");
    }

    // 拒绝应答到之前，回调或查询已经把这笔退款记成处理中：拒绝不能把已受理的退款关掉，订单也不转退款异常。
    @Test
    void aRejectionDoesNotCloseARefundThatIsAlreadyProcessing() {
        UUID orderId = store.order(BUYER, "PAID");
        channel.refundAnswers.add(request -> {
            Refund refund = store.refundByOutRefundNo(request.outRefundNo());
            refund.status = "PROCESSING";
            refund.refundId = channel.refundIdOf(request.outRefundNo());
            throw new PaymentChannelException(Kind.REJECTED, "NOT_ENOUGH", 403, "balance is not enough");
        });

        TradeOrderView order = service.cancelPaid(BUYER, orderId, 1, KEY);

        assertThat(store.onlyRefund().status).isEqualTo("PROCESSING");
        assertThat(order.status()).isEqualTo(TradeOrderStatus.REFUNDING);
        assertThat(store.transitions).extracting(Transition::cause).containsExactly("BUYER_CANCEL");
        assertThat(store.touched).isEmpty();
    }

    // 受理的金额和退款单不同：照样记成处理中，等查询或回调核对（核对不上不入账）。
    @Test
    void anAcceptanceWithAnotherAmountIsLeftToTheResult() {
        UUID orderId = store.order(BUYER, "PAID");
        channel.refundAnswers.add(request -> new RefundAccepted(
                channel.refundIdOf(request.outRefundNo()), request.outRefundNo(), request.refundMinor() + 1, NOW));

        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 1_000L, "少发一件");

        Refund refund = store.onlyRefund();
        assertThat(refund.status).isEqualTo("PROCESSING");
        assertThat(service.apply(with(result(refund, RefundState.SUCCESS), SUB_MCHID,
                store.paymentOf(orderId).outTradeNo, 1_001L), "REFUND_QUERIED")).isEqualTo(Applied.MISMATCH);
        assertThat(store.order(orderId).refundedMinor).isZero();
    }

    // 结果比受理先到（回调抢在记受理之前）：结果说了算，之后记受理不会把成功改回处理中。
    @Test
    void aResultThatArrivesBeforeTheAcceptanceIsRecordedWins() {
        UUID orderId = store.order(BUYER, "PAID");
        channel.refundAnswers.add(request -> {
            Refund refund = store.refundByOutRefundNo(request.outRefundNo());
            assertThat(service.apply(result(refund, RefundState.SUCCESS), "REFUND_NOTIFIED")).isEqualTo(Applied.APPLIED);
            return channel.accepted(request);
        });

        TradeOrderView order = service.cancelPaid(BUYER, orderId, 1, KEY);

        assertThat(store.onlyRefund().status).isEqualTo("SUCCEEDED");
        assertThat(order.status()).isEqualTo(TradeOrderStatus.REFUNDED);
        assertThat(order.amounts().refundedMinor()).isEqualTo(PAID);
        // 没见过受理就到账：受理时间按到账时间记（对账按它分天），之后的受理应答不再改它。
        assertThat(store.onlyRefund().acceptedAt).isEqualTo(SUCCEEDED_AT);
    }

    // ---- 受理时间（E06：对账按微信受理的日期分天） ----

    // 受理时间用渠道给的（微信的 create_time），不是本机记下受理的时间；到账不改它。
    @Test
    void theAcceptanceTimeIsTheChannelsAndSurvivesTheSuccess() {
        UUID orderId = store.order(BUYER, "PAID");
        Instant acceptedByWechat = NOW.minusSeconds(7);
        channel.refundAnswers.add(request -> new RefundAccepted(
                channel.refundIdOf(request.outRefundNo()), request.outRefundNo(), request.refundMinor(), acceptedByWechat));
        clock.now = NOW.plusSeconds(3);

        service.cancelPaid(BUYER, orderId, 1, KEY);

        Refund refund = store.onlyRefund();
        assertThat(refund.status).isEqualTo("PROCESSING");
        assertThat(refund.acceptedAt).isEqualTo(acceptedByWechat);
        assertThat(refund.updatedAt).isEqualTo(NOW.plusSeconds(3));

        assertThat(service.apply(result(refund, RefundState.SUCCESS), "REFUND_NOTIFIED")).isEqualTo(Applied.APPLIED);
        assertThat(store.onlyRefund().succeededAt).isEqualTo(SUCCEEDED_AT);
        assertThat(store.onlyRefund().acceptedAt).isEqualTo(acceptedByWechat);
    }

    // 受理应答里没有 create_time：按本机记下受理的时间。
    @Test
    void anAcceptanceWithoutATimeIsDatedNow() {
        UUID orderId = store.order(BUYER, "PAID");
        channel.refundAnswers.add(request -> new RefundAccepted(
                channel.refundIdOf(request.outRefundNo()), request.outRefundNo(), request.refundMinor(), null));
        clock.now = NOW.plusSeconds(5);

        service.cancelPaid(BUYER, orderId, 1, KEY);

        assertThat(store.onlyRefund().acceptedAt).isEqualTo(NOW.plusSeconds(5));
    }

    // 渠道没受理（提交失败、被拒）的退款没有受理时间，不进对账。
    @Test
    void aRefundTheChannelDidNotAcceptHasNoAcceptanceTime() {
        UUID orderId = store.order(BUYER, "PAID");
        channel.refundAnswers.add(request -> {
            throw new PaymentChannelException(Kind.RETRYABLE, "SYSTEM_ERROR", 500, "x");
        });

        service.cancelPaid(BUYER, orderId, 1, KEY);

        assertThat(store.onlyRefund().status).isEqualTo("REQUESTED");
        assertThat(store.onlyRefund().acceptedAt).isNull();
    }

    // ---- 渠道结果 ----

    @Test
    void aSuccessfulFullRefundRefundsTheOrderOnceEvenIfReportedTwice() {
        UUID orderId = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, orderId, 1, KEY);
        Refund refund = store.onlyRefund();
        store.locks.clear();
        clock.now = NOW.plusSeconds(60);

        assertThat(service.apply(result(refund, RefundState.SUCCESS), "REFUND_NOTIFIED")).isEqualTo(Applied.APPLIED);
        int version = store.order(orderId).version;
        assertThat(service.apply(result(refund, RefundState.SUCCESS), "REFUND_QUERIED")).isEqualTo(Applied.ALREADY);

        Order order = store.order(orderId);
        assertThat(order.status).isEqualTo("REFUNDED");
        assertThat(order.refundedMinor).isEqualTo(PAID);
        assertThat(order.version).isEqualTo(version).isEqualTo(3);
        assertThat(refund.status).isEqualTo("SUCCEEDED");
        assertThat(refund.succeededAt).isEqualTo(SUCCEEDED_AT);
        assertThat(store.transitions).last().isEqualTo(
                new Transition(orderId, "REFUNDING", "REFUNDED", "REFUND_NOTIFIED", null, NOW.plusSeconds(60)));
        assertThat(store.transitions).hasSize(2);
        // 先锁订单，后锁退款单。
        assertThat(store.locks.subList(0, 2)).containsExactly("lockOrder:" + orderId, "lockRefund:" + refund.id);
    }

    // R6-5：退款到账时告诉买家一次（部分退款也说，带上这一笔的金额）；同一结果再报、关闭的退款都不说。
    @Test
    void aRefundThatArrivesIsReportedToTheBuyerOnce() {
        RecordingBuyerNotifier notifier = new RecordingBuyerNotifier();
        TradeRefundService telling = new TradeRefundService(store.refundMapper, store.orderMapper, orderService,
                () -> channel, TransactionOperations.withoutTransaction(), clock, this::nextSuffix,
                new TradeBuyerEvents(id -> new TradeBuyerEvents.Loaded(new TradeBuyerNotifier.OrderFacts(id, BUYER,
                        "MALL", null, "示例无人机专卖店", "示例无人机", 1), "EXPRESS"), notifier));
        UUID orderId = store.order(BUYER, "COMPLETED");
        telling.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 41_000L, "少发一件");
        Refund refund = store.onlyRefund();
        clock.now = NOW.plusSeconds(60);

        telling.apply(result(refund, RefundState.SUCCESS), "REFUND_NOTIFIED");
        telling.apply(result(refund, RefundState.SUCCESS), "REFUND_QUERIED");

        assertThat(notifier.told).containsExactly(
                "refunded " + orderId + " " + refund.id + " 41000 " + NOW.plusSeconds(60));
    }

    @Test
    void aPartialRefundReportedTwiceIsAddedOnce() {
        UUID orderId = store.order(BUYER, "COMPLETED");
        service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY, 41_000L, "少发一件");
        Refund refund = store.onlyRefund();

        service.apply(result(refund, RefundState.SUCCESS), "REFUND_NOTIFIED");
        service.apply(result(refund, RefundState.SUCCESS), "REFUND_NOTIFIED");

        assertThat(store.order(orderId).refundedMinor).isEqualTo(41_000);
        assertThat(store.order(orderId).status).isEqualTo("COMPLETED");
    }

    // 子商户号、支付单号、金额对不上：不改退款单和订单，等人工核对；之后对得上的结果照常处理。
    @ParameterizedTest
    @ValueSource(strings = {"sub merchant", "out_trade_no", "amount"})
    void aResultThatDoesNotMatchChangesNothing(String wrong) {
        UUID orderId = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, orderId, 1, KEY);
        Refund refund = store.onlyRefund();
        RefundQuery good = result(refund, RefundState.SUCCESS);
        RefundQuery bad = switch (wrong) {
            case "sub merchant" -> with(good, "1900000110", good.outTradeNo(), good.refundMinor());
            case "out_trade_no" -> with(good, good.subMchid(), "P20261116999999999999", good.refundMinor());
            case "amount" -> with(good, good.subMchid(), good.outTradeNo(), PAID - 1);
            default -> throw new IllegalArgumentException(wrong);
        };
        int version = store.order(orderId).version;

        for (RefundState state : RefundState.values()) {
            RefundQuery badInState = new RefundQuery(state, state.name(), bad.subMchid(), bad.outTradeNo(),
                    bad.outRefundNo(), bad.refundId(), bad.refundMinor(), bad.totalMinor(), bad.succeededAt());
            assertThat(service.apply(badInState, "REFUND_NOTIFIED")).as(state.name()).isEqualTo(Applied.MISMATCH);
        }

        assertThat(refund.status).isEqualTo("PROCESSING");
        assertThat(store.order(orderId).status).isEqualTo("REFUNDING");
        assertThat(store.order(orderId).version).isEqualTo(version);
        assertThat(store.order(orderId).refundedMinor).isZero();
        assertThat(service.apply(good, "REFUND_NOTIFIED")).isEqualTo(Applied.APPLIED);
    }

    // 结果里没有的字段不拿来核对；没有到账时间时按处理时间记。
    @Test
    void aResultMissingMerchantOrderOrAmountIsHeldAndAMissingTimeIsNow() {
        UUID orderId = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, orderId, 1, KEY);
        Refund refund = store.onlyRefund();
        Payment payment = store.paymentOf(orderId);
        clock.now = NOW.plusSeconds(300);

        // 和支付一样严格：子商户号、商户订单号、金额缺一项都不入账。
        for (RefundQuery sparse : List.of(
                new RefundQuery(RefundState.SUCCESS, "SUCCESS", null, payment.outTradeNo, refund.outRefundNo, null,
                        refund.amountMinor, null, null),
                new RefundQuery(RefundState.SUCCESS, "SUCCESS", SUB_MCHID, null, refund.outRefundNo, null,
                        refund.amountMinor, null, null),
                new RefundQuery(RefundState.SUCCESS, "SUCCESS", SUB_MCHID, payment.outTradeNo, refund.outRefundNo, null,
                        null, null, null))) {
            assertThat(service.apply(sparse, "REFUND_QUERIED")).isEqualTo(Applied.MISMATCH);
            assertThat(refund.status).isEqualTo("PROCESSING");
        }

        RefundQuery noTimeNoId = new RefundQuery(RefundState.SUCCESS, "SUCCESS", SUB_MCHID, payment.outTradeNo,
                refund.outRefundNo, null, refund.amountMinor, null, null);
        assertThat(service.apply(noTimeNoId, "REFUND_QUERIED")).isEqualTo(Applied.APPLIED);

        assertThat(refund.status).isEqualTo("SUCCEEDED");
        assertThat(refund.succeededAt).isEqualTo(NOW.plusSeconds(300));
        assertThat(refund.refundId).as("没给退款单号时保留受理时记下的").isEqualTo(channel.refundIdOf(refund.outRefundNo));
        assertThat(store.order(orderId).status).isEqualTo("REFUNDED");
    }

    @Test
    void aResultForAnOrderWithoutASucceededPaymentIsHeld() {
        UUID orderId = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, orderId, 1, KEY);
        Refund refund = store.onlyRefund();
        RefundQuery good = result(refund, RefundState.SUCCESS);
        store.paymentOf(orderId).status = "MISMATCH";

        assertThat(service.apply(good, "REFUND_NOTIFIED")).isEqualTo(Applied.MISMATCH);
        assertThat(refund.status).isEqualTo("PROCESSING");
    }

    @Test
    void anUnknownRefundNumberIsUnknownAndLocksNothing() {
        RefundQuery stranger = new RefundQuery(RefundState.SUCCESS, "SUCCESS", SUB_MCHID, "P20261116000000000001",
                "R20261119999999999999", "503", 1L, PAID, NOW);
        RefundQuery noNumber = new RefundQuery(RefundState.SUCCESS, "SUCCESS", SUB_MCHID, "P20261116000000000001",
                null, "503", 1L, PAID, NOW);

        assertThat(service.apply(stranger, "REFUND_NOTIFIED")).isEqualTo(Applied.UNKNOWN);
        assertThat(service.apply(noNumber, "REFUND_NOTIFIED")).isEqualTo(Applied.UNKNOWN);
        assertThat(store.locks).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = RefundState.class, names = {"PROCESSING", "NOT_FOUND", "OTHER"})
    void aResultWithoutAnOutcomeChangesNothing(RefundState state) {
        UUID orderId = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, orderId, 1, KEY);
        Refund refund = store.onlyRefund();
        int version = store.order(orderId).version;

        assertThat(service.apply(result(refund, state), "REFUND_QUERIED")).isEqualTo(Applied.NOT_FINAL);

        assertThat(refund.status).isEqualTo("PROCESSING");
        assertThat(store.order(orderId).version).isEqualTo(version);
        assertThat(store.transitions).hasSize(1);
    }

    // 已关闭的退款报成功、已成功的报关闭或异常：都不改，人工核对。同样的关闭再来一次是 ALREADY。
    @Test
    void aFinishedRefundDoesNotChangeItsOutcome() {
        UUID closedOrder = store.order(BUYER, "PAID");
        service.merchantRefund(MERCHANT, OPERATOR, closedOrder, KEY, 1_000L, "少发一件");
        Refund closed = store.refundByKey(closedOrder, KEY);
        assertThat(service.apply(result(closed, RefundState.CLOSED), "REFUND_NOTIFIED")).isEqualTo(Applied.APPLIED);
        assertThat(store.transitions).as("部分退款关闭不改订单").isEmpty();

        assertThat(service.apply(result(closed, RefundState.CLOSED), "REFUND_NOTIFIED")).isEqualTo(Applied.ALREADY);
        assertThat(service.apply(result(closed, RefundState.SUCCESS), "REFUND_NOTIFIED")).isEqualTo(Applied.MISMATCH);
        assertThat(service.apply(result(closed, RefundState.ABNORMAL), "REFUND_NOTIFIED")).isEqualTo(Applied.MISMATCH);
        assertThat(closed.status).isEqualTo("CLOSED");
        assertThat(store.order(closedOrder).refundedMinor).isZero();

        UUID succeededOrder = store.order(BUYER, "PAID");
        service.merchantRefund(MERCHANT, OPERATOR, succeededOrder, KEY, 1_000L, "少发一件");
        Refund succeeded = store.refundByKey(succeededOrder, KEY);
        service.apply(result(succeeded, RefundState.SUCCESS), "REFUND_NOTIFIED");

        assertThat(service.apply(result(succeeded, RefundState.CLOSED), "REFUND_NOTIFIED")).isEqualTo(Applied.MISMATCH);
        assertThat(service.apply(result(succeeded, RefundState.ABNORMAL), "REFUND_NOTIFIED")).isEqualTo(Applied.MISMATCH);
        assertThat(succeeded.status).isEqualTo("SUCCEEDED");
        assertThat(store.order(succeededOrder).refundedMinor).isEqualTo(1_000);
    }

    // 异常（等人工在微信商户平台处理）：全额退款的订单退款异常；同样的异常再来是 ALREADY；人工处理成功后转成功、订单已退款。
    @Test
    void anAbnormalRefundCanStillSucceedLater() {
        UUID orderId = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, orderId, 1, KEY);
        Refund refund = store.onlyRefund();

        assertThat(service.apply(result(refund, RefundState.ABNORMAL), "REFUND_QUERIED")).isEqualTo(Applied.APPLIED);
        assertThat(refund.status).isEqualTo("ABNORMAL");
        assertThat(store.order(orderId).status).isEqualTo("REFUND_FAILED");
        assertThat(service.apply(result(refund, RefundState.ABNORMAL), "REFUND_QUERIED")).isEqualTo(Applied.ALREADY);

        clock.now = NOW.plus(Duration.ofDays(1));
        assertThat(service.apply(result(refund, RefundState.SUCCESS), "REFUND_QUERIED")).isEqualTo(Applied.APPLIED);

        assertThat(refund.status).isEqualTo("SUCCEEDED");
        assertThat(store.order(orderId).status).isEqualTo("REFUNDED");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(PAID);
        assertThat(store.transitions).extracting(Transition::from, Transition::to, Transition::cause)
                .containsExactly(
                        tuple("PAID", "REFUNDING", "BUYER_CANCEL"),
                        tuple("REFUNDING", "REFUND_FAILED", "REFUND_QUERIED"),
                        tuple("REFUND_FAILED", "REFUNDED", "REFUND_QUERIED"));
    }

    // 异常的退款还占着钱：商家不能再把同一笔钱退一次；它后来人工处理成功时订单正常变成已退款，不会超出实付。
    @Test
    void anAbnormalRefundStillHoldsItsMoneySoTheSameMoneyIsNotRefundedTwice() {
        UUID orderId = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, orderId, 1, KEY);
        Refund refund = store.onlyRefund();
        service.apply(result(refund, RefundState.ABNORMAL), "REFUND_NOTIFIED");
        assertThat(store.order(orderId).status).isEqualTo("REFUND_FAILED");

        assertRefused(() -> service.merchantRefund(MERCHANT, OPERATOR, orderId, KEY_2, 1L, "再退一次"),
                Error.REFUND_EXCEEDS_PAID);
        assertThat(store.refunds).hasSize(1);

        assertThat(service.apply(result(refund, RefundState.SUCCESS), "REFUND_QUERIED")).isEqualTo(Applied.APPLIED);
        assertThat(store.order(orderId).status).isEqualTo("REFUNDED");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(PAID);
    }

    // 兜底：万一到账会让已退超过实付（按规则走不到），不入账，人工核对。
    @Test
    void aRefundThatWouldExceedThePaidAmountIsHeld() {
        UUID orderId = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, orderId, 1, KEY);
        Refund refund = store.onlyRefund();
        store.order(orderId).refundedMinor = 1;

        assertThat(service.apply(result(refund, RefundState.SUCCESS), "REFUND_NOTIFIED")).isEqualTo(Applied.MISMATCH);
        assertThat(refund.status).isEqualTo("PROCESSING");
        assertThat(store.order(orderId).refundedMinor).isEqualTo(1);
    }

    // 异常之后关闭：退款单结束在关闭；订单已经是退款异常，不再记迁移。
    @Test
    void anAbnormalRefundThatIsClosedLaterStaysFailed() {
        UUID orderId = store.order(BUYER, "PAID");
        service.cancelPaid(BUYER, orderId, 1, KEY);
        Refund refund = store.onlyRefund();
        service.apply(result(refund, RefundState.ABNORMAL), "REFUND_QUERIED");

        assertThat(service.apply(result(refund, RefundState.CLOSED), "REFUND_QUERIED")).isEqualTo(Applied.APPLIED);

        assertThat(refund.status).isEqualTo("CLOSED");
        assertThat(store.order(orderId).status).isEqualTo("REFUND_FAILED");
        assertThat(store.transitions).hasSize(2);
    }

    // ---- 定时兜底 ----

    @Test
    void pollingResubmitsQueriesAndTouchesTheRightRefunds() {
        Refund requestedOld = store.refund(store.order(BUYER, "PAID"), "REQUESTED", 1_000, NOW.minusSeconds(60));
        Refund requestedYoung = store.refund(store.order(BUYER, "PAID"), "REQUESTED", 1_000, NOW.minusSeconds(59));
        Refund processingOld = store.refund(store.order(BUYER, "PAID"), "PROCESSING", 1_000, NOW.minusSeconds(60));
        Refund processingYoung = store.refund(store.order(BUYER, "PAID"), "PROCESSING", 1_000, NOW.minusSeconds(59));
        Refund processingPending = store.refund(store.order(BUYER, "PAID"), "PROCESSING", 1_000, NOW.minusSeconds(120));
        Refund abnormalOld = store.refund(store.order(BUYER, "PAID"), "ABNORMAL", 1_000, NOW.minusSeconds(3600));
        Refund abnormalYoung = store.refund(store.order(BUYER, "PAID"), "ABNORMAL", 1_000, NOW.minusSeconds(3599));
        Refund succeeded = store.refund(store.order(BUYER, "PAID"), "SUCCEEDED", 1_000, NOW.minus(Duration.ofDays(1)));
        Refund closed = store.refund(store.order(BUYER, "PAID"), "CLOSED", 1_000, NOW.minus(Duration.ofDays(1)));
        channel.queryDefault = no -> {
            Refund refund = store.refundByOutRefundNo(no);
            if (refund == processingOld) {
                return result(refund, RefundState.SUCCESS);
            }
            return result(refund, refund == abnormalOld ? RefundState.ABNORMAL : RefundState.PROCESSING);
        };

        assertThat(service.pollRefunds()).isEqualTo(1);

        assertThat(store.submitQueries).containsExactly(List.of(NOW.minusSeconds(60), 50));
        assertThat(store.queryQueries).containsExactly(List.of(NOW.minusSeconds(60), NOW.minusSeconds(3600), 50));
        // 先重提，再按上次更新时间从早到晚查。
        assertThat(channel.calls).containsExactly(
                "refund:" + requestedOld.outRefundNo,
                "query:" + abnormalOld.outRefundNo,
                "query:" + processingPending.outRefundNo,
                "query:" + processingOld.outRefundNo);
        assertThat(channel.refundQueries).allSatisfy(query -> assertThat(query).startsWith(SUB_MCHID + "/"));
        assertThat(requestedOld.status).isEqualTo("PROCESSING");
        assertThat(processingOld.status).isEqualTo("SUCCEEDED");
        // 查过没结论的只更新时间，免得下一轮马上又查。
        assertThat(processingPending.updatedAt).isEqualTo(NOW);
        assertThat(abnormalOld.updatedAt).isEqualTo(NOW);
        assertThat(abnormalOld.status).isEqualTo("ABNORMAL");
        for (Refund untouched : List.of(requestedYoung, processingYoung, abnormalYoung, succeeded, closed)) {
            assertThat(untouched.updatedAt).isBefore(NOW);
        }
        assertThat(requestedYoung.status).isEqualTo("REQUESTED");
    }

    @Test
    void oneFailureDuringPollingDoesNotStopTheOthers() {
        Refund brokenSubmit = store.refund(store.order(BUYER, "PAID"), "REQUESTED", 1_000, NOW.minusSeconds(300));
        Refund goodSubmit = store.refund(store.order(BUYER, "PAID"), "REQUESTED", 1_000, NOW.minusSeconds(200));
        Refund unreachable = store.refund(store.order(BUYER, "PAID"), "PROCESSING", 1_000, NOW.minusSeconds(300));
        Refund broken = store.refund(store.order(BUYER, "PAID"), "PROCESSING", 1_000, NOW.minusSeconds(250));
        Refund good = store.refund(store.order(BUYER, "PAID"), "PROCESSING", 1_000, NOW.minusSeconds(200));
        channel.refundDefault = request -> {
            if (request.outRefundNo().equals(brokenSubmit.outRefundNo)) {
                throw new IllegalStateException("bug in the channel adapter");
            }
            return channel.accepted(request);
        };
        channel.queryDefault = no -> {
            if (no.equals(unreachable.outRefundNo)) {
                throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
            }
            if (no.equals(broken.outRefundNo)) {
                throw new IllegalStateException("bug in the channel adapter");
            }
            return result(store.refundByOutRefundNo(no), RefundState.SUCCESS);
        };

        assertThat(service.pollRefunds()).isEqualTo(1);

        assertThat(brokenSubmit.status).isEqualTo("REQUESTED");
        assertThat(goodSubmit.status).isEqualTo("PROCESSING");
        assertThat(unreachable.status).isEqualTo("PROCESSING");
        // 查不通的只更新时间，排到后面：一直查不通的几笔不会占满下一批。
        assertThat(unreachable.updatedAt).isEqualTo(NOW);
        assertThat(store.touched).contains(unreachable.id);
        assertThat(broken.status).isEqualTo("PROCESSING");
        assertThat(good.status).isEqualTo("SUCCEEDED");
    }

    // 一整批都查不通（比如某个子商户的配置坏了）：它们被排到后面，下一轮先查别的退款，不会一直卡住。
    @Test
    void refundsThatCannotBeQueriedDoNotHoldUpTheOthers() {
        List<Refund> unreachable = new ArrayList<>();
        for (int i = 0; i < TradeRefundService.BATCH; i++) {
            unreachable.add(store.refund(store.order(BUYER, "PAID"), "PROCESSING", 1_000, NOW.minusSeconds(600 - i)));
        }
        Refund waiting = store.refund(store.order(BUYER, "PAID"), "PROCESSING", 1_000, NOW.minusSeconds(200));
        channel.queryDefault = no -> {
            if (!no.equals(waiting.outRefundNo)) {
                throw new PaymentChannelException(Kind.RETRYABLE, "NETWORK", 0, "timeout");
            }
            return result(waiting, RefundState.SUCCESS);
        };

        assertThat(service.pollRefunds()).isZero();
        assertThat(channel.refundQueries).hasSize(TradeRefundService.BATCH);
        assertThat(waiting.status).isEqualTo("PROCESSING");
        assertThat(unreachable).allSatisfy(refund -> assertThat(refund.updatedAt).isEqualTo(NOW));

        clock.now = NOW.plusSeconds(61);
        assertThat(service.pollRefunds()).isEqualTo(1);
        assertThat(channel.refundQueries.get(TradeRefundService.BATCH)).endsWith("/" + waiting.outRefundNo);
        assertThat(waiting.status).isEqualTo("SUCCEEDED");
    }

    @Test
    void pollingWithoutAChannelDoesNothing() {
        store.refund(store.order(BUYER, "PAID"), "REQUESTED", 1_000, NOW.minusSeconds(600));
        configured = null;

        assertThat(service.pollRefunds()).isZero();

        assertThat(store.submitQueries).isEmpty();
        assertThat(store.queryQueries).isEmpty();
    }

    // ---- 辅助 ----

    private String nextSuffix() {
        return suffixes.isEmpty() ? String.format("%012d", ++suffixCounter) : suffixes.poll();
    }

    /** 渠道对这笔退款的一次结果（回调或查询），各字段都和退款单、支付单对得上。 */
    private RefundQuery result(Refund refund, RefundState state) {
        Payment payment = store.paymentOf(refund.orderId);
        return new RefundQuery(state, state.name(), SUB_MCHID, payment.outTradeNo, refund.outRefundNo,
                channel.refundIdOf(refund.outRefundNo), refund.amountMinor, PAID,
                state == RefundState.SUCCESS ? SUCCEEDED_AT : null);
    }

    private static RefundQuery with(RefundQuery query, String subMchid, String outTradeNo, Long refundMinor) {
        return new RefundQuery(query.state(), query.rawState(), subMchid, outTradeNo, query.outRefundNo(),
                query.refundId(), refundMinor, query.totalMinor(), query.succeededAt());
    }

    private static void assertRefused(Runnable action, Error error) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TradeException.class, refused -> assertThat(refused.error()).isEqualTo(error));
    }

    // ---- 假渠道与时钟 ----

    /** 退款的提交、查询按顺序编排；没编排的查询让测试失败（AssertionError 不会被服务吞掉）。付款的方法用不到。 */
    private static final class FakeChannel implements PaymentChannel {

        final List<String> calls = new ArrayList<>();
        final List<RefundRequest> refundRequests = new ArrayList<>();
        final List<String> refundQueries = new ArrayList<>();
        final Deque<Function<RefundRequest, RefundAccepted>> refundAnswers = new ArrayDeque<>();
        Function<RefundRequest, RefundAccepted> refundDefault = this::accepted;
        Function<String, RefundQuery> queryDefault;

        @Override
        public String providerId() {
            return "1900000001";
        }

        @Override
        public String createAppPayment(PrepayRequest request) {
            throw new AssertionError("refunds do not create payments");
        }

        @Override
        public AppPayParams appPayParams(String prepayId) {
            throw new AssertionError("refunds do not sign payments");
        }

        @Override
        public PaymentQuery queryPayment(String subMchid, String outTradeNo) {
            throw new AssertionError("refunds do not query payments");
        }

        @Override
        public void closePayment(String subMchid, String outTradeNo) {
            throw new AssertionError("refunds do not close payments");
        }

        @Override
        public RefundAccepted applyRefund(RefundRequest request) {
            calls.add("refund:" + request.outRefundNo());
            refundRequests.add(request);
            Function<RefundRequest, RefundAccepted> answer = refundAnswers.poll();
            return (answer == null ? refundDefault : answer).apply(request);
        }

        @Override
        public RefundQuery queryRefund(String subMchid, String outRefundNo) {
            calls.add("query:" + outRefundNo);
            refundQueries.add(subMchid + "/" + outRefundNo);
            if (queryDefault == null) {
                throw new AssertionError("unexpected query of " + outRefundNo);
            }
            return queryDefault.apply(outRefundNo);
        }

        @Override
        public ChannelNotification parseNotification(Map<String, String> headers, String body) {
            throw new AssertionError("notifications are handled by the payment service");
        }

        @Override
        public TradeBill tradeBill(String subMchid, java.time.LocalDate billDate) {
            throw new AssertionError("refunds do not download bills");
        }

        /** 微信退款单号由退款单号推出来，每笔一个。 */
        String refundIdOf(String outRefundNo) {
            return "503" + outRefundNo.substring(1);
        }

        RefundAccepted accepted(RefundRequest request) {
            return new RefundAccepted(refundIdOf(request.outRefundNo()), request.outRefundNo(), request.refundMinor(),
                    NOW);
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
        UUID merchantId = MERCHANT;
        String status;
        long payableMinor = PAID;
        long paidMinor;
        long refundedMinor;
        Instant payBefore;
        Instant paidAt;
        Instant closedAt;
        Instant createdAt;
        int version;

        /** V001 trade_order 的检查约束。 */
        void check() {
            if (!Set.of("PENDING_PAYMENT", "PAID", "SHIPPED", "COMPLETED", "CLOSED", "REFUNDING", "REFUNDED",
                    "REFUND_FAILED").contains(status)
                    || (paidMinor != 0 && paidMinor != payableMinor)
                    || refundedMinor < 0
                    || refundedMinor > paidMinor
                    || ("PENDING_PAYMENT".equals(status)) != (paidAt == null && closedAt == null)
                    || ("CLOSED".equals(status)) != (closedAt != null)
                    || (!Set.of("PENDING_PAYMENT", "CLOSED").contains(status) && paidAt == null)) {
                throw new IllegalStateException("trade_order check violated: " + status + " refunded " + refundedMinor);
            }
        }

        TradeOrderMapper.OrderRow toOrderRow() {
            return new TradeOrderMapper.OrderRow(id, orderNo, buyerId, merchantId, "LOCAL_STORE", "示例机器人商家",
                    "MALL", null, status, "EXPRESS", payableMinor, 0, payableMinor, paidMinor, refundedMinor, "CNY", null,
                    payBefore, paidAt, null, null, closedAt, createdAt, version);
        }

        TradeRefundMapper.RefundOrderRow toRefundOrderRow() {
            return new TradeRefundMapper.RefundOrderRow(id, buyerId, merchantId, status, SUB_MCHID, paidMinor,
                    refundedMinor, paidAt, version);
        }
    }

    private static final class Payment {
        UUID id;
        UUID orderId;
        String outTradeNo;
        String status;
    }

    private static final class Refund {
        UUID id;
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
        Instant createdAt;
        Instant updatedAt;
        Instant succeededAt;
        Instant acceptedAt;
        int version;

        boolean open() {
            return "REQUESTED".equals(status) || "PROCESSING".equals(status);
        }

        TradeRefundMapper.RefundRow toRow() {
            return new TradeRefundMapper.RefundRow(id, orderId, paymentId, outRefundNo, amountMinor, reason, initiator,
                    status, refundId, idempotencyKey, createdAt, succeededAt);
        }
    }

    /**
     * 订单、支付单、退款单和状态迁移的一份内存数据，两个 mapper 共用。写入时检查 V001 / V002 的约束，
     * 锁语句记到 locks 里，好检查加锁顺序。
     */
    private final class Store {

        final Map<UUID, Order> orders = new LinkedHashMap<>();
        final List<Payment> payments = new ArrayList<>();
        final List<Refund> refunds = new ArrayList<>();
        final List<Transition> transitions = new ArrayList<>();
        final List<String> locks = new ArrayList<>();
        final List<UUID> touched = new ArrayList<>();
        final List<List<Object>> submitQueries = new ArrayList<>();
        final List<List<Object>> queryQueries = new ArrayList<>();
        private long sequence;

        final TradeRefundMapper refundMapper = new RefundMapper();
        final TradeOrderMapper orderMapper = new OrderMapper();

        /** 本商家卖给买家的一张单；付过款的带一张成功的支付单（实付 1410 元）。 */
        UUID order(UUID buyerId, String status) {
            Order order = new Order();
            order.id = UUID.randomUUID();
            order.orderNo = "MO20261116" + String.format("%08d", ++sequence);
            order.buyerId = buyerId;
            order.status = status;
            order.createdAt = PAID_AT.minusSeconds(60);
            order.payBefore = order.createdAt.plus(Duration.ofMinutes(30));
            if ("CLOSED".equals(status)) {
                order.closedAt = order.payBefore;
                order.version = 1;
            } else if (!"PENDING_PAYMENT".equals(status)) {
                order.paidAt = PAID_AT;
                order.paidMinor = PAID;
                order.version = 1;
                Payment payment = new Payment();
                payment.id = UUID.randomUUID();
                payment.orderId = order.id;
                payment.outTradeNo = "P20261116" + String.format("%012d", ++sequence);
                payment.status = "SUCCEEDED";
                payments.add(payment);
            }
            if ("REFUNDED".equals(status)) {
                order.refundedMinor = PAID;
            }
            order.check();
            orders.put(order.id, order);
            return order.id;
        }

        /** 直接放一笔某个状态的商家退款（模拟以前的退款）；到账的计入订单的已退金额。 */
        Refund refund(UUID orderId, String status, long amountMinor, Instant updatedAt) {
            Refund refund = new Refund();
            refund.id = UUID.randomUUID();
            refund.orderId = orderId;
            refund.paymentId = paymentOf(orderId).id;
            refund.outRefundNo = "R20261118" + String.format("%012d", 900_000_000_000L + ++sequence);
            refund.amountMinor = amountMinor;
            refund.reason = "以前的退款";
            refund.initiator = "MERCHANT";
            refund.requestedBy = OPERATOR;
            refund.status = status;
            refund.idempotencyKey = UUID.randomUUID();
            refund.createdAt = updatedAt;
            refund.updatedAt = updatedAt;
            if (Set.of("PROCESSING", "SUCCEEDED", "ABNORMAL").contains(status)) {
                refund.refundId = channel.refundIdOf(refund.outRefundNo);
            }
            if ("SUCCEEDED".equals(status)) {
                refund.succeededAt = updatedAt;
                order(orderId).refundedMinor += amountMinor;
                order(orderId).check();
            }
            refunds.add(refund);
            checkRefunds();
            return refund;
        }

        Order order(UUID id) {
            return Objects.requireNonNull(orders.get(id), "order " + id);
        }

        Payment paymentOf(UUID orderId) {
            return payments.stream().filter(payment -> payment.orderId.equals(orderId)).findFirst().orElseThrow();
        }

        Refund onlyRefund() {
            assertThat(refunds).hasSize(1);
            return refunds.getFirst();
        }

        Refund refundByKey(UUID orderId, UUID key) {
            return refunds.stream()
                    .filter(refund -> refund.orderId.equals(orderId) && refund.idempotencyKey.equals(key))
                    .findFirst().orElseThrow();
        }

        Refund refundByOutRefundNo(String outRefundNo) {
            return refunds.stream().filter(refund -> refund.outRefundNo.equals(outRefundNo)).findFirst().orElseThrow();
        }

        private Refund refundById(UUID id) {
            return refunds.stream().filter(refund -> refund.id.equals(id)).findFirst().orElseThrow();
        }

        /** trade_refund 的唯一约束和检查约束。 */
        void checkRefunds() {
            Set<String> outRefundNos = new HashSet<>();
            Set<String> refundIds = new HashSet<>();
            Set<List<UUID>> keys = new HashSet<>();
            for (Refund refund : refunds) {
                if (!refund.outRefundNo.matches("^[0-9A-Za-z_|*@-]{6,64}$")
                        || refund.amountMinor <= 0
                        || refund.reason == null || refund.reason.length() > 80
                        || !Set.of("BUYER_CANCEL", "MERCHANT").contains(refund.initiator)
                        || refund.requestedBy == null
                        || !Set.of("REQUESTED", "PROCESSING", "SUCCEEDED", "CLOSED", "ABNORMAL").contains(refund.status)
                        || ("SUCCEEDED".equals(refund.status)) != (refund.succeededAt != null)
                        || (refund.refundId != null && refund.refundId.length() > 32)) {
                    throw new IllegalStateException("trade_refund check violated");
                }
                if (!outRefundNos.add(refund.outRefundNo)) {
                    throw new IllegalStateException("duplicate out_refund_no");
                }
                if (refund.refundId != null && !refundIds.add(refund.refundId)) {
                    throw new IllegalStateException("duplicate refund_id");
                }
                if (!keys.add(List.of(refund.orderId, refund.idempotencyKey))) {
                    throw new IllegalStateException("duplicate (order_id, idempotency_key)");
                }
            }
        }

        private final class RefundMapper implements TradeRefundMapper {

            @Override
            public RefundOrderRow lockBuyerOrder(UUID buyerId, UUID orderId) {
                locks.add("lockBuyerOrder:" + orderId);
                Order order = orders.get(orderId);
                return order == null || !order.buyerId.equals(buyerId) ? null : order.toRefundOrderRow();
            }

            @Override
            public RefundOrderRow lockMerchantOrder(UUID merchantId, UUID orderId) {
                locks.add("lockMerchantOrder:" + orderId);
                Order order = orders.get(orderId);
                return order == null || !order.merchantId.equals(merchantId) ? null : order.toRefundOrderRow();
            }

            @Override
            public RefundOrderRow lockOrder(UUID orderId) {
                locks.add("lockOrder:" + orderId);
                Order order = orders.get(orderId);
                return order == null ? null : order.toRefundOrderRow();
            }

            @Override
            public int markOrderRefunding(UUID orderId, Instant at) {
                Order order = order(orderId);
                if (!Set.of("PAID", "SHIPPED", "COMPLETED", "REFUND_FAILED").contains(order.status)) {
                    return 0;
                }
                order.status = "REFUNDING";
                order.version++;
                order.check();
                return 1;
            }

            @Override
            public int addRefunded(UUID orderId, long amountMinor, String status, Instant at) {
                Order order = order(orderId);
                order.refundedMinor += amountMinor;
                order.status = status;
                order.version++;
                // refunded_minor <= paid_minor：多记一次就违反约束。
                order.check();
                return 1;
            }

            @Override
            public int changeOrderStatus(UUID orderId, String fromStatus, String status, Instant at) {
                Order order = order(orderId);
                if (!order.status.equals(fromStatus)) {
                    return 0;
                }
                order.status = status;
                order.version++;
                order.check();
                return 1;
            }

            @Override
            public PaidPaymentRow findSucceededPayment(UUID orderId) {
                return payments.stream()
                        .filter(payment -> payment.orderId.equals(orderId) && "SUCCEEDED".equals(payment.status))
                        .findFirst()
                        .map(payment -> new PaidPaymentRow(payment.id, payment.outTradeNo, SUB_MCHID,
                                order(orderId).payableMinor))
                        .orElse(null);
            }

            @Override
            public long sumOpenRefunds(UUID orderId) {
                return refunds.stream()
                        .filter(refund -> refund.orderId.equals(orderId) && refund.open())
                        .mapToLong(refund -> refund.amountMinor)
                        .sum();
            }

            @Override
            public long sumReservedRefunds(UUID orderId) {
                return refunds.stream()
                        .filter(refund -> refund.orderId.equals(orderId)
                                && (refund.open() || "ABNORMAL".equals(refund.status)))
                        .mapToLong(refund -> refund.amountMinor)
                        .sum();
            }

            @Override
            public int countRefunds(UUID orderId) {
                return (int) refunds.stream().filter(refund -> refund.orderId.equals(orderId)).count();
            }

            @Override
            public RefundRow findRefundByKey(UUID orderId, UUID idempotencyKey) {
                return refunds.stream()
                        .filter(refund -> refund.orderId.equals(orderId) && refund.idempotencyKey.equals(idempotencyKey))
                        .findFirst().map(Refund::toRow).orElse(null);
            }

            @Override
            public RefundRow lockRefund(UUID refundId) {
                locks.add("lockRefund:" + refundId);
                return refunds.stream().filter(refund -> refund.id.equals(refundId))
                        .findFirst().map(Refund::toRow).orElse(null);
            }

            @Override
            public RefundRow findRefundByOutRefundNo(String outRefundNo) {
                return refunds.stream().filter(refund -> refund.outRefundNo.equals(outRefundNo))
                        .findFirst().map(Refund::toRow).orElse(null);
            }

            @Override
            public int countOutRefundNo(String outRefundNo) {
                return refunds.stream().anyMatch(refund -> refund.outRefundNo.equals(outRefundNo)) ? 1 : 0;
            }

            @Override
            public UUID insertRefund(UUID orderId, UUID paymentId, String outRefundNo, long amountMinor, String reason,
                                     String initiator, UUID requestedBy, UUID idempotencyKey, Instant at) {
                Refund refund = new Refund();
                refund.id = UUID.randomUUID();
                refund.orderId = order(orderId).id;
                refund.paymentId = paymentId;
                refund.outRefundNo = outRefundNo;
                refund.amountMinor = amountMinor;
                refund.reason = reason;
                refund.initiator = initiator;
                refund.requestedBy = requestedBy;
                refund.idempotencyKey = idempotencyKey;
                refund.createdAt = at;
                refund.updatedAt = at;
                refunds.add(refund);
                checkRefunds();
                return refund.id;
            }

            @Override
            public int markProcessing(UUID id, String refundId, Instant acceptedAt, Instant at) {
                Refund refund = refundById(id);
                if (!"REQUESTED".equals(refund.status)) {
                    return 0;
                }
                refund.status = "PROCESSING";
                refund.refundId = refundId;
                // accepted_at = COALESCE(accepted_at, #{acceptedAt})
                if (refund.acceptedAt == null) {
                    refund.acceptedAt = acceptedAt;
                }
                refund.version++;
                refund.updatedAt = at;
                checkRefunds();
                return 1;
            }

            @Override
            public int markSucceeded(UUID id, String refundId, Instant succeededAt, Instant at) {
                Refund refund = refundById(id);
                if (!Set.of("REQUESTED", "PROCESSING", "ABNORMAL").contains(refund.status)) {
                    return 0;
                }
                refund.status = "SUCCEEDED";
                if (refundId != null) {
                    refund.refundId = refundId;
                }
                refund.succeededAt = succeededAt;
                // accepted_at = COALESCE(accepted_at, #{succeededAt})
                if (refund.acceptedAt == null) {
                    refund.acceptedAt = succeededAt;
                }
                refund.version++;
                refund.updatedAt = at;
                checkRefunds();
                return 1;
            }

            @Override
            public int markFinished(UUID id, String status, Instant at) {
                Refund refund = refundById(id);
                if (!Set.of("REQUESTED", "PROCESSING", "ABNORMAL").contains(refund.status)
                        || refund.status.equals(status)) {
                    return 0;
                }
                refund.status = status;
                refund.version++;
                refund.updatedAt = at;
                checkRefunds();
                return 1;
            }

            @Override
            public int touch(UUID id, Instant at) {
                touched.add(id);
                refundById(id).updatedAt = at;
                return 1;
            }

            @Override
            public List<RefundRow> findRefundsToSubmit(Instant before, int limit) {
                submitQueries.add(List.of(before, limit));
                return refunds.stream()
                        .filter(refund -> "REQUESTED".equals(refund.status) && !refund.updatedAt.isAfter(before))
                        .sorted(Comparator.comparing((Refund refund) -> refund.updatedAt)
                                .thenComparing(refund -> refund.id))
                        .limit(limit)
                        .map(Refund::toRow)
                        .toList();
            }

            @Override
            public List<RefundRow> findRefundsToQuery(Instant processingBefore, Instant abnormalBefore, int limit) {
                queryQueries.add(List.of(processingBefore, abnormalBefore, limit));
                return refunds.stream()
                        .filter(refund -> ("PROCESSING".equals(refund.status) && !refund.updatedAt.isAfter(processingBefore))
                                || ("ABNORMAL".equals(refund.status) && !refund.updatedAt.isAfter(abnormalBefore)))
                        .sorted(Comparator.comparing((Refund refund) -> refund.updatedAt)
                                .thenComparing(refund -> refund.id))
                        .limit(limit)
                        .map(Refund::toRow)
                        .toList();
            }
        }

        /** 退款服务只用它记状态迁移；返回的订单由 TradeOrderService 经它读。其余的方法退款用不到。 */
        private final class OrderMapper implements TradeOrderMapper {
        // ---- S5-7、S5-8（交易迁移 V013）：付款主体、企业订单、注销 ----
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


            /** V002 放宽后的 trade_state_transition.cause。 */
            private static final Set<String> CAUSES = Set.of("SUBMIT", "PAYMENT_NOTIFIED", "PAYMENT_QUERIED",
                    "BUYER_CANCEL", "TIMEOUT", "SHIPMENT", "PICKUP", "RECEIPT", "AUTO_RECEIPT", "REFUND_NOTIFIED",
                    "REFUND_QUERIED", "MERCHANT_REFUND", "REFUND_REJECTED");

            @Override
            public int insertTransition(UUID orderId, String fromStatus, String toStatus, String cause, UUID actorId,
                                        Instant at) {
                if (!CAUSES.contains(cause)) {
                    throw new IllegalStateException("trade_state_transition cause check violated: " + cause);
                }
                if (!order(orderId).status.equals(toStatus)) {
                    throw new AssertionError("transition to " + toStatus + " but the order is " + order(orderId).status);
                }
                transitions.add(new Transition(orderId, fromStatus, toStatus, cause, actorId, at));
                return 1;
            }

            @Override
            public OrderRow findBuyerOrder(UUID buyerId, UUID orderId) {
                Order order = orders.get(orderId);
                return order == null || !order.buyerId.equals(buyerId) ? null : order.toOrderRow();
            }

            @Override
            public List<LineRow> findLines(List<UUID> orderIds) {
                return List.of();
            }

            @Override
            public List<DeliveryRow> findDeliveries(List<UUID> orderIds) {
                return List.of();
            }

            @Override
            public List<RefundRow> findRefunds(List<UUID> orderIds) {
                return refunds.stream()
                        .filter(refund -> orderIds.contains(refund.orderId))
                        .sorted(Comparator.comparing((Refund refund) -> refund.createdAt)
                                .thenComparing(refund -> refund.id))
                        .map(refund -> new RefundRow(refund.id, refund.orderId, refund.amountMinor, refund.reason,
                                refund.status, refund.createdAt, refund.succeededAt))
                        .toList();
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
            public int closeUnpaid(UUID orderId, int version, Instant at) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ExpiredRow> lockExpired(Instant now, int limit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int countOpenPayments(UUID orderId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OrderRow lockBuyerOrder(UUID buyerId, UUID orderId) {
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
    }
}
