package com.lowaltitude.inspection.trade.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 买家要知道的订单事件（R6-5，docs/redesign/06-原型对齐修正计划.md）：发货、可以自提了、退款到账。
 * 交易模块只认这个端口，启动模块把它接到站内信；两个模块互不依赖。
 *
 * <p>在改订单的同一个事务里调用，订单已经锁住。实现者自己决定什么时候写（启动模块在事务提交之后写），
 * 而且不能让交易失败：交易模块会吞掉实现抛出的异常、只记日志。
 */
public interface TradeBuyerNotifier {

    /** 快递单已发货。 */
    void shipped(OrderFacts order, Instant at);

    /** 自提的商城订单付款到账、有了取货码，可以去提货了。 */
    void readyForPickup(OrderFacts order, Instant at);

    /** 一笔退款到账了（部分退款也算）。refundId 是交易模块的退款单，同一笔只报一次。 */
    void refunded(OrderFacts order, UUID refundId, long amountMinor, Instant at);

    /**
     * 订单里能放进通知的事实：买家、来源、业务单、商家名和第一件商品的名字、总件数。不带地址、电话、取货码。
     *
     * @param source    订单来源：MALL、ACTIVITY、VENUE、RENTAL
     * @param sourceRef 业务单 ID（报名单、到馆预约、租赁单）；商城订单为空
     */
    record OrderFacts(
            UUID orderId,
            UUID buyerId,
            String source,
            UUID sourceRef,
            String merchantName,
            String productName,
            int pieces
    ) {
    }

    /** 没有接站内信时（只有交易模块的测试、或没有数据库的上下文）。 */
    static TradeBuyerNotifier none() {
        return new TradeBuyerNotifier() {
            @Override
            public void shipped(OrderFacts order, Instant at) {
            }

            @Override
            public void readyForPickup(OrderFacts order, Instant at) {
            }

            @Override
            public void refunded(OrderFacts order, UUID refundId, long amountMinor, Instant at) {
            }
        };
    }
}
