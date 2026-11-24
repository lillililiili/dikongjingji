package com.lowaltitude.inspection.ops;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.OpsUserLookupService;
import com.lowaltitude.inspection.identity.application.account.OpsUserLookupService.ViewTarget;
import com.lowaltitude.inspection.trade.application.OpsOrderLookupService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端订单、用户查询：两种平台角色都能查，别的角色不行；详情读到了才记查看记录。 */
class OpsLookupControllerTest {

    private static final UUID ORDER_ID = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final OpsOrderLookupService orders = mock(OpsOrderLookupService.class);
    private final OpsUserLookupService users = mock(OpsUserLookupService.class);
    private final MockMvc mvc = OpsTestSupport.mockMvc(
            new OpsLookupController(orders, users, OpsTestSupport.actors()));

    @Test
    void operatorsAndSupportCanSearchOtherRolesCannot() throws Exception {
        when(orders.search(any(), any(), any(), any(), any(), any(), eq(1), eq(20)))
                .thenReturn(new OpsOrderLookupService.OpsOrderPageView(1, 20, 0, false, List.of()));
        when(users.search(any(), eq(1), eq(20)))
                .thenReturn(new OpsUserLookupService.OpsUserPageView(1, 20, 0, false, List.of()));

        mvc.perform(get("/api/v1/ops/orders").param("q", "13800000000").param("from", "2026-11-01")
                        .param("to", "2026-11-23").principal(OpsTestSupport.support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        verify(orders).search("13800000000", null, null, null, LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-23"), 1, 20);
        mvc.perform(get("/api/v1/ops/users").param("q", "王").principal(OpsTestSupport.operator()))
                .andExpect(status().isOk());

        var customer = OpsTestSupport.signedIn(USER_ID, "CUSTOMER");
        var merchant = OpsTestSupport.signedIn(USER_ID, "PROVIDER_OPERATOR");
        mvc.perform(get("/api/v1/ops/orders").principal(customer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ops/users").principal(merchant)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ops/users/" + USER_ID).principal(customer)).andExpect(status().isForbidden());
        verify(users, never()).recordView(any(), any(), any());
    }

    @Test
    void openingADetailLeavesAViewRecord() throws Exception {
        when(orders.detail(ORDER_ID)).thenReturn(Optional.of(new OpsOrderLookupService.OpsOrderDetailView(
                ORDER_ID, "MO2026112300000001", OpsOrderLookupService.OpsOrderSource.MALL,
                com.lowaltitude.inspection.trade.application.TradeOrderStatus.PAID, "370500", "示例商家",
                new OpsOrderLookupService.OpsBuyerView(USER_ID, "王女士（示例）", "138****2026"), null, "无人机",
                1, 100, 100, 0, OffsetDateTime.parse("2026-11-23T02:00:00Z"), null, null, "EXPRESS", 100, 0,
                null, null, null, null, null, List.of(), null, List.of(), List.of(), List.of(), List.of())));
        when(users.detail(eq(USER_ID), any())).thenReturn(Optional.of(new OpsUserLookupService.OpsUserDetailView(
                USER_ID, "王女士（示例）", "dev-customer", "138****2026", OpsUserLookupService.OpsUserStatus.ACTIVE,
                "PASSWORD", List.of("CUSTOMER"), OffsetDateTime.parse("2026-11-01T00:00:00Z"), null, null,
                List.of(), List.of(), 1, null, 3)));

        mvc.perform(get("/api/v1/ops/orders/" + ORDER_ID).principal(OpsTestSupport.support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyer.maskedPhone").value("138****2026"));
        verify(users).recordView(OpsTestSupport.SUPPORT_ID, ViewTarget.TRADE_ORDER, ORDER_ID);

        mvc.perform(get("/api/v1/ops/users/" + USER_ID).principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(3));
        verify(users).recordView(OpsTestSupport.OPERATOR_ID, ViewTarget.USER, USER_ID);
    }

    @Test
    void missingOnesAre404AndLeaveNoRecord() throws Exception {
        when(orders.detail(any())).thenReturn(Optional.empty());
        when(users.detail(any(), any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/ops/orders/" + ORDER_ID).principal(OpsTestSupport.operator()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mvc.perform(get("/api/v1/ops/users/" + USER_ID).principal(OpsTestSupport.support()))
                .andExpect(status().isNotFound());
        verify(users, never()).recordView(any(), any(), any());
    }
}
