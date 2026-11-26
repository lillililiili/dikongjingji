package com.lowaltitude.inspection.identity.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.OpsUserLookupService.OpsUserStatus;
import com.lowaltitude.inspection.identity.application.account.OpsUserLookupService.ViewTarget;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OpsUserLookupMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OpsUserLookupMapper.UserExtraRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OpsUserLookupMapper.UserRow;
import org.junit.jupiter.api.Test;

/** 运营端用户查询：q 的用法、注销冷静期、打码、查看记录。 */
class OpsUserLookupServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-23T02:00:00Z");

    private final OpsUserLookupMapper mapper = mock(OpsUserLookupMapper.class);
    private final OpsUserLookupService service = new OpsUserLookupService(mapper,
            Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC));

    private static UserRow row(String status, boolean closing, String roles) {
        return new UserRow(USER, "王女士（示例）", "dev-customer", "13800002026", status, closing, "PASSWORD", roles,
                AT);
    }

    @Test
    void queryIsAPhoneAnIdOrAName() {
        when(mapper.countUsers(any(), any(), any(), any())).thenReturn(0L);

        service.search("13800002026", 1, 20);
        verify(mapper).countUsers(eq("13800002026"), isNull(), isNull(), isNull());
        service.search(USER.toString(), 1, 20);
        verify(mapper).countUsers(isNull(), eq(USER), isNull(), isNull());
        service.search(" 王%_ ", 1, 20);
        verify(mapper).countUsers(isNull(), isNull(), eq("王%_"), eq("%王%"));
        // 超过显示名上限的只按登录名查。
        service.search("p-" + "x".repeat(30), 1, 20);
        verify(mapper).countUsers(isNull(), isNull(), eq("p-" + "x".repeat(30)), isNull());
        assertThatThrownBy(() -> service.search("x".repeat(41), 1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(null, 0, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void masksLegacyPhoneShapedUsernamesInLookupResults() {
        when(mapper.countUsers(any(), any(), any(), any())).thenReturn(1L);
        when(mapper.findUsers(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                new UserRow(USER, "客户", "13800002026", null, "ACTIVE", false, "PASSWORD", "CUSTOMER", AT)));
        var customer = service.search("13800002026", 1, 20).items().get(0);
        assertThat(customer.loginName()).isEqualTo("138****2026");
        assertThat(customer.maskedPhone()).isNull();
    }

    @Test
    void statusRolesAndPhoneAreShapedForTheOpsPage() {
        when(mapper.countUsers(any(), any(), any(), any())).thenReturn(3L);
        when(mapper.findUsers(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                row("ACTIVE", true, "CUSTOMER"),
                row("ACTIVE", false, "CUSTOMER,LIVE_HOST"),
                row("CLOSED", false, "")));

        var items = service.search(null, 1, 20).items();

        assertThat(items).extracting(OpsUserLookupService.OpsUserSummaryView::status)
                .containsExactly(OpsUserStatus.CLOSING, OpsUserStatus.ACTIVE, OpsUserStatus.CLOSED);
        assertThat(items.get(1).roles()).containsExactly("CUSTOMER", "LIVE_HOST");
        assertThat(items.get(2).roles()).isEmpty();
        assertThat(items.get(0).maskedPhone()).isEqualTo("138****2026");
    }

    @Test
    void detailAddsDevicesAndTheOrderCountFromTrade() {
        when(mapper.findUser(USER)).thenReturn(row("ACTIVE", false, "CUSTOMER"));
        when(mapper.findUserExtra(USER, AT.minusDays(30))).thenReturn(new UserExtraRow(null, null, 2, AT));
        when(mapper.findOrganizations(USER)).thenReturn(List.of());
        when(mapper.findEnterprises(USER)).thenReturn(List.of(
                new OpsUserLookupMapper.EnterpriseRow(UUID.randomUUID(), "东营港务物流（示例）", "ADMIN", "ACTIVE")));

        var detail = service.detail(USER, buyer -> 5).orElseThrow();

        assertThat(detail.activeDeviceCount()).isEqualTo(2);
        assertThat(detail.orderCount()).isEqualTo(5);
        assertThat(detail.enterprises()).singleElement()
                .satisfies(enterprise -> assertThat(enterprise.memberRole()).isEqualTo("ADMIN"));
        assertThat(service.detail(UUID.randomUUID(), buyer -> 0)).isEmpty();
    }

    @Test
    void viewRecordsNameTheActorAndTheTarget() {
        UUID actor = UUID.randomUUID();

        service.recordView(actor, ViewTarget.TRADE_ORDER, USER);

        verify(mapper).insertView(eq(actor), eq("OPS_VIEW_ORDER"), eq("TRADE_ORDER"), eq(USER), any(), eq(AT));
        assertThatThrownBy(() -> service.recordView(null, ViewTarget.USER, USER))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
