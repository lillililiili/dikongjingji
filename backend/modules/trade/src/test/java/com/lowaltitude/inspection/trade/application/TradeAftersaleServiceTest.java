package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.trade.application.TradeAftersaleService.ApplyCommand;
import com.lowaltitude.inspection.trade.application.TradeAftersaleService.ReturnAddress;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.AftersaleRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.OrderFactsRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.OrderHeadRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper.RefundOrderRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** 售后服务的主要分支（S4-2）：申请、同意要寄回地址、确认收货的金额、退款结果回推、超时推进。持久层用替身。 */
class TradeAftersaleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final UUID BUYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final UUID AFTERSALE = UUID.fromString("a5000000-0000-4000-8000-000000000001");
    private static final UUID REFUND = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("f0000000-0000-4000-8000-000000000001");

    private final TradeAftersaleMapper mapper = mock(TradeAftersaleMapper.class);
    private final TradeRefundMapper refunds = mock(TradeRefundMapper.class);
    private final TradeRefundService refundService = mock(TradeRefundService.class);
    private TradeAftersaleService service;

    @BeforeEach
    void setUp() {
        service = new TradeAftersaleService(mapper, refunds, refundService, TradeAftersaleImageStore.unavailable(),
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC), () -> "00000001");
        when(refundService.channelReady()).thenReturn(true);
        when(mapper.findOrderHead(ORDER)).thenReturn(new OrderHeadRow("MO2026112300000001", MERCHANT, "示例机器人商家", null));
        when(mapper.countAftersaleNo(anyString())).thenReturn(0);
    }

    private void order(String status, Instant completedAt) {
        RefundOrderRow row = new RefundOrderRow(ORDER, BUYER, MERCHANT, status, "1900000109", 129_900, 0,
                NOW.minusSeconds(86_400), 3);
        when(refunds.lockBuyerOrder(BUYER, ORDER)).thenReturn(row);
        when(refunds.lockOrder(ORDER)).thenReturn(row);
        when(mapper.findOrderFacts(ORDER)).thenReturn(new OrderFactsRow("MALL", completedAt, NOW.minusSeconds(3600), true));
    }

    private AftersaleRow row(String kind, String status, long requested, UUID refundId) {
        return new AftersaleRow(AFTERSALE, "AS2026112300000001", ORDER, "MO2026112300000001", BUYER, MERCHANT,
                "示例机器人商家", null, kind, "RETURN_REFUND".equals(kind) ? "NO_REASON_7D" : "NOT_WANTED", null,
                requested, null, status, null, null, null, null, null, null, null, null, refundId, NOW.plusSeconds(60),
                NOW, null, null, null, null, null, KEY, "h", 2, 129_900);
    }

    @Test
    void aReturnOnAShippedOrderWaitsFortyEightHoursForTheMerchant() {
        order("SHIPPED", null);
        when(mapper.insertAftersale(anyString(), eq(ORDER), eq(BUYER), eq(MERCHANT), eq("RETURN_REFUND"),
                eq("NO_REASON_7D"), isNull(), eq(129_900L), eq("PENDING_REVIEW"), eq(NOW.plusSeconds(48 * 3600)),
                eq(KEY), anyString(), eq(NOW))).thenReturn(AFTERSALE);
        when(mapper.findBuyerAftersale(BUYER, AFTERSALE)).thenReturn(row("RETURN_REFUND", "PENDING_REVIEW", 129_900, null));

        var result = service.apply(BUYER, ORDER, KEY, new ApplyCommand("RETURN_REFUND", "NO_REASON_7D", " ", null, List.of()));

        assertThat(result.created()).isTrue();
        assertThat(result.aftersale().status()).isEqualTo("PENDING_REVIEW");
        assertThat(result.aftersale().actions().canRevoke()).isTrue();
        verify(mapper).insertEvent(AFTERSALE, "SUBMIT", null, "PENDING_REVIEW", BUYER, NOW);
        verify(refundService, never()).createAftersaleRefund(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void aRefundOnlyOnAnUnshippedOrderRefundsEverythingStraightAway() {
        order("PAID", null);
        when(mapper.insertAftersale(anyString(), eq(ORDER), eq(BUYER), eq(MERCHANT), eq("REFUND_ONLY"), eq("NOT_WANTED"),
                any(), eq(129_900L), eq("PENDING_REVIEW"), any(), eq(KEY), anyString(), eq(NOW))).thenReturn(AFTERSALE);
        when(refundService.createAftersaleRefund(eq(ORDER), eq(129_900L), anyString(), eq("BUYER_CANCEL"), eq(BUYER),
                eq(TradeAftersaleService.refundKey(AFTERSALE)))).thenReturn(REFUND);
        when(mapper.findBuyerAftersale(BUYER, AFTERSALE)).thenReturn(row("REFUND_ONLY", "REFUNDING", 129_900, REFUND));

        var result = service.apply(BUYER, ORDER, KEY, new ApplyCommand("REFUND_ONLY", "NOT_WANTED", null, null, null));

        assertThat(result.aftersale().status()).isEqualTo("REFUNDING");
        verify(mapper).changeStatus(AFTERSALE, "PENDING_REVIEW", "REFUNDING", null, NOW);
        verify(refundService).submit(REFUND);
    }

    @Test
    void aReasonThatDoesNotFitTheOrderIsRefusedWithWhy() {
        order("PAID", null);

        assertThatThrownBy(() -> service.apply(BUYER, ORDER, KEY,
                new ApplyCommand("RETURN_REFUND", "DAMAGED", null, null, null)))
                .isInstanceOfSatisfying(OpsException.class, refused -> {
                    assertThat(refused.error()).isEqualTo(TradeAftersaleError.NOT_ELIGIBLE);
                    assertThat(refused.details()).containsEntry("reason", "NOT_SHIPPED");
                });
        assertThatThrownBy(() -> service.apply(BUYER, ORDER, KEY,
                new ApplyCommand("RETURN_REFUND", "NOT_WANTED", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void askingForMoreThanIsLeftIsRefused() {
        order("SHIPPED", null);

        assertThatThrownBy(() -> service.apply(BUYER, ORDER, KEY,
                new ApplyCommand("RETURN_REFUND", "DAMAGED", null, 130_000L, null)))
                .isInstanceOfSatisfying(OpsException.class,
                        refused -> assertThat(refused.error()).isEqualTo(TradeAftersaleError.AMOUNT_EXCEEDS));
    }

    @Test
    void agreeingNeedsAReturnAddressGivenOrSaved() {
        when(mapper.findMerchantAftersale(MERCHANT, AFTERSALE)).thenReturn(row("RETURN_REFUND", "PENDING_REVIEW", 129_900, null));
        when(mapper.lockAftersale(AFTERSALE)).thenReturn(row("RETURN_REFUND", "PENDING_REVIEW", 129_900, null));

        assertThatThrownBy(() -> service.agree(MERCHANT, OPERATOR, AFTERSALE, 2, null, false))
                .isInstanceOfSatisfying(OpsException.class,
                        refused -> assertThat(refused.error()).isEqualTo(TradeAftersaleError.RETURN_ADDRESS_REQUIRED));
        assertThatThrownBy(() -> service.agree(MERCHANT, OPERATOR, AFTERSALE, 1,
                new ReturnAddress("售后组", "0546-0000000", "东营区示例路 120 号", null), false))
                .isInstanceOfSatisfying(OpsException.class,
                        refused -> assertThat(refused.error()).isEqualTo(TradeAftersaleError.VERSION_CONFLICT));

        service.agree(MERCHANT, OPERATOR, AFTERSALE, 2,
                new ReturnAddress("售后组", "0546-0000000", "东营区示例路 120 号", null), true);

        verify(mapper).changeStatus(AFTERSALE, "PENDING_REVIEW", "AWAITING_RETURN", NOW.plusSeconds(7 * 86_400), NOW);
        verify(mapper).saveReturnAddress(MERCHANT, "售后组", "0546-0000000", "东营区示例路 120 号", null, OPERATOR, NOW);
    }

    @Test
    void refundingLessThanAskedNeedsANoteAndNeverMore() {
        when(mapper.findMerchantAftersale(MERCHANT, AFTERSALE)).thenReturn(row("RETURN_REFUND", "RETURN_SHIPPED", 129_900, null));
        when(mapper.lockAftersale(AFTERSALE)).thenReturn(row("RETURN_REFUND", "RETURN_SHIPPED", 129_900, null));

        assertThatThrownBy(() -> service.confirmReceipt(MERCHANT, OPERATOR, AFTERSALE, 2, 130_000L, null))
                .isInstanceOfSatisfying(OpsException.class,
                        refused -> assertThat(refused.error()).isEqualTo(TradeAftersaleError.AMOUNT_EXCEEDS));
        assertThatThrownBy(() -> service.confirmReceipt(MERCHANT, OPERATOR, AFTERSALE, 2, 100_000L, null))
                .isInstanceOf(IllegalArgumentException.class);

        when(refundService.createAftersaleRefund(eq(ORDER), eq(100_000L), anyString(), eq("MERCHANT"), eq(OPERATOR), any()))
                .thenReturn(REFUND);
        service.confirmReceipt(MERCHANT, OPERATOR, AFTERSALE, 2, 100_000L, "少了充电线");

        verify(mapper).saveRefund(AFTERSALE, REFUND, 100_000L, "少了充电线", true, NOW);
        verify(mapper).changeStatus(AFTERSALE, "RETURN_SHIPPED", "REFUNDING", null, NOW);
        verify(refundService).submit(REFUND);
    }

    // 表上的约束要求“未同意”“验货未通过”的单有理由：先写理由，再改状态。
    @Test
    void aRejectionWritesTheReasonBeforeTheStatus() {
        when(mapper.findMerchantAftersale(MERCHANT, AFTERSALE)).thenReturn(row("RETURN_REFUND", "PENDING_REVIEW", 129_900, null));
        when(mapper.lockAftersale(AFTERSALE)).thenReturn(row("RETURN_REFUND", "PENDING_REVIEW", 129_900, null));

        service.reject(MERCHANT, OPERATOR, AFTERSALE, 2, "凭证不足", null);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper).saveRejection(AFTERSALE, "凭证不足", null, false, NOW);
        order.verify(mapper).changeStatus(AFTERSALE, "PENDING_REVIEW", "REJECTED", null, NOW);
    }

    @Test
    void theRefundResultMovesTheAftersaleOnce() {
        when(mapper.lockByRefund(REFUND)).thenReturn(row("RETURN_REFUND", "REFUNDING", 129_900, REFUND));

        service.settled(REFUND, true, NOW);

        verify(mapper).changeStatus(AFTERSALE, "REFUNDING", "REFUNDED", null, NOW);
        verify(mapper).markRefunded(AFTERSALE, NOW);

        when(mapper.lockByRefund(REFUND)).thenReturn(row("RETURN_REFUND", "REFUNDED", 129_900, REFUND));
        service.settled(REFUND, false, NOW);
        verify(mapper, never()).changeStatus(AFTERSALE, "REFUNDING", "REFUND_FAILED", null, NOW);
    }

    @Test
    void anUnansweredApplicationIsAgreedAutomaticallyAndAnUnshippedReturnCloses() {
        order("SHIPPED", null);
        when(mapper.findDue(NOW, TradeAftersaleService.DUE_BATCH)).thenReturn(List.of(
                new TradeAftersaleMapper.DueRow(AFTERSALE, ORDER, "PENDING_REVIEW")));
        AftersaleRow due = new AftersaleRow(AFTERSALE, "AS1", ORDER, "MO1", BUYER, MERCHANT, "示例机器人商家", null,
                "RETURN_REFUND", "DAMAGED", null, 129_900, null, "PENDING_REVIEW", null, null, null, null, null, null,
                null, null, null, NOW.minusSeconds(1), NOW, null, null, null, null, null, KEY, "h", 0, 129_900);
        when(mapper.lockAftersale(AFTERSALE)).thenReturn(due);

        assertThat(service.processDeadlines()).isEqualTo(1);

        verify(mapper).changeStatus(AFTERSALE, "PENDING_REVIEW", "AWAITING_RETURN", NOW.plusSeconds(7 * 86_400), NOW);
        verify(mapper).insertEvent(AFTERSALE, "AUTO_AGREE", "PENDING_REVIEW", "AWAITING_RETURN", null, NOW);
        verify(mapper, never()).saveRefund(any(), any(), anyLong(), any(), anyBoolean(), any());
        verify(mapper, never()).changeStatus(eq(AFTERSALE), eq("AWAITING_RETURN"), anyString(), any(), any());
        verify(mapper, never()).attachImage(any(), any(), anyInt());
    }
}
