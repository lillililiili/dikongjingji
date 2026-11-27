package com.lowaltitude.inspection.trade.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeSourceMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeSourceMapper.SourceOrderRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把订单的付款和关闭告诉业务模块（E09）。在改订单状态的同一个事务里调用，订单已经锁住。
 * 商城监听者处理已预占的供给，其他业务监听者处理各自的订单来源。
 *
 * <p>业务模块收不下一笔付款（{@link TradeSourceListener#onPaid} 返回 false）时，在同一个事务里建一笔全额退款
 * （发起方 SOURCE，没有操作人），提交之后交给渠道。
 */
final class TradeSourceEvents {

    static final String MALL = "MALL";
    /** 自动退款的原因：不说业务细节，买家在订单的退款记录里看到的就是这一句。 */
    static final String AUTO_REFUND_REASON = "订单已关闭，付款自动原路退回";

    private static final Logger log = LoggerFactory.getLogger(TradeSourceEvents.class);

    private final Supplier<List<TradeSourceListener>> listenerLookup;
    private final TradeSourceMapper sources;
    private final Supplier<TradeRefundService> refunds;
    private final AfterCommit afterCommit;
    private volatile List<TradeSourceListener> listeners;

    /**
     * @param listenerLookup 第一次用到时才取（业务模块的监听者反过来依赖交易模块，构造时取会成环）
     * @param refunds        同样第一次用到时才取（退款服务依赖订单服务，订单服务依赖这里）
     */
    TradeSourceEvents(
            Supplier<List<TradeSourceListener>> listenerLookup,
            TradeSourceMapper sources,
            Supplier<TradeRefundService> refunds,
            AfterCommit afterCommit
    ) {
        this.listenerLookup = listenerLookup;
        this.sources = sources;
        this.refunds = refunds;
        this.afterCommit = afterCommit;
    }

    /** 独立测试可在没有业务监听者时使用。 */
    static TradeSourceEvents none() {
        return new TradeSourceEvents(List::of, null, () -> null, Runnable::run);
    }

    /**
     * 订单刚变成已付款（paymentId 是这次入账的支付单）。业务收不下时全额自动退款。
     * 自动退款的幂等键由支付单算出：同一笔付款只会退一次。
     */
    void paid(UUID orderId, UUID paymentId) {
        SourceOrderRow order = sourceOrder(orderId);
        if (order == null) {
            return;
        }
        TradeSourceListener listener = listenerOf(order);
        if (listener == null || listener.onPaid(orderId, order.sourceRef())) {
            return;
        }
        log.warn("{} order {} was paid but its source cannot take it; refunding in full", order.source(), orderId);
        UUID key = UUID.nameUUIDFromBytes(("source-auto-refund:" + paymentId).getBytes(StandardCharsets.UTF_8));
        TradeRefundService refundService = refunds.get();
        UUID refundId = refundService.createSourceRefund(orderId, null, AUTO_REFUND_REASON, key, null);
        afterCommit.run(() -> refundService.submit(refundId));
    }

    /** 待付款的订单刚关闭（买家取消或超时）。 */
    void closed(UUID orderId) {
        SourceOrderRow order = sourceOrder(orderId);
        if (order == null) {
            return;
        }
        TradeSourceListener listener = listenerOf(order);
        if (listener != null) {
            listener.onClosed(orderId, order.sourceRef());
        }
    }

    /** 没有业务监听者时省去来源查询。商城通过订单 ID 查找预占，sourceRef 可以为空。 */
    private SourceOrderRow sourceOrder(UUID orderId) {
        if (listeners().isEmpty()) {
            return null;
        }
        return sources.findSourceOrder(orderId);
    }

    private TradeSourceListener listenerOf(SourceOrderRow order) {
        for (TradeSourceListener listener : listeners()) {
            if (order.source().equals(listener.source())) {
                return listener;
            }
        }
        log.error("no listener handles {} orders; order {} is left as it is", order.source(), order.id());
        return null;
    }

    private List<TradeSourceListener> listeners() {
        List<TradeSourceListener> found = listeners;
        if (found == null) {
            found = List.copyOf(listenerLookup.get());
            listeners = found;
        }
        return found;
    }
}
