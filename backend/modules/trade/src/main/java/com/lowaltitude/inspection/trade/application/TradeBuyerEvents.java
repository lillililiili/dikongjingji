package com.lowaltitude.inspection.trade.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.lowaltitude.inspection.trade.application.TradeBuyerNotifier.OrderFacts;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.LineRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.OrderRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把发货、付款到账、退款到账告诉 {@link TradeBuyerNotifier}（R6-5）。在改订单的同一个事务里调用。
 * 通知是附带的：读订单或通知本身出错只记日志，不让发货、入账、退款失败。
 * 没有接站内信时（{@link #none()}）连订单都不读。
 */
final class TradeBuyerEvents {

    static final String MALL = "MALL";
    static final String PICKUP = "PICKUP";

    private static final Logger log = LoggerFactory.getLogger(TradeBuyerEvents.class);

    /** 订单的事实和配送方式；订单不存在时为 null。 */
    record Loaded(OrderFacts facts, String deliveryMode) {
    }

    private final Function<UUID, Loaded> loader;
    private final TradeBuyerNotifier notifier;

    TradeBuyerEvents(TradeOrderMapper orders, TradeBuyerNotifier notifier) {
        this(orderId -> load(orders, orderId), notifier);
    }

    TradeBuyerEvents(Function<UUID, Loaded> loader, TradeBuyerNotifier notifier) {
        this.loader = loader;
        this.notifier = notifier;
    }

    static TradeBuyerEvents none() {
        return new TradeBuyerEvents((Function<UUID, Loaded>) null, null);
    }

    /** 订单刚变成已付款：只有自提的商城订单要说“可以自提了”（取货码同时生成）。 */
    void paid(UUID orderId, Instant at) {
        report(orderId, "ready for pickup", loaded -> {
            if (MALL.equals(loaded.facts().source()) && PICKUP.equals(loaded.deliveryMode())) {
                notifier.readyForPickup(loaded.facts(), at);
            }
        });
    }

    void shipped(UUID orderId, Instant at) {
        report(orderId, "shipped", loaded -> notifier.shipped(loaded.facts(), at));
    }

    void refunded(UUID orderId, UUID refundId, long amountMinor, Instant at) {
        report(orderId, "refunded", loaded -> notifier.refunded(loaded.facts(), refundId, amountMinor, at));
    }

    private void report(UUID orderId, String what, java.util.function.Consumer<Loaded> action) {
        if (loader == null) {
            return;
        }
        try {
            Loaded loaded = loader.apply(orderId);
            if (loaded == null) {
                log.warn("order {} was not found; the buyer is not told it was {}", orderId, what);
                return;
            }
            action.accept(loaded);
        } catch (RuntimeException exception) {
            log.error("telling the buyer that order {} was {} failed", orderId, what, exception);
        }
    }

    private static Loaded load(TradeOrderMapper orders, UUID orderId) {
        OrderRow order = orders.findOrder(orderId);
        if (order == null) {
            return null;
        }
        List<LineRow> lines = orders.findLines(List.of(orderId));
        String product = lines.isEmpty() ? null : lines.getFirst().productName();
        int pieces = lines.stream().mapToInt(LineRow::quantity).sum();
        return new Loaded(new OrderFacts(order.id(), order.buyerId(), order.source(), order.sourceRef(),
                order.merchantName(), product, pieces), order.deliveryMode());
    }
}
