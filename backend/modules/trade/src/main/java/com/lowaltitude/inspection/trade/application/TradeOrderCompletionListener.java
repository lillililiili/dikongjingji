package com.lowaltitude.inspection.trade.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 商城订单完成（确认收货、自动确认收货、自提核销）时，告诉关心它的业务模块（S5-5：商品附带的数字课程随即开通）。
 * 在交易模块把订单改成已完成的同一个事务里调用，订单已经锁住；交易模块不依赖任何业务模块。
 * 这是附带的：实现抛异常只记日志，不让收货、核销失败，所以实现要自己保证幂等（同一张单可能因重试再告诉一次）。
 */
public interface TradeOrderCompletionListener {

    void onCompleted(CompletedOrder order);

    /** 完成的商城订单：买家、订单编号和每一行商品。 */
    record CompletedOrder(UUID orderId, String orderNo, UUID buyerId, Instant completedAt, List<CompletedLine> lines) {
    }

    /** 订单行快照：商品、供给（商家 × 规格 × 城市）、商品名和规格名、件数。 */
    record CompletedLine(UUID productId, UUID offerId, String productName, String skuName, int quantity) {
    }
}
