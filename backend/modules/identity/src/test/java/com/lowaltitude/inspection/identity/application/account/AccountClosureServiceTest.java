package com.lowaltitude.inspection.identity.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.ClosureRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.UserRow;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 注销：条件不满足不能申请；15 天冷静期可撤回；到期退出企业、别的模块去标识、会话全撤、账号匿名化且不能再登录。 */
class AccountClosureServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final OffsetDateTime AT = NOW.atOffset(ZoneOffset.UTC);
    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AccountMapper mapper = mock(AccountMapper.class);
    private final EnterpriseService enterprises = mock(EnterpriseService.class);
    private final LoginSessionService sessions = mock(LoginSessionService.class);
    private final List<UUID> erased = new ArrayList<>();
    private String openOrders;

    private AccountClosureService service() {
        AccountPorts.ClosureCondition orders = new AccountPorts.ClosureCondition() {
            public String code() { return "NO_OPEN_ORDERS"; }
            public String title() { return "没有进行中的订单"; }
            public String unmetDetail(UUID accountId) { return openOrders; }
        };
        return new AccountClosureService(mapper, List.of(orders), List.of(erased::add), enterprises, sessions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unmetConditionsBlockTheRequest() {
        when(mapper.lockUser(ME)).thenReturn(new UserRow(ME, "ACTIVE", "王女士", null, null, null, "PHONE"));
        openOrders = "2 笔订单未完成";
        when(enterprises.soleAdminOf(ME)).thenReturn(List.of("东营港务物流"));

        AccountViews.ClosureView view = service().read(ME);
        assertThat(view.status()).isEqualTo("NONE");
        assertThat(view.conditions()).extracting(AccountViews.ClosureConditionView::detail)
                .containsExactly("2 笔订单未完成", "仍是 东营港务物流 的唯一管理员");
        assertThatThrownBy(() -> service().request(ME))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.CLOSURE_CONDITIONS_UNMET);
        verify(mapper, never()).insertClosure(any(), any(), any());
    }

    @Test
    void aRequestStartsFifteenDaysOfCoolingOff() {
        when(mapper.lockUser(ME)).thenReturn(new UserRow(ME, "ACTIVE", "王女士", null, null, null, "PHONE"));

        service().request(ME);

        verify(mapper).insertClosure(ME, AT, AT.plusDays(15));
    }

    @Test
    void onlyOnePendingRequestAndWithdrawNeedsOne() {
        when(mapper.lockUser(ME)).thenReturn(new UserRow(ME, "ACTIVE", "王女士", null, null, null, "PHONE"));
        when(mapper.findPendingClosure(ME)).thenReturn(new ClosureRow(UUID.randomUUID(), ME, AT, AT.plusDays(15)));
        assertThatThrownBy(() -> service().request(ME))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.CLOSURE_ALREADY_PENDING);
        assertThat(service().isClosing(ME)).isTrue();

        when(mapper.findPendingClosure(ME)).thenReturn(null);
        assertThatThrownBy(() -> service().withdraw(ME))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.CLOSURE_NOT_PENDING);
    }

    @Test
    void completionAnonymizesAfterEveryModuleHasForgottenThePerson() {
        UUID closure = UUID.randomUUID();
        when(mapper.lockPendingClosure(closure)).thenReturn(new ClosureRow(closure, ME, AT.minusDays(15), AT.minusSeconds(1)));

        assertThat(service().complete(closure)).isTrue();

        InOrder order = inOrder(enterprises, sessions, mapper);
        order.verify(enterprises).leaveAllForClosure(ME);
        order.verify(sessions).revokeAllExcept(ME, null, "CLOSED");
        order.verify(mapper).anonymizeUser(eq(ME), eq("closed-" + ME.toString().replace("-", "")), eq(AT));
        order.verify(mapper).completeClosure(closure, AT);
        assertThat(erased).containsExactly(ME);
    }

    @Test
    void notYetDueOrAlreadyHandledDoesNothing() {
        UUID closure = UUID.randomUUID();
        when(mapper.lockPendingClosure(closure)).thenReturn(new ClosureRow(closure, ME, AT, AT.plusDays(1)));
        assertThat(service().complete(closure)).isFalse();
        when(mapper.lockPendingClosure(closure)).thenReturn(null);
        assertThat(service().complete(closure)).isFalse();
        verify(mapper, never()).anonymizeUser(any(), anyString(), any());
        assertThat(erased).isEmpty();
    }
}
