package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOpsLookupMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOpsLookupMapper.DeliveryRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOpsLookupMapper.OrderExtraRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOpsLookupMapper.OrderSummaryRow;
import org.junit.jupiter.api.Test;

/** 运营端订单查询：q 的三种用法、日期按北京时间、打码。 */
class OpsOrderLookupServiceTest {

    private static final UUID ORDER = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final UUID BUYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final TradeOpsLookupMapper mapper = mock(TradeOpsLookupMapper.class);
    private final OpsOrderLookupService service = new OpsOrderLookupService(mapper);

    private static OrderSummaryRow row(String phone) {
        return new OrderSummaryRow(ORDER, "MO2026112300000001", "MALL", "PAID", "370500", "示例商家", BUYER,
                "王女士（示例）", phone, null, "无人机", 2, 12_800, 12_800, 0,
                OffsetDateTime.parse("2026-11-23T02:00:00Z"), OffsetDateTime.parse("2026-11-23T02:01:00Z"));
    }

    @Test
    void queryIsAPhoneAnOrderIdOrANumber() {
        when(mapper.countOrders(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);

        service.search(" 13800002026 ", null, null, null, null, null, 1, 20);
        verify(mapper).countOrders(eq("13800002026"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull());
        service.search(ORDER.toString(), null, null, null, null, null, 1, 20);
        verify(mapper).countOrders(isNull(), eq(ORDER), isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
        service.search("4200001234202611230000000001", BUYER, "MALL", "PAID", null, null, 1, 20);
        verify(mapper).countOrders(isNull(), isNull(), eq("4200001234202611230000000001"), eq(BUYER), eq("MALL"),
                eq("PAID"), isNull(), isNull());
        // 12 位数字不是手机号，按单号查。
        service.search("138000020261", null, null, null, null, null, 1, 20);
        verify(mapper).countOrders(isNull(), isNull(), eq("138000020261"), isNull(), isNull(), isNull(), isNull(),
                isNull());
    }

    @Test
    void datesAreWholeBeijingDaysIncludingTheLastOne() {
        when(mapper.countOrders(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);

        service.search(null, null, null, null, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-23"), 1, 20);

        verify(mapper).countOrders(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(OffsetDateTime.parse("2026-11-01T00:00+08:00")), eq(OffsetDateTime.parse("2026-11-24T00:00+08:00")));
    }

    @Test
    void badArgumentsAreRejected() {
        assertThatThrownBy(() -> service.search("x".repeat(65), null, null, null, null, null, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(null, null, "GIFT", null, null, null, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(null, null, null, "LOST", null, null, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(null, null, null, null, LocalDate.parse("2026-11-23"),
                LocalDate.parse("2026-11-01"), 1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(null, null, null, null, LocalDate.parse("2025-11-01"),
                LocalDate.parse("2026-11-23"), 1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(null, null, null, null, null, null, 1, 51))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listMasksTheBuyerPhone() {
        when(mapper.countOrders(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(21L);
        when(mapper.findOrders(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("13800002026")));

        var page = service.search(null, null, null, null, null, null, 2, 10);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.buyer().maskedPhone()).isEqualTo("138****2026");
            assertThat(item.source()).isEqualTo(OpsOrderLookupService.OpsOrderSource.MALL);
            assertThat(item.itemCount()).isEqualTo(2);
        });
        verify(mapper).findOrders(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(10), eq(10));
    }

    @Test
    void detailMasksTheDeliveryAndLeavesOutTheAddress() {
        when(mapper.findOrder(ORDER)).thenReturn(row(null));
        when(mapper.findOrderExtra(ORDER)).thenReturn(new OrderExtraRow(null, "EXPRESS", 12_000, 800, "周末送",
                null, null, null, null));
        when(mapper.findDelivery(ORDER)).thenReturn(new DeliveryRow("EXPRESS", "王小明", "13900001234", "东营市",
                null, "顺丰速运", "SF1234567890"));

        var detail = service.detail(ORDER).orElseThrow();

        assertThat(detail.buyer().maskedPhone()).isNull();
        assertThat(detail.delivery().recipientMasked()).isEqualTo("王*");
        assertThat(detail.delivery().phoneMasked()).isEqualTo("139****1234");
        assertThat(detail.delivery().trackingNo()).isEqualTo("SF1234567890");
        assertThat(detail.buyerNote()).isEqualTo("周末送");
        assertThat(service.detail(UUID.randomUUID())).isEmpty();
    }
}
