package com.lowaltitude.inspection.trade.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lowaltitude.inspection.trade.application.TradeOrderCompletionListener.CompletedLine;
import com.lowaltitude.inspection.trade.application.TradeOrderCompletionListener.CompletedOrder;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.OrderRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把商城订单的完成告诉 {@link TradeOrderCompletionListener}（S5-5）。在改订单的同一个事务里调用。
 * 只管商城订单；没有监听者时连订单都不读。监听者出错只记日志，不让完成失败。
 */
final class TradeCompletionEvents {

    private static final Logger log = LoggerFactory.getLogger(TradeCompletionEvents.class);

    private final TradeOrderMapper orders;
    private final Supplier<List<TradeOrderCompletionListener>> listenerLookup;

    /** @param listenerLookup 第一次用到时才取（监听者依赖的业务服务反过来依赖交易模块，构造时取会成环） */
    TradeCompletionEvents(TradeOrderMapper orders, Supplier<List<TradeOrderCompletionListener>> listenerLookup) {
        this.orders = orders;
        this.listenerLookup = listenerLookup;
    }

    static TradeCompletionEvents none() {
        return new TradeCompletionEvents(null, List::of);
    }

    void completed(UUID orderId, Instant at) {
        List<TradeOrderCompletionListener> listeners = listenerLookup.get();
        if (listeners.isEmpty() || orders == null) {
            return;
        }
        CompletedOrder order;
        try {
            OrderRow row = orders.findOrder(orderId);
            if (row == null || !TradeSourceEvents.MALL.equals(row.source())) {
                return;
            }
            List<CompletedLine> lines = orders.findLines(List.of(orderId)).stream()
                    .map(line -> new CompletedLine(line.productId(), line.offerId(), line.productName(), line.skuName(),
                            line.quantity()))
                    .toList();
            order = new CompletedOrder(row.id(), row.orderNo(), row.buyerId(), at, lines);
        } catch (RuntimeException exception) {
            log.error("reading completed order {} for its listeners failed", orderId, exception);
            return;
        }
        for (TradeOrderCompletionListener listener : listeners) {
            try {
                listener.onCompleted(order);
            } catch (RuntimeException exception) {
                log.error("telling {} that order {} completed failed", listener.getClass().getName(), orderId, exception);
            }
        }
    }
}
