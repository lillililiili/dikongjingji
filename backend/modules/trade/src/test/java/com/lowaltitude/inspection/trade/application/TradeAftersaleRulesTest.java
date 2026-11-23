package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import com.lowaltitude.inspection.trade.application.TradeAftersaleRules.Kind;
import com.lowaltitude.inspection.trade.application.TradeAftersaleRules.OrderFacts;
import com.lowaltitude.inspection.trade.application.TradeAftersaleRules.Reason;
import org.junit.jupiter.api.Test;

/** 商品售后能不能申请（S4-2，A1 默认决定和设计假设的时限）。 */
class TradeAftersaleRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");

    private static OrderFacts facts(TradeOrderStatus status, Instant completedAt, boolean noReason) {
        return new OrderFacts(true, status, completedAt, noReason, false, 0, 129_900);
    }

    @Test
    void anUnshippedOrderOnlyTakesARefundWithoutReturningAnything() {
        OrderFacts paid = facts(TradeOrderStatus.PAID, null, true);

        assertThat(TradeAftersaleRules.kindBlock(paid, Kind.REFUND_ONLY)).isNull();
        assertThat(TradeAftersaleRules.kindBlock(paid, Kind.RETURN_REFUND)).isEqualTo("NOT_SHIPPED");
        assertThat(TradeAftersaleRules.reasonBlock(paid, Reason.NOT_WANTED, NOW)).isNull();
        assertThat(TradeAftersaleRules.reasonBlock(paid, Reason.NO_REASON_7D, NOW)).isEqualTo("NOT_SHIPPED");
    }

    @Test
    void aShippedOrderTakesReturnsOnlyAndNoReasonReturnsStayOpenUntilReceived() {
        OrderFacts shipped = facts(TradeOrderStatus.SHIPPED, null, true);

        assertThat(TradeAftersaleRules.kindBlock(shipped, Kind.REFUND_ONLY)).isEqualTo("ALREADY_SHIPPED");
        assertThat(TradeAftersaleRules.reasonBlock(shipped, Reason.NO_REASON_7D, NOW)).isNull();
        assertThat(TradeAftersaleRules.reasonBlock(shipped, Reason.DAMAGED, NOW)).isNull();
    }

    @Test
    void noReasonReturnsCloseSevenDaysAfterReceiptAndQualityReturnsAfterFifteen() {
        OrderFacts eightDays = facts(TradeOrderStatus.COMPLETED, NOW.minus(Duration.ofDays(8)), true);
        OrderFacts sixteenDays = facts(TradeOrderStatus.COMPLETED, NOW.minus(Duration.ofDays(16)), true);
        OrderFacts sevenDays = facts(TradeOrderStatus.COMPLETED, NOW.minus(Duration.ofDays(7)), true);

        assertThat(TradeAftersaleRules.reasonBlock(sevenDays, Reason.NO_REASON_7D, NOW)).isNull();
        assertThat(TradeAftersaleRules.reasonBlock(eightDays, Reason.NO_REASON_7D, NOW)).isEqualTo("WINDOW_PASSED");
        assertThat(TradeAftersaleRules.reasonBlock(eightDays, Reason.NOT_AS_DESCRIBED, NOW)).isNull();
        assertThat(TradeAftersaleRules.reasonBlock(sixteenDays, Reason.DAMAGED, NOW)).isEqualTo("WINDOW_PASSED");
        assertThat(TradeAftersaleRules.applicable(sixteenDays, NOW)).isFalse();
    }

    @Test
    void aProductMarkedWithoutNoReasonReturnsStillTakesQualityReturns() {
        OrderFacts marked = facts(TradeOrderStatus.SHIPPED, null, false);

        assertThat(TradeAftersaleRules.reasonBlock(marked, Reason.NO_REASON_7D, NOW)).isEqualTo("NO_REASON_NOT_SUPPORTED");
        assertThat(TradeAftersaleRules.reasonBlock(marked, Reason.NOT_AS_DESCRIBED, NOW)).isNull();
        assertThat(TradeAftersaleRules.applicable(marked, NOW)).isTrue();
    }

    @Test
    void oneOpenAftersaleAtATimeThreeInAllAndOnlyWhileMoneyIsLeft() {
        OrderFacts open = new OrderFacts(true, TradeOrderStatus.SHIPPED, null, true, true, 1, 129_900);
        OrderFacts three = new OrderFacts(true, TradeOrderStatus.SHIPPED, null, true, false, 3, 129_900);
        OrderFacts nothing = new OrderFacts(true, TradeOrderStatus.SHIPPED, null, true, false, 1, 0);

        assertThat(TradeAftersaleRules.reasonBlock(open, Reason.DAMAGED, NOW)).isEqualTo("OPEN_AFTERSALE");
        assertThat(TradeAftersaleRules.reasonBlock(three, Reason.DAMAGED, NOW)).isEqualTo("LIMIT_REACHED");
        assertThat(TradeAftersaleRules.reasonBlock(nothing, Reason.DAMAGED, NOW)).isEqualTo("NOTHING_TO_REFUND");
    }

    @Test
    void unpaidRefundedAndNonMallOrdersTakeNoAftersale() {
        assertThat(TradeAftersaleRules.applicable(facts(TradeOrderStatus.PENDING_PAYMENT, null, true), NOW)).isFalse();
        assertThat(TradeAftersaleRules.applicable(facts(TradeOrderStatus.REFUNDED, null, true), NOW)).isFalse();
        OrderFacts activity = new OrderFacts(false, TradeOrderStatus.PAID, null, true, false, 0, 24_000);
        assertThat(TradeAftersaleRules.reasonBlock(activity, Reason.NOT_WANTED, NOW)).isEqualTo("ORDER_NOT_ELIGIBLE");
    }

    @Test
    void tabsGroupTheStatuses() {
        assertThat(TradeAftersaleRules.ofTab("DOING")).contains("PENDING_REVIEW", "RETURN_SHIPPED", "REFUND_FAILED");
        assertThat(TradeAftersaleRules.ofTab("DONE")).containsExactly("REFUNDED");
        assertThat(TradeAftersaleRules.ofTab("CLOSED")).containsExactlyInAnyOrder(
                "REJECTED", "RECEIPT_REJECTED", "REVOKED", "CLOSED");
        assertThat(TradeAftersaleRules.ofTab(null)).isEmpty();
        assertThatThrownBy(() -> TradeAftersaleRules.ofTab("REFUND")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradeAftersaleRules.ofStatus("DONE")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void picturesAreRecognisedByTheirFirstBytes() {
        assertThat(TradeAftersaleService.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        assertThat(TradeAftersaleService.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0})).isEqualTo("image/png");
        assertThat(TradeAftersaleService.sniff("RIFF0000WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        assertThat(TradeAftersaleService.sniff("<svg></svg>".getBytes())).isNull();
    }

    @Test
    void oneAftersaleMakesAtMostOneRefund() {
        java.util.UUID id = java.util.UUID.randomUUID();
        assertThat(TradeAftersaleService.refundKey(id)).isEqualTo(TradeAftersaleService.refundKey(id));
        assertThat(TradeAftersaleService.refundKey(id)).isNotEqualTo(TradeAftersaleService.refundKey(java.util.UUID.randomUUID()));
    }
}
