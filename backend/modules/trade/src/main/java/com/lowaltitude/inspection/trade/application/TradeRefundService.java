package com.lowaltitude.inspection.trade.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundAccepted;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundRequest;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeRefundView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper.PaidPaymentRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper.RefundOrderRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper.RefundRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 退款（E05，规则见 docs/redesign/03-App实现任务计划.md §19.7）：
 * <ul>
 *   <li>买家取消已付款、未发货（未自提）的订单：把还没退的钱全退，订单进入退款中；</li>
 *   <li>商家受权接口发起退款：可部分退，同一订单最多 50 次，合计不超过实付，付款后一年内；
 *       退的是剩下的全部时订单进入退款中，否则订单状态不变；</li>
 *   <li>结果只认渠道的退款回调或主动查询：到账才累加已退金额，全退完订单变成已退款；
 *       全额退款失败或异常时订单变成“退款异常”，人工处理。</li>
 * </ul>
 * 调渠道时不占着数据库锁；渠道不通或限频时退款单留在“已建”，定时任务用同一个退款单号重新提交（微信保证同一单号只退一次）。
 */
public class TradeRefundService {

    static final int MAX_REFUNDS_PER_ORDER = 50;
    static final Duration REFUND_WINDOW = Duration.ofDays(365);
    /** 已建未提交的多久后重新提交、处理中的多久查一次。 */
    static final Duration RETRY_AFTER = Duration.ofMinutes(1);
    /** 异常的（等人工在微信商户平台处理）多久查一次。 */
    static final Duration ABNORMAL_RECHECK = Duration.ofHours(1);
    static final int BATCH = 50;
    static final int MAX_REASON = 80;
    static final String BUYER_CANCEL_REASON = "买家取消订单";
    /**
     * 渠道拒绝退款时，只有这些错误码算确定的业务拒绝（余额不足、用户账户异常、请求或参数不对、原交易不存在），
     * 退款单就此关闭。其余的拒绝（签名错、无权限等）多半是配置或证书的问题，修好后同一个退款单号还能再提，
     * 所以留在“已建”等定时任务重试。具体错误码联调时确认。
     */
    static final Set<String> DEFINITE_REFUND_REJECTIONS = Set.of(
            "NOT_ENOUGH", "USER_ACCOUNT_ABNORMAL", "INVALID_REQUEST", "PARAM_ERROR", "RESOURCE_NOT_EXISTS");

    private static final Logger log = LoggerFactory.getLogger(TradeRefundService.class);
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TradeRefundMapper refunds;
    private final TradeOrderMapper orders;
    private final TradeOrderService orderService;
    private final Supplier<PaymentChannel> channels;
    private final TransactionOperations tx;
    private final Clock clock;
    private final Supplier<String> outRefundNoSuffix;
    private final TradeBuyerEvents buyerEvents;

    TradeRefundService(
            TradeRefundMapper refunds,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            Supplier<PaymentChannel> channels,
            TransactionOperations tx,
            Clock clock,
            Supplier<String> outRefundNoSuffix
    ) {
        this(refunds, orders, orderService, channels, tx, clock, outRefundNoSuffix, TradeBuyerEvents.none());
    }

    /** @param buyerEvents 退款到账时告诉买家（R6-5） */
    TradeRefundService(
            TradeRefundMapper refunds,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            Supplier<PaymentChannel> channels,
            TransactionOperations tx,
            Clock clock,
            Supplier<String> outRefundNoSuffix,
            TradeBuyerEvents buyerEvents
    ) {
        this.refunds = refunds;
        this.orders = orders;
        this.orderService = orderService;
        this.channels = channels;
        this.tx = tx;
        this.clock = clock;
        this.outRefundNoSuffix = outRefundNoSuffix;
        this.buyerEvents = buyerEvents;
    }

    // ---- 买家取消已付款的订单 ----

