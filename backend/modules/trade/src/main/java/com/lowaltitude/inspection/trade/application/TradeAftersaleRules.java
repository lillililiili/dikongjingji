package com.lowaltitude.inspection.trade.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 商品售后的规则（S4-2，A1 默认决定；时限是设计假设，写在 docs/redesign/08-后续开发计划.md 的 S4-2 报告里）：
 * <ul>
 *   <li>两类：退货退款（已发货、已自提或已收货的订单）和仅退款（已付款、未发货未自提的订单，按 §19.7 的取消规则直接全额原路退回，
 *       不用商家同意）。</li>
 *   <li>退货退款的原因：七天无理由（每件商品都没标“不支持七天无理由”，且确认收货后 7 天内；还没确认收货时一直可以）；
 *       质量问题——与描述不符、商品有损坏（确认收货后 15 天内，参照三包的退换期；超过的走售后责任方的保修）。</li>
 *   <li>一张订单同时只有一张没结束的售后单，一共最多 3 张；申请金额不超过实付减已退减在路上的退款。</li>
 *   <li>时限：商家 48 小时不处理自动同意；同意后 7 天不填运单自动关闭；运单提交后 10 天商家不确认也不拒绝，
 *       按申请金额自动原路退款。</li>
 * </ul>
 */
final class TradeAftersaleRules {

    static final Duration NO_REASON_WINDOW = Duration.ofDays(7);
    static final Duration QUALITY_WINDOW = Duration.ofDays(15);
    static final Duration REVIEW_WINDOW = Duration.ofHours(48);
    static final Duration RETURN_WINDOW = Duration.ofDays(7);
    static final Duration RECEIVE_WINDOW = Duration.ofDays(10);
    static final int MAX_PER_ORDER = 3;

    enum Kind { RETURN_REFUND, REFUND_ONLY }

    enum Reason {
        NO_REASON_7D(Kind.RETURN_REFUND),
        NOT_AS_DESCRIBED(Kind.RETURN_REFUND),
        DAMAGED(Kind.RETURN_REFUND),
        NOT_WANTED(Kind.REFUND_ONLY);

        final Kind kind;

        Reason(Kind kind) {
            this.kind = kind;
        }
    }

    enum Status {
        PENDING_REVIEW, AWAITING_RETURN, RETURN_SHIPPED, REFUNDING, REFUNDED, REFUND_FAILED,
        REJECTED, RECEIPT_REJECTED, REVOKED, CLOSED
    }

    /** 没结束的售后单（一张订单同时只有一张）。 */
    static final Set<String> OPEN = Set.of("PENDING_REVIEW", "AWAITING_RETURN", "RETURN_SHIPPED", "REFUNDING");

    /** 列表三个页签：售后中、已完成、已关闭。 */
    static List<String> ofTab(String tab) {
        String code = tab == null || tab.isBlank() ? "ALL" : tab.trim();
        return switch (code) {
            case "ALL" -> List.of();
            case "DOING" -> List.of("PENDING_REVIEW", "AWAITING_RETURN", "RETURN_SHIPPED", "REFUNDING", "REFUND_FAILED");
            case "DONE" -> List.of("REFUNDED");
            case "CLOSED" -> List.of("REJECTED", "RECEIPT_REJECTED", "REVOKED", "CLOSED");
            default -> throw new IllegalArgumentException("aftersale tab is invalid");
        };
    }

    /** 商家工作台按一个状态筛；不传是全部。 */
    static List<String> ofStatus(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        try {
            return List.of(Status.valueOf(status.trim()).name());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("aftersale status is invalid");
        }
    }

    /**
     * 判断要用到的订单事实。completedAt 是确认收货（或自提核销、自动确认）的时间；refundableMinor 是实付减已退减在路上的退款。
     */
    record OrderFacts(
            boolean mall,
            TradeOrderStatus status,
            Instant completedAt,
            boolean everyLineNoReasonReturn,
            boolean openAftersale,
            long aftersaleCount,
            long refundableMinor
    ) {
    }

    private TradeAftersaleRules() {
    }

    /** 这一类现在不能选的原因；能选时为 null。 */
    static String kindBlock(OrderFacts facts, Kind kind) {
        String common = commonBlock(facts);
        if (common != null) {
            return common;
        }
        return switch (kind) {
            case REFUND_ONLY -> facts.status() == TradeOrderStatus.PAID ? null : "ALREADY_SHIPPED";
            case RETURN_REFUND -> facts.status() == TradeOrderStatus.SHIPPED || facts.status() == TradeOrderStatus.COMPLETED
                    ? null : "NOT_SHIPPED";
        };
    }

    /** 这个原因现在不能选的原因；能选时为 null。 */
    static String reasonBlock(OrderFacts facts, Reason reason, Instant now) {
        String kind = kindBlock(facts, reason.kind);
        if (kind != null) {
            return kind;
        }
        return switch (reason) {
            case NOT_WANTED -> null;
            case NO_REASON_7D -> !facts.everyLineNoReasonReturn()
                    ? "NO_REASON_NOT_SUPPORTED"
                    : withinWindow(facts, NO_REASON_WINDOW, now) ? null : "WINDOW_PASSED";
            case NOT_AS_DESCRIBED, DAMAGED -> withinWindow(facts, QUALITY_WINDOW, now) ? null : "WINDOW_PASSED";
        };
    }

    /** 订单上“申请售后”按钮：至少有一个原因能选。 */
    static boolean applicable(OrderFacts facts, Instant now) {
        for (Reason reason : Reason.values()) {
            if (reasonBlock(facts, reason, now) == null) {
                return true;
            }
        }
        return false;
    }

    private static String commonBlock(OrderFacts facts) {
        if (!facts.mall()) {
            return "ORDER_NOT_ELIGIBLE";
        }
        switch (facts.status()) {
            case PAID, SHIPPED, COMPLETED -> {
            }
            case PENDING_PAYMENT, CLOSED -> {
                return "ORDER_NOT_PAID";
            }
            default -> {
                return "ORDER_REFUNDED";
            }
        }
        if (facts.openAftersale()) {
            return "OPEN_AFTERSALE";
        }
        if (facts.aftersaleCount() >= MAX_PER_ORDER) {
            return "LIMIT_REACHED";
        }
        if (facts.refundableMinor() <= 0) {
            return "NOTHING_TO_REFUND";
        }
        return null;
    }

    private static boolean withinWindow(OrderFacts facts, Duration window, Instant now) {
        return facts.completedAt() == null || !now.isAfter(facts.completedAt().plus(window));
    }
}
