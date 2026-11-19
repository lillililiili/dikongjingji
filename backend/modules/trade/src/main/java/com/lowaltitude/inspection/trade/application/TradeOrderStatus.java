package com.lowaltitude.inspection.trade.application;

import java.util.List;

/** 订单状态（契约 TradeOrderStatus）。部分退款不改状态，看已退金额。 */
public enum TradeOrderStatus {
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    COMPLETED,
    CLOSED,
    REFUNDING,
    REFUNDED,
    REFUND_FAILED;

    /** 我的订单的页签对应哪些状态；ALL 是全部。 */
    static List<String> ofTab(String tab) {
        String code = tab == null || tab.isBlank() ? "ALL" : tab.trim();
        return switch (code) {
            case "ALL" -> List.of();
            case "TO_PAY" -> List.of(PENDING_PAYMENT.name());
            case "TO_SHIP" -> List.of(PAID.name());
            case "TO_RECEIVE" -> List.of(SHIPPED.name());
            case "DONE" -> List.of(COMPLETED.name());
            case "REFUND" -> List.of(REFUNDING.name(), REFUNDED.name(), REFUND_FAILED.name());
            default -> throw new IllegalArgumentException("order tab is invalid");
        };
    }
}