    /**
     * 已付款、未发货（未自提）的订单取消即退款：退还没退的全部，订单进入退款中。
     * 同一个幂等键重放、或订单已在退款中 / 已退款时，原样返回订单。
     */
    public TradeOrderView cancelPaid(UUID buyerId, UUID orderId, int version, UUID idempotencyKey) {
        if (buyerId == null || orderId == null || idempotencyKey == null) {
            throw new IllegalArgumentException("buyer, order and idempotency key are required");
        }
        requireChannel();
        UUID refundId = tx.execute(status -> {
            Instant now = clock.instant();
            RefundOrderRow order = refunds.lockBuyerOrder(buyerId, orderId);
            if (order == null) {
                throw new TradeException(Error.NOT_FOUND);
            }
            RefundRow replayed = refunds.findRefundByKey(orderId, idempotencyKey);
            if (replayed != null) {
                if (!"BUYER_CANCEL".equals(replayed.initiator())) {
                    throw new TradeException(Error.IDEMPOTENCY_CONFLICT);
                }
                return replayed.id();
            }
            if (TradeOrderStatus.REFUNDING.name().equals(order.status())
                    || TradeOrderStatus.REFUNDED.name().equals(order.status())) {
                return null;
            }
            if (!TradeOrderStatus.PAID.name().equals(order.status())) {
                throw new TradeException(Error.ORDER_STATE_INVALID);
            }
            if (order.version() != version) {
                throw new TradeException(Error.VERSION_CONFLICT);
            }
            PaidPaymentRow payment = requirePayment(order);
            long refundable = refundable(order);
            if (refundable <= 0) {
                throw new TradeException(Error.ORDER_STATE_INVALID);
            }
            requireWithinLimits(order, now);
            UUID created = refunds.insertRefund(orderId, payment.id(), newOutRefundNo(now), refundable,
                    BUYER_CANCEL_REASON, "BUYER_CANCEL", buyerId, idempotencyKey, now);
            refunds.markOrderRefunding(orderId, now);
            orders.insertTransition(orderId, order.status(), TradeOrderStatus.REFUNDING.name(), "BUYER_CANCEL", buyerId, now);
            return created;
        });
        if (refundId != null) {
            submit(refundId);
        }
        return orderService.read(buyerId, orderId);
    }

    // ---- 商家退款 ----

    /**
     * 商家受权接口发起退款。同一个幂等键、同样的金额和原因重放返回同一笔退款；换了内容拒绝。
     */
    public TradeRefundView merchantRefund(
            UUID merchantId,
            UUID operatorId,
            UUID orderId,
            UUID idempotencyKey,
            Long amountMinor,
            String reason
    ) {
        if (merchantId == null || operatorId == null || orderId == null || idempotencyKey == null) {
            throw new IllegalArgumentException("merchant, operator, order and idempotency key are required");
        }
        if (amountMinor == null || amountMinor < 1) {
            throw new IllegalArgumentException("refund amount must be positive");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > MAX_REASON || why.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("refund reason is invalid");
        }
        requireChannel();
        UUID refundId = tx.execute(status -> {
            Instant now = clock.instant();
            RefundOrderRow order = refunds.lockMerchantOrder(merchantId, orderId);
            if (order == null) {
                throw new TradeException(Error.NOT_FOUND);
            }
            RefundRow replayed = refunds.findRefundByKey(orderId, idempotencyKey);
            if (replayed != null) {
                if (!"MERCHANT".equals(replayed.initiator()) || replayed.amountMinor() != amountMinor
                        || !why.equals(replayed.reason())) {
                    throw new TradeException(Error.IDEMPOTENCY_CONFLICT);
                }
                return replayed.id();
            }
            TradeOrderStatus current = TradeOrderStatus.valueOf(order.status());
            if (current != TradeOrderStatus.PAID && current != TradeOrderStatus.SHIPPED
                    && current != TradeOrderStatus.COMPLETED && current != TradeOrderStatus.REFUND_FAILED) {
                throw new TradeException(Error.ORDER_STATE_INVALID);
            }
            PaidPaymentRow payment = requirePayment(order);
            requireWithinLimits(order, now);
            long refundable = refundable(order);
            if (amountMinor > refundable) {
                throw new TradeException(Error.REFUND_EXCEEDS_PAID);
            }
            UUID created = refunds.insertRefund(orderId, payment.id(), newOutRefundNo(now), amountMinor, why,
                    "MERCHANT", operatorId, idempotencyKey, now);
            // 退的是剩下的全部：订单进入退款中；部分退款不改订单状态。
            if (amountMinor == refundable) {
                refunds.markOrderRefunding(orderId, now);
                orders.insertTransition(orderId, order.status(), TradeOrderStatus.REFUNDING.name(), "MERCHANT_REFUND",
                        operatorId, now);
            }
            return created;
        });
        submit(refundId);
        return tx.execute(status -> view(refunds.lockRefund(refundId)));
    }

