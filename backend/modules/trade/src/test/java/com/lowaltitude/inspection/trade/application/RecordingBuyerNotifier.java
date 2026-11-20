package com.lowaltitude.inspection.trade.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 记下交易模块告诉买家的每件事（R6-5），一行一件：“事件 订单 [退款 金额] 时间”。 */
final class RecordingBuyerNotifier implements TradeBuyerNotifier {

    final List<String> told = new ArrayList<>();
    final List<OrderFacts> facts = new ArrayList<>();

    @Override
    public void shipped(OrderFacts order, Instant at) {
        facts.add(order);
        told.add("shipped " + order.orderId() + " " + at);
    }

    @Override
    public void readyForPickup(OrderFacts order, Instant at) {
        facts.add(order);
        told.add("readyForPickup " + order.orderId() + " " + at);
    }

    @Override
    public void refunded(OrderFacts order, UUID refundId, long amountMinor, Instant at) {
        facts.add(order);
        told.add("refunded " + order.orderId() + " " + refundId + " " + amountMinor + " " + at);
    }
}
