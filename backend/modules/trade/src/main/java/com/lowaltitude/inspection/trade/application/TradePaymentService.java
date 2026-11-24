package com.lowaltitude.inspection.trade.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.lowaltitude.inspection.trade.application.PaymentChannel.AppPayParams;
import com.lowaltitude.inspection.trade.application.PaymentChannel.ChannelNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.OtherNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentNotification;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentState;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PrepayRequest;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundNotification;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeViews.PaymentLaunchView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.application.TradeViews.WechatAppCallView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.LineRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradePaymentMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradePaymentMapper.NotificationRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradePaymentMapper.PaymentOrderRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradePaymentMapper.PaymentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 支付（E04，契约 contracts/openapi-trade.yaml）：发起支付、App 回来后查一次单、渠道回调、没收到回调时的查单兜底、
 * 有进行中支付单的订单在取消或超时时先查单再关。
 *
 * <p>订单只在这里因为付款而改变，入口只有一个 {@link #apply}：回调和查单的结果都要和支付单核对
 * （服务商号、子商户号、商户单号、金额、币种）全部对上，才把订单改成已付款；对不上的支付单记为 MISMATCH 并告警，订单不动。
 *
 * <p>调渠道时不占着数据库锁：先在一个短事务里建支付单，在事务外调渠道，再在另一个短事务里记结果。
 * 锁的顺序固定是先订单、后支付单。
 */
public class TradePaymentService {

    static final String WECHAT_APP = "WECHAT_APP";
    /** 微信的预支付 ID 2 小时有效；本地记早 5 分钟，免得把快过期的交给 App。 */
    static final Duration PREPAY_LIFETIME = Duration.ofHours(2).minusMinutes(5);
    /** 发起支付后过多久还没有结果就开始主动查单。 */
    static final Duration POLL_AFTER = Duration.ofMinutes(1);
    static final int POLL_BATCH = 50;
    static final int EXPIRE_BATCH = 20;
    static final int MAX_DETAIL = 200;
    /** App 回来后的查单：同一张订单多久内只真正查一次渠道。 */
    static final Duration CHECK_INTERVAL = Duration.ofSeconds(2);
    /** 查单时间表最多记多少张订单。 */
    static final int MAX_TRACKED_CHECKS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(TradePaymentService.class);
    private static final String PENDING = TradeOrderStatus.PENDING_PAYMENT.name();
    private static final String CLOSED = TradeOrderStatus.CLOSED.name();
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TradePaymentMapper payments;
    private final TradeOrderMapper orders;
    private final TradeOrderService orderService;
    private final Supplier<PaymentChannel> channels;
    private final TransactionOperations tx;
    private final Clock clock;
    private final Supplier<String> outTradeNoSuffix;
    private final Supplier<String> pickupCodes;
    private final TradeRefundService refunds;
    private final TradeSourceEvents events;
    private final TradeBuyerEvents buyerEvents;
    /** 每张订单上次真正查单的时间（见 {@link #takeCheckSlot}）。 */
    private final Map<UUID, Instant> lastChecks = new ConcurrentHashMap<>();

    TradePaymentService(
            TradePaymentMapper payments,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            Supplier<PaymentChannel> channels,
            TransactionOperations tx,
            Clock clock,
            Supplier<String> outTradeNoSuffix,
            Supplier<String> pickupCodes,
            TradeRefundService refunds
    ) {
        this(payments, orders, orderService, channels, tx, clock, outTradeNoSuffix, pickupCodes, refunds,
                TradeSourceEvents.none());
    }

    TradePaymentService(
            TradePaymentMapper payments,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            Supplier<PaymentChannel> channels,
            TransactionOperations tx,
            Clock clock,
            Supplier<String> outTradeNoSuffix,
            Supplier<String> pickupCodes,
            TradeRefundService refunds,
            TradeSourceEvents events
    ) {
        this(payments, orders, orderService, channels, tx, clock, outTradeNoSuffix, pickupCodes, refunds, events,
                TradeBuyerEvents.none());
    }

    /** @param buyerEvents 自提的商城订单付款到账时告诉买家可以提货了（R6-5） */
    TradePaymentService(
            TradePaymentMapper payments,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            Supplier<PaymentChannel> channels,
            TransactionOperations tx,
            Clock clock,
            Supplier<String> outTradeNoSuffix,
            Supplier<String> pickupCodes,
            TradeRefundService refunds,
            TradeSourceEvents events,
            TradeBuyerEvents buyerEvents
    ) {
        this.payments = payments;
        this.orders = orders;
        this.orderService = orderService;
        this.channels = channels;
        this.tx = tx;
        this.clock = clock;
        this.outTradeNoSuffix = outTradeNoSuffix;
        this.pickupCodes = pickupCodes;
        this.refunds = refunds;
        this.events = events;
        this.buyerEvents = buyerEvents;
    }

    // ---- 发起支付 ----

    /**
     * 发起支付，返回 App 调起微信要的参数。已有进行中的支付单时沿用它（不会在微信那边开第二单）；
     * 上次没拿到预支付就用同一个商户单号、同样的内容再下一次单。
     */
    public PaymentLaunchView launch(UUID buyerId, UUID orderId, UUID idempotencyKey, String channelName) {
        if (buyerId == null || orderId == null || idempotencyKey == null) {
            throw new IllegalArgumentException("buyer, order and idempotency key are required");
        }
        if (!WECHAT_APP.equals(channelName)) {
            throw new IllegalArgumentException("channel is not supported");
        }
        // S5-8：注销冷静期内不能付款（撤回注销后恢复）。
        orderService.payerFor(buyerId);
        PaymentChannel channel = requireChannel();
        Instant now = clock.instant();

        Prepared prepared = tx.execute(status -> {
            PaymentOrderRow order = payments.lockBuyerOrder(buyerId, orderId);
            if (order == null) {
                throw new TradeException(Error.NOT_FOUND);
            }
            if (!PENDING.equals(order.status())) {
                throw new TradeException(Error.ORDER_STATE_INVALID);
            }
            if (!order.payBefore().isAfter(now)) {
                throw new TradeException(Error.ORDER_EXPIRED);
            }
            PaymentRow open = payments.lockOpenPayment(orderId);
            if (open == null) {
                // 这个键用过、那张支付单又已经不在进行中：说明订单正在被关闭，让买家刷新。
                if (payments.findPaymentByKey(orderId, idempotencyKey) != null) {
                    throw new TradeException(Error.ORDER_STATE_INVALID);
                }
                String outTradeNo = newOutTradeNo(now);
                UUID paymentId = payments.insertPayment(
                        orderId, outTradeNo, order.subMchid(), order.payableMinor(), idempotencyKey, now);
                open = new PaymentRow(paymentId, orderId, outTradeNo, order.subMchid(), order.payableMinor(),
                        "CREATED", null, null, null, idempotencyKey);
            }
            return new Prepared(order, open, description(order));
        });

        PaymentRow payment = prepared.payment();
        if (!"PREPAID".equals(payment.status())) {
            String prepayId;
            try {
                prepayId = channel.createAppPayment(new PrepayRequest(payment.subMchid(), payment.outTradeNo(),
                        prepared.description(), payment.amountMinor(), prepared.order().payBefore()));
            } catch (PaymentChannelException exception) {
                // 支付单留在 CREATED：下次发起用同一个商户单号重试；订单到期时查单再关。
                log.warn("wechat prepay for payment {} failed: {} {}", payment.outTradeNo(), exception.kind(), exception.code());
                throw new TradeException(Error.CHANNEL_UNAVAILABLE);
            }
            payment = tx.execute(status -> {
                PaymentOrderRow order = payments.lockOrder(orderId);
                PaymentRow current = payments.lockPayment(prepared.payment().id());
                Instant at = clock.instant();
                if (order == null || !PENDING.equals(order.status())) {
                    // 订单在这段时间里已经不是待付款（被关闭或已由别的支付单付过），而这张支付单还开着：
                    // 本地先关掉，免得它挂在一张不再待付款的订单上；微信那边在事务外关。
                    payments.closePayment(current.id(), at);
                    return payments.lockPayment(current.id());
                }
                if (!"CREATED".equals(current.status())) {
                    return current;
                }
                payments.markPrepaid(current.id(), prepayId, at.plus(PREPAY_LIFETIME), at);
                return payments.lockPayment(current.id());
            });
            if (!"PREPAID".equals(payment.status())) {
                // 拿预支付的这段时间里，订单被取消或超时关闭了。微信那边刚建的单没交给任何人，顺手关掉；
                // 关不掉也不要紧，它在订单的付款截止时间自己失效。
                if ("CLOSED".equals(payment.status())) {
                    closeQuietly(channel, payment);
                }
                throw new TradeException(Error.ORDER_STATE_INVALID);
            }
        }
        if (!payment.prepayExpiresAt().isAfter(clock.instant())) {
            log.warn("prepay of payment {} expired before its order", payment.outTradeNo());
            throw new TradeException(Error.ORDER_EXPIRED);
        }

        AppPayParams params = channel.appPayParams(payment.prepayId());
        Instant expiresAt = payment.prepayExpiresAt().isBefore(prepared.order().payBefore())
                ? payment.prepayExpiresAt()
                : prepared.order().payBefore();
        return new PaymentLaunchView(payment.id(), WECHAT_APP, expiresAt, new WechatAppCallView(params.appId(),
                params.partnerId(), params.prepayId(), params.packageValue(), params.nonceStr(), params.timeStamp(),
                params.sign()));
    }

    private record Prepared(PaymentOrderRow order, PaymentRow payment, String description) {
    }

    // ---- App 回来后查一次单 ----

    /** 只看渠道的结果，不信 App 说的任何支付状态。没有进行中的支付单时直接返回订单。 */
    public TradeOrderView check(UUID buyerId, UUID orderId) {
        if (buyerId == null || orderId == null) {
            throw new IllegalArgumentException("buyer and order are required");
        }
        PaymentRow open = tx.execute(status -> {
            PaymentOrderRow order = payments.findBuyerOrder(buyerId, orderId);
            if (order == null) {
                throw new TradeException(Error.NOT_FOUND);
            }
            return PENDING.equals(order.status()) ? payments.findOpenPayment(orderId) : null;
        });
        if (open != null) {
            PaymentChannel channel = requireChannel();
            if (takeCheckSlot(orderId, clock.instant())) {
                apply(channel, query(channel, open), "PAYMENT_QUERIED");
            }
        }
        return orderService.read(buyerId, orderId);
    }

    /**
     * 买家自己这张单正在进行的支付单号；没有在途支付时返回 null。只给开发环境的“模拟付款”用：
     * 它拿这个号去让本机模拟微信付款，订单状态仍然只由验签后的回调推进。
     */
    public String openPaymentNo(UUID buyerId, UUID orderId) {
        if (buyerId == null || orderId == null) {
            throw new IllegalArgumentException("buyer and order are required");
        }
        return tx.execute(status -> {
            if (payments.findBuyerOrder(buyerId, orderId) == null) {
                throw new TradeException(Error.NOT_FOUND);
            }
            PaymentRow open = payments.findOpenPayment(orderId);
            return open == null ? null : open.outTradeNo();
        });
    }

    /**
     * 同一张订单 {@link #CHECK_INTERVAL} 内只放行一次真正的查单（App 回来后会连着查几次，不能每次都打到渠道）。
     * 记录只在本实例的内存里，多实例部署时各算各的，最多多查几次，不影响结果；
     * 条数超过 {@link #MAX_TRACKED_CHECKS} 时先清掉已过期的，还超就整个清空。
     */
    boolean takeCheckSlot(UUID orderId, Instant now) {
        if (lastChecks.size() > MAX_TRACKED_CHECKS) {
            lastChecks.values().removeIf(at -> !at.plus(CHECK_INTERVAL).isAfter(now));
            if (lastChecks.size() > MAX_TRACKED_CHECKS) {
                lastChecks.clear();
            }
        }
        boolean[] taken = {false};
        lastChecks.compute(orderId, (id, last) -> {
            if (last != null && last.plus(CHECK_INTERVAL).isAfter(now) && !last.isAfter(now)) {
                return last;
            }
            taken[0] = true;
            return now;
        });
        return taken[0];
    }

    /** 测试用：现在记着几张订单的查单时间。 */
    int trackedChecks() {
        return lastChecks.size();
    }

    // ---- 取消 ----

    /**
     * 买家取消。未付款的直接关；发起过支付的，先向渠道查单：已经付了就按付款处理并拒绝取消（买家刷新后看到已付款，
     * 再取消就是退款）；没付就先在渠道关单，再关订单。已付款、未发货（未自提）的交给退款服务全额退款。
     */
    public TradeOrderView cancel(UUID buyerId, UUID orderId, int version, UUID idempotencyKey) {
        try {
            return orderService.cancel(buyerId, orderId, version);
        } catch (TradeException exception) {
            if (exception.error() == Error.REFUND_REQUIRED) {
                // 已付款、未发货（未自提）：取消即全额退款（E05）。
                return refunds.cancelPaid(buyerId, orderId, version, idempotencyKey);
            }
            if (exception.error() != Error.PAYMENT_IN_PROGRESS) {
                throw exception;
            }
        }
        PaymentChannel channel = requireChannel();
        PaymentRow open = tx.execute(status -> payments.findOpenPayment(orderId));
        if (open != null && settle(channel, open) != Settled.CLOSED) {
            // 买家其实已经付了款（或这笔付款要人工核对）：订单不再是待付款，页面刷新后会看到。
            throw new TradeException(Error.ORDER_STATE_INVALID);
        }
        try {
            return orderService.cancel(buyerId, orderId, version);
        } catch (TradeException exception) {
            if (exception.error() == Error.PAYMENT_IN_PROGRESS) {
                // 关单的同时买家又发起了一次支付。
                throw new TradeException(Error.VERSION_CONFLICT);
            }
            throw exception;
        }
    }

    // ---- 定时兜底 ----

    /** 没收到回调时靠查单补上：建了 1 分钟以上、订单还在付款期内的支付单。返回因此改成已付款的订单数。 */
    public int pollOpenPayments() {
        PaymentChannel channel = channels.get();
        if (channel == null) {
            return 0;
        }
        Instant now = clock.instant();
        List<PaymentRow> rows = tx.execute(status -> payments.findPaymentsToPoll(now.minus(POLL_AFTER), now, POLL_BATCH));
        int applied = 0;
        for (PaymentRow row : rows) {
            try {
                Applied result = apply(channel, channel.queryPayment(row.subMchid(), row.outTradeNo()), "PAYMENT_QUERIED");
                if (result == Applied.APPLIED) {
                    applied++;
                } else if (result == Applied.NOT_PAID) {
                    // 查到了、还没付：也排到后面，让这一批里别的支付单先查。
                    touchQuietly(row);
                }
            } catch (PaymentChannelException exception) {
                log.warn("polling payment {} failed: {} {}", row.outTradeNo(), exception.kind(), exception.code());
                touchQuietly(row);
            } catch (RuntimeException exception) {
                log.error("polling payment {} failed", row.outTradeNo(), exception);
                touchQuietly(row);
            }
        }
        return applied;
    }

    /**
     * 过了付款截止时间、但还有进行中支付单的订单：先查单，付了就入账，没付就在渠道关单再关订单。
     * 渠道不通时留到下一轮，绝不在不知道付没付的情况下关单。返回关掉的订单数。
     */
    public int closeExpiredWithPayments() {
        Instant now = clock.instant();
        List<PaymentRow> expired = tx.execute(status -> payments.findExpiredWithOpenPayment(now, EXPIRE_BATCH));
        if (expired.isEmpty()) {
            return 0;
        }
        PaymentChannel channel = channels.get();
        if (channel == null) {
            log.warn("{} expired orders have open payments but no payment channel is configured", expired.size());
            return 0;
        }
        int closed = 0;
        for (PaymentRow row : expired) {
            UUID orderId = row.orderId();
            try {
                PaymentRow open = tx.execute(status -> payments.findOpenPayment(orderId));
                if (open != null && settle(channel, open) != Settled.CLOSED) {
                    continue;
                }
                if (Boolean.TRUE.equals(tx.execute(status -> closeExpiredOrder(orderId)))) {
                    closed++;
                }
            } catch (TradeException | PaymentChannelException exception) {
                log.warn("closing expired order {} is deferred: {}", orderId, exception.getMessage());
                // 排到后面：查不通或状态不明的几张不能一直占着一批的名额，挡住别的过期订单。
                touchQuietly(row);
            } catch (RuntimeException exception) {
                log.error("closing expired order {} failed", orderId, exception);
                touchQuietly(row);
            }
        }
        return closed;
    }

    /** 只更新支付单的时间；更新失败也不让这一轮的循环停下（它在 catch 里调用）。 */
    private void touchQuietly(PaymentRow payment) {
        try {
            tx.executeWithoutResult(status -> payments.touchPayment(payment.id(), clock.instant()));
        } catch (RuntimeException exception) {
            log.error("touching payment {} failed", payment.outTradeNo(), exception);
        }
    }

    private boolean closeExpiredOrder(UUID orderId) {
        Instant now = clock.instant();
        PaymentOrderRow order = payments.lockOrder(orderId);
        if (order == null || !PENDING.equals(order.status()) || order.payBefore().isAfter(now)
                || orders.countOpenPayments(orderId) > 0) {
            return false;
        }
        if (orders.closeUnpaid(orderId, order.version(), now) == 0) {
            return false;
        }
        orders.insertTransition(orderId, PENDING, CLOSED, "TIMEOUT", null, now);
        events.closed(orderId);
        return true;
    }

    // ---- 渠道回调 ----

    /** 回调接口据此回：ACKNOWLEDGED 204（含重复的通知）；UNTRUSTED 401；FAILED 500，渠道会重发。 */
    public enum NotificationResult {
        ACKNOWLEDGED, UNTRUSTED, FAILED
    }

    /**
     * 处理一条回调：验签解密，按通知 ID 去重，付款通知走 {@link #apply}。
     *
     * @param headers 请求头，键不分大小写
     * @param body    原始请求体
     */
    public NotificationResult handleNotification(Map<String, String> headers, String body) {
        PaymentChannel channel = channels.get();
        if (channel == null) {
            log.error("a payment notification arrived but no payment channel is configured");
            return NotificationResult.FAILED;
        }
        ChannelNotification notification;
        try {
            notification = channel.parseNotification(headers, body);
        } catch (PaymentChannelException exception) {
            log.warn("rejected a payment notification: {}", exception.getMessage());
            return NotificationResult.UNTRUSTED;
        }
        String outTradeNo = switch (notification) {
            case PaymentNotification payment -> payment.payment().outTradeNo();
            case RefundNotification refund -> refund.refund().outTradeNo();
            case OtherNotification other -> null;
        };
        String outRefundNo = notification instanceof RefundNotification refund ? refund.refund().outRefundNo() : null;
        if (!fits(notification.notificationId(), 64) || !fits(outTradeNo, 32) || !fits(outRefundNo, 64)) {
            log.warn("rejected a payment notification with oversized identifiers");
            return NotificationResult.UNTRUSTED;
        }

        UUID recordId;
        try {
            recordId = tx.execute(status -> {
                UUID inserted = payments.insertNotification(notification.notificationId(),
                        truncate(notification.eventType(), 40), outTradeNo, outRefundNo, clock.instant());
                if (inserted != null) {
                    return inserted;
                }
                // 同一条通知又来了：处理完的直接回成功；上次中途失败（还是 RECEIVED）的再处理一遍，入账本身是幂等的。
                NotificationRow existing = payments.findNotification(notification.notificationId());
                return existing != null && "RECEIVED".equals(existing.outcome()) ? existing.id() : null;
            });
        } catch (RuntimeException exception) {
            log.error("recording payment notification {} failed", notification.notificationId(), exception);
            return NotificationResult.FAILED;
        }
        if (recordId == null) {
            return NotificationResult.ACKNOWLEDGED;
        }

        try {
            String outcome;
            String detail;
            switch (notification) {
                case PaymentNotification payment -> {
                    Applied applied = apply(channel, payment.payment(), "PAYMENT_NOTIFIED");
                    outcome = switch (applied) {
                        case APPLIED -> "APPLIED";
                        case MISMATCH -> "REJECTED";
                        default -> "IGNORED";
                    };
                    detail = applied.name();
                }
                case RefundNotification refund -> {
                    TradeRefundService.Applied applied = refunds.apply(refund.refund(), "REFUND_NOTIFIED");
                    outcome = switch (applied) {
                        case APPLIED -> "APPLIED";
                        case MISMATCH -> "REJECTED";
                        default -> "IGNORED";
                    };
                    detail = applied.name();
                }
                case OtherNotification other -> {
                    outcome = "IGNORED";
                    detail = "EVENT_NOT_HANDLED";
                }
            }
            tx.execute(status -> payments.finishNotification(recordId, outcome, truncate(detail, MAX_DETAIL)));
            return NotificationResult.ACKNOWLEDGED;
        } catch (RuntimeException exception) {
            log.error("handling payment notification {} failed", notification.notificationId(), exception);
            return NotificationResult.FAILED;
        }
    }

    // ---- 入账 ----

    enum Applied {
        /** 这次把订单改成了已付款。 */
        APPLIED,
        /** 之前已经入过账（同一笔交易）。 */
        ALREADY,
        /** 渠道说还没付（或已关闭、不存在）。 */
        NOT_PAID,
        /** 不认识的商户单号。 */
        UNKNOWN,
        /** 和支付单对不上，或订单已由另一张支付单付过：支付单停在 MISMATCH，等人工处理。 */
        MISMATCH
    }

    /** 把渠道的一次结果（回调或查单）落到支付单和订单上。 */
    Applied apply(PaymentChannel channel, PaymentQuery result, String cause) {
        if (result.state() != PaymentState.SUCCESS && result.state() != PaymentState.REFUND) {
            return Applied.NOT_PAID;
        }
        return tx.execute(status -> {
            PaymentRow found = payments.findPaymentByOutTradeNo(result.outTradeNo());
            if (found == null) {
                log.warn("payment result for unknown out_trade_no {}", result.outTradeNo());
                return Applied.UNKNOWN;
            }
            PaymentOrderRow order = payments.lockOrder(found.orderId());
            PaymentRow payment = payments.lockPayment(found.id());
            Instant now = clock.instant();

            String problem = mismatch(channel, result, payment);
            if (problem != null) {
                if (!"SUCCEEDED".equals(payment.status())) {
                    payments.markMismatch(payment.id(), now);
                }
                log.error("payment {} does not match the channel result ({}); held for manual review",
                        payment.outTradeNo(), problem);
                return Applied.MISMATCH;
            }
            if ("SUCCEEDED".equals(payment.status())) {
                if (result.transactionId().equals(payment.transactionId())) {
                    return Applied.ALREADY;
                }
                log.error("payment {} reported a second transaction; held for manual review", payment.outTradeNo());
                return Applied.MISMATCH;
            }
            if ("MISMATCH".equals(payment.status())) {
                return Applied.MISMATCH;
            }
            if (!PENDING.equals(order.status()) && !CLOSED.equals(order.status())) {
                // 订单已经由另一张支付单付过：这笔钱要退，交给人工。
                payments.markMismatch(payment.id(), now);
                log.error("order {} was paid twice; payment {} is held for a refund", order.id(), payment.outTradeNo());
                return Applied.MISMATCH;
            }

            Instant paidAt = result.succeededAt() != null ? result.succeededAt() : now;
            payments.markSucceeded(payment.id(), result.transactionId(), paidAt, now);
            payments.markOrderPaid(order.id(), paidAt, now);
            // 只有自提的商城订单有取货码；没有配送行的业务来源订单（E09）这一句不改任何行。
            payments.assignPickupCode(order.id(), pickupCodes.get(), now);
            orders.insertTransition(order.id(), order.status(), TradeOrderStatus.PAID.name(), cause, null, now);
            if (CLOSED.equals(order.status())) {
                log.warn("order {} was paid after it closed; reopened as paid", order.id());
            }
            // 业务来源的订单（E09）：同一事务里告诉业务模块；收不下就全额自动退款，提交后交给渠道。
            events.paid(order.id(), payment.id());
            buyerEvents.paid(order.id(), now);
            return Applied.APPLIED;
        });
    }

    private static String mismatch(PaymentChannel channel, PaymentQuery result, PaymentRow payment) {
        if (result.spMchid() == null || !result.spMchid().equals(channel.providerId())) {
            return "service provider";
        }
        if (result.subMchid() == null || !result.subMchid().equals(payment.subMchid())) {
            return "sub merchant";
        }
        if (!payment.outTradeNo().equals(result.outTradeNo())) {
            return "out_trade_no";
        }
        if (result.totalMinor() == null || result.totalMinor() != payment.amountMinor()) {
            return "amount";
        }
        if (result.currency() != null && !"CNY".equals(result.currency())) {
            return "currency";
        }
        return null;
    }

    // ---- 关单前的查单 ----

    private enum Settled {
        /** 付了（或要人工核对）：订单不能关。 */
        PAID,
        /** 确认没付，渠道那边也关了：本地支付单已关闭。 */
        CLOSED
    }

    private Settled settle(PaymentChannel channel, PaymentRow open) {
        PaymentQuery result = query(channel, open);
        switch (result.state()) {
            case SUCCESS, REFUND -> {
                apply(channel, result, "PAYMENT_QUERIED");
                return Settled.PAID;
            }
            case NOTPAY -> {
                try {
                    channel.closePayment(open.subMchid(), open.outTradeNo());
                } catch (PaymentChannelException exception) {
                    // 关单被拒，多半是查单之后买家刚好付了：再查一次。
                    PaymentQuery again = query(channel, open);
                    if (again.state() == PaymentState.SUCCESS || again.state() == PaymentState.REFUND) {
                        apply(channel, again, "PAYMENT_QUERIED");
                        return Settled.PAID;
                    }
                    if (again.state() != PaymentState.CLOSED && again.state() != PaymentState.NOT_FOUND) {
                        throw new TradeException(Error.CHANNEL_UNAVAILABLE);
                    }
                }
                closeLocally(open);
                return Settled.CLOSED;
            }
            case CLOSED, NOT_FOUND -> {
                closeLocally(open);
                return Settled.CLOSED;
            }
            default -> {
                log.warn("payment {} is in an unexpected channel state {}", open.outTradeNo(), result.rawState());
                throw new TradeException(Error.CHANNEL_UNAVAILABLE);
            }
        }
    }

    private static void closeQuietly(PaymentChannel channel, PaymentRow payment) {
        try {
            channel.closePayment(payment.subMchid(), payment.outTradeNo());
        } catch (PaymentChannelException exception) {
            log.info("closing the orphaned prepay of payment {} failed: {} {}",
                    payment.outTradeNo(), exception.kind(), exception.code());
        }
    }

    private void closeLocally(PaymentRow open) {
        tx.executeWithoutResult(status -> {
            payments.lockOrder(open.orderId());
            payments.closePayment(open.id(), clock.instant());
        });
    }

    private PaymentQuery query(PaymentChannel channel, PaymentRow payment) {
        try {
            return channel.queryPayment(payment.subMchid(), payment.outTradeNo());
        } catch (PaymentChannelException exception) {
            log.warn("querying payment {} failed: {} {}", payment.outTradeNo(), exception.kind(), exception.code());
            throw new TradeException(Error.CHANNEL_UNAVAILABLE);
        }
    }

    // ---- 小工具 ----

    private PaymentChannel requireChannel() {
        PaymentChannel channel = channels.get();
        if (channel == null) {
            throw new TradeException(Error.CHANNEL_UNAVAILABLE);
        }
        return channel;
    }

    /** 微信收银台上显示的商品描述：商家名和第一件商品，多件时加件数。同一张单每次都一样，重试下单时内容不变。 */
    private String description(PaymentOrderRow order) {
        List<LineRow> lines = orders.findLines(List.of(order.id()));
        if (lines.isEmpty()) {
            return order.merchantName();
        }
        int pieces = lines.stream().mapToInt(LineRow::quantity).sum();
        String first = lines.getFirst().productName();
        return order.merchantName() + " " + first + (pieces > 1 ? " 等" + pieces + "件" : "");
    }

    /** “P” + 北京日期 + 12 位随机数字，共 21 位；撞号就换一个。 */
    private String newOutTradeNo(Instant now) {
        String day = DAY.format(now.atZone(BEIJING));
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "P" + day + outTradeNoSuffix.get();
            if (payments.countOutTradeNo(candidate) == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not pick an unused out_trade_no");
    }

    private static boolean fits(String value, int max) {
        return value == null || value.length() <= max;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