    // ---- 业务来源发起的退款（E09） ----

    /**
     * 业务来源的订单（收费报名）由业务模块发起退款：报名单按凭证退改，或付款到账时业务收不下的自动全额退款。
     * 在调用方的事务里执行，不调渠道：调用方在事务提交后再 {@link #submit}（定时任务也会兜底重提）。
     * 规则和商家退款一样（付款后一年内、最多 50 次、合计不超过实付）；退的是剩下的全部时订单进入退款中。
     * 同一个幂等键、同样的金额和原因重放返回同一笔退款。
     *
     * @param amountMinor 为 null 时退还没退的全部
     * @param actorId     发起人；自动退款没有，为 null
     */
    UUID createSourceRefund(UUID orderId, Long amountMinor, String reason, UUID idempotencyKey, UUID actorId) {
        if (orderId == null || idempotencyKey == null || (amountMinor != null && amountMinor < 1)) {
            throw new IllegalArgumentException("source refund is invalid");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > MAX_REASON || why.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("refund reason is invalid");
        }
        Instant now = clock.instant();
        RefundOrderRow order = refunds.lockOrder(orderId);
        if (order == null) {
            throw new TradeException(Error.NOT_FOUND);
        }
        RefundRow replayed = refunds.findRefundByKey(orderId, idempotencyKey);
        if (replayed != null) {
            if (!"SOURCE".equals(replayed.initiator()) || !why.equals(replayed.reason())
                    || (amountMinor != null && replayed.amountMinor() != amountMinor)) {
                throw new TradeException(Error.IDEMPOTENCY_CONFLICT);
            }
            return replayed.id();
        }
        TradeOrderStatus current = TradeOrderStatus.valueOf(order.status());
        if (current != TradeOrderStatus.PAID && current != TradeOrderStatus.SHIPPED
                && current != TradeOrderStatus.COMPLETED && current != TradeOrderStatus.REFUND_FAILED) {
            throw new TradeException(Error.ORDER_STATE_INVALID);
        }
        PaidPaymentRow payment = requirePayment(order);
        requireWithinLimits(order, now);
        long refundable = refundable(order);
        long amount = amountMinor == null ? refundable : amountMinor;
        if (refundable <= 0 || amount > refundable) {
            throw new TradeException(Error.REFUND_EXCEEDS_PAID);
        }
        UUID created = refunds.insertRefund(orderId, payment.id(), newOutRefundNo(now), amount, why,
                "SOURCE", actorId, idempotencyKey, now);
        if (amount == refundable) {
            refunds.markOrderRefunding(orderId, now);
            orders.insertTransition(orderId, order.status(), TradeOrderStatus.REFUNDING.name(), "SOURCE_REFUND",
                    actorId, now);
        }
        return created;
    }

    // ---- 提交给渠道 ----

    /**
     * 把一笔“已建”的退款交给渠道。渠道不通、或被拒绝但不是确定的业务拒绝时，留在“已建”等定时任务重试；
     * 确定的业务拒绝（{@link #DEFINITE_REFUND_REJECTIONS}）关闭这笔退款。
     */
    void submit(UUID refundId) {
        PaymentChannel channel = channels.get();
        if (channel == null) {
            return;
        }
        Pending pending = tx.execute(status -> {
            RefundRow refund = refunds.lockRefund(refundId);
            if (refund == null || !"REQUESTED".equals(refund.status())) {
                return null;
            }
            return new Pending(refund, refunds.findSucceededPayment(refund.orderId()));
        });
        if (pending == null) {
            return;
        }
        if (pending.payment() == null) {
            log.error("refund {} has no succeeded payment to refund from; left for manual review",
                    pending.refund().outRefundNo());
            return;
        }
        RefundRow refund = pending.refund();
        PaidPaymentRow payment = pending.payment();
        RefundAccepted accepted;
        try {
            accepted = channel.applyRefund(new RefundRequest(payment.subMchid(), payment.outTradeNo(),
                    refund.outRefundNo(), refund.amountMinor(), payment.amountMinor(), refund.reason()));
        } catch (PaymentChannelException exception) {
            if (exception.kind() == PaymentChannelException.Kind.REJECTED
                    && DEFINITE_REFUND_REJECTIONS.contains(exception.code())) {
                log.error("wechat rejected refund {}: {}", refund.outRefundNo(), exception.code());
                // 只关还在“已建”的：拒绝应答到之前，回调或查询可能已经把它记成处理中或到账了。
                tx.executeWithoutResult(status ->
                        finish(refund.orderId(), refund.id(), "CLOSED", "REFUND_REJECTED", "REQUESTED"));
            } else if (exception.kind() == PaymentChannelException.Kind.REJECTED) {
                log.error("wechat rejected refund {} with {}; kept for a retry, check the channel setup",
                        refund.outRefundNo(), exception.code());
                tx.executeWithoutResult(status -> refunds.touch(refund.id(), clock.instant()));
            } else {
                log.warn("submitting refund {} failed, will retry: {} {}",
                        refund.outRefundNo(), exception.kind(), exception.code());
                tx.executeWithoutResult(status -> refunds.touch(refund.id(), clock.instant()));
            }
            return;
        }
        if (accepted.refundMinor() != refund.amountMinor()) {
            log.error("wechat accepted refund {} with another amount; waiting for the query to settle it", refund.outRefundNo());
        }
        tx.executeWithoutResult(status -> {
            refunds.lockOrder(refund.orderId());
            Instant at = clock.instant();
            refunds.markProcessing(refund.id(), accepted.refundId(),
                    accepted.acceptedAt() != null ? accepted.acceptedAt() : at, at);
        });
    }

    private record Pending(RefundRow refund, PaidPaymentRow payment) {
    }

    // ---- 渠道结果 ----

    enum Applied {
        /** 这次改了退款单（到账、关闭或异常）。 */
        APPLIED,
        /** 之前已经记过同样的结果。 */
        ALREADY,
        /** 还在处理中，或渠道还不知道这笔退款。 */
        NOT_FINAL,
        /** 不认识的退款单号。 */
        UNKNOWN,
        /** 和退款单对不上（商户、订单、金额），或已关闭的退款报成功：不改，人工核对。 */
        MISMATCH
    }

    /** 把渠道的一次退款结果（回调或查询）落到退款单和订单上。 */
    Applied apply(RefundQuery result, String cause) {
        if (result.outRefundNo() == null) {
            return Applied.UNKNOWN;
        }
        return tx.execute(status -> {
            RefundRow found = refunds.findRefundByOutRefundNo(result.outRefundNo());
            if (found == null) {
                log.warn("refund result for unknown out_refund_no {}", result.outRefundNo());
                return Applied.UNKNOWN;
            }
            RefundOrderRow order = refunds.lockOrder(found.orderId());
            RefundRow refund = refunds.lockRefund(found.id());
            PaidPaymentRow payment = refunds.findSucceededPayment(order.id());
            String problem = mismatch(result, refund, payment);
            if (problem != null) {
                log.error("refund {} does not match the channel result ({}); held for manual review",
                        refund.outRefundNo(), problem);
                return Applied.MISMATCH;
            }
            Instant now = clock.instant();
            switch (result.state()) {
                case SUCCESS -> {
                    if ("SUCCEEDED".equals(refund.status())) {
                        return Applied.ALREADY;
                    }
                    if ("CLOSED".equals(refund.status())) {
                        log.error("closed refund {} was reported as succeeded; held for manual review", refund.outRefundNo());
                        return Applied.MISMATCH;
                    }
                    long refunded = order.refundedMinor() + refund.amountMinor();
                    if (refunded > order.paidMinor()) {
                        // 按规则不会发生（可退金额已扣掉所有占着钱的退款）；万一发生，不让账面超过实付，人工核对。
                        log.error("refund {} would make the refunded total exceed the paid amount; held for manual review",
                                refund.outRefundNo());
                        return Applied.MISMATCH;
                    }
                    refunds.markSucceeded(refund.id(), result.refundId(),
                            result.succeededAt() != null ? result.succeededAt() : now, now);
                    String next = order.status();
                    if (refunded == order.paidMinor()) {
                        next = TradeOrderStatus.REFUNDED.name();
                    } else if (TradeOrderStatus.REFUNDING.name().equals(order.status())
                            && refunds.sumOpenRefunds(order.id()) == 0) {
                        // 全额退款里有一笔没退成：没退完，人工处理。
                        next = TradeOrderStatus.REFUND_FAILED.name();
                    }
                    refunds.addRefunded(order.id(), refund.amountMinor(), next, now);
                    if (!next.equals(order.status())) {
                        orders.insertTransition(order.id(), order.status(), next, cause, null, now);
                    }
                    buyerEvents.refunded(order.id(), refund.id(), refund.amountMinor(), now);
                    return Applied.APPLIED;
                }
                case CLOSED, ABNORMAL -> {
                    String target = result.state() == PaymentChannel.RefundState.CLOSED ? "CLOSED" : "ABNORMAL";
                    if (target.equals(refund.status())) {
                        return Applied.ALREADY;
                    }
                    if ("SUCCEEDED".equals(refund.status()) || "CLOSED".equals(refund.status())) {
                        log.error("refund {} went from {} to {}; held for manual review",
                                refund.outRefundNo(), refund.status(), target);
                        return Applied.MISMATCH;
                    }
                    finish(order.id(), refund.id(), target, cause, null);
                    return Applied.APPLIED;
                }
                default -> {
                    return Applied.NOT_FINAL;
                }
            }
        });
    }

    /**
     * 退款单结束在 CLOSED 或 ABNORMAL；如果订单在全额退款中、又没有别的在路上的退款，订单转“退款异常”。在事务里调用。
     *
     * @param expectedStatus 不为 null 时，只有锁住后退款单还是这个状态才结束（渠道当场拒绝时只关仍是“已建”的）
     */
    private void finish(UUID orderId, UUID refundId, String target, String cause, String expectedStatus) {
        // 锁的顺序和别处一样：先订单、后退款单。
        RefundOrderRow order = refunds.lockOrder(orderId);
        RefundRow refund = refunds.lockRefund(refundId);
        if (expectedStatus != null && !expectedStatus.equals(refund.status())) {
            log.warn("refund {} is already {}; not ending it as {}", refund.outRefundNo(), refund.status(), target);
            return;
        }
        Instant now = clock.instant();
        if (refunds.markFinished(refundId, target, now) == 0) {
            return;
        }
        if (TradeOrderStatus.REFUNDING.name().equals(order.status()) && refunds.sumOpenRefunds(order.id()) == 0) {
            refunds.changeOrderStatus(order.id(), order.status(), TradeOrderStatus.REFUND_FAILED.name(), now);
            orders.insertTransition(order.id(), order.status(), TradeOrderStatus.REFUND_FAILED.name(), cause, null, now);
        }
        log.warn("refund {} ended as {}", refund.outRefundNo(), target);
    }

    /** 和支付一样严格：子商户号、商户订单号、退款金额缺了也算对不上（回调和查询应答里这三项都是必有的）。 */
    private static String mismatch(RefundQuery result, RefundRow refund, PaidPaymentRow payment) {
        if (payment == null) {
            return "no succeeded payment";
        }
        if (result.subMchid() == null || !result.subMchid().equals(payment.subMchid())) {
            return "sub merchant";
        }
        if (result.outTradeNo() == null || !result.outTradeNo().equals(payment.outTradeNo())) {
            return "out_trade_no";
        }
        if (result.refundMinor() == null || result.refundMinor() != refund.amountMinor()) {
            return "amount";
        }
        return null;
    }

    // ---- 定时兜底 ----

    /** 重新提交还没提交成功的退款，查询处理中和异常的退款。返回这一轮改了几笔。 */
    public int pollRefunds() {
        PaymentChannel channel = channels.get();
        if (channel == null) {
            return 0;
        }
        Instant now = clock.instant();
        for (RefundRow refund : tx.execute(status -> refunds.findRefundsToSubmit(now.minus(RETRY_AFTER), BATCH))) {
            try {
                submit(refund.id());
            } catch (RuntimeException exception) {
                log.error("resubmitting refund {} failed", refund.outRefundNo(), exception);
            }
        }
        int changed = 0;
        List<RefundRow> toQuery = tx.execute(status ->
                refunds.findRefundsToQuery(now.minus(RETRY_AFTER), now.minus(ABNORMAL_RECHECK), BATCH));
        for (RefundRow refund : toQuery) {
            try {
                PaidPaymentRow payment = tx.execute(status -> refunds.findSucceededPayment(refund.orderId()));
                if (payment == null) {
                    continue;
                }
                Applied applied = apply(channel.queryRefund(payment.subMchid(), refund.outRefundNo()), "REFUND_QUERIED");
                if (applied == Applied.APPLIED) {
                    changed++;
                } else {
                    tx.executeWithoutResult(status -> refunds.touch(refund.id(), clock.instant()));
                }
            } catch (PaymentChannelException exception) {
                log.warn("querying refund {} failed: {} {}", refund.outRefundNo(), exception.kind(), exception.code());
                // 排到后面：一直查不通的几笔不能把一批的名额占满，挡住别的退款。
                touchQuietly(refund);
            } catch (RuntimeException exception) {
                log.error("querying refund {} failed", refund.outRefundNo(), exception);
            }
        }
        return changed;
    }

    // ---- 小工具 ----

    /** 只更新时间；更新失败也不让这一轮的循环停下（它在 catch 里调用）。 */
    private void touchQuietly(RefundRow refund) {
        try {
            tx.executeWithoutResult(status -> refunds.touch(refund.id(), clock.instant()));
        } catch (RuntimeException exception) {
            log.error("touching refund {} failed", refund.outRefundNo(), exception);
        }
    }

    /** 还能退多少：实付减已退，再减还占着钱的退款（在路上的和异常的）。 */
    private long refundable(RefundOrderRow order) {
        return order.paidMinor() - order.refundedMinor() - refunds.sumReservedRefunds(order.id());
    }

    private void requireWithinLimits(RefundOrderRow order, Instant now) {
        if (order.paidAt() == null || order.paidAt().plus(REFUND_WINDOW).isBefore(now)
                || refunds.countRefunds(order.id()) >= MAX_REFUNDS_PER_ORDER) {
            throw new TradeException(Error.REFUND_LIMIT_REACHED);
        }
    }

    private PaidPaymentRow requirePayment(RefundOrderRow order) {
        PaidPaymentRow payment = refunds.findSucceededPayment(order.id());
        if (payment == null) {
            log.error("order {} is paid but has no succeeded payment", order.id());
            throw new TradeException(Error.ORDER_STATE_INVALID);
        }
        return payment;
    }

    private PaymentChannel requireChannel() {
        PaymentChannel channel = channels.get();
        if (channel == null) {
            throw new TradeException(Error.CHANNEL_UNAVAILABLE);
        }
        return channel;
    }

    private static TradeRefundView view(RefundRow refund) {
        return new TradeRefundView(refund.id(), refund.amountMinor(), refund.reason(), refund.status(),
                refund.createdAt(), refund.succeededAt());
    }

    /** “R” + 北京日期 + 12 位随机数字，共 21 位；撞号就换一个。 */
    private String newOutRefundNo(Instant now) {
        String day = DAY.format(now.atZone(BEIJING));
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "R" + day + outRefundNoSuffix.get();
            if (refunds.countOutRefundNo(candidate) == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not pick an unused out_refund_no");
    }
}
