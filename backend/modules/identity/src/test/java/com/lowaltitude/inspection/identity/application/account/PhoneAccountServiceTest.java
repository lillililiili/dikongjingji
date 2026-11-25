package com.lowaltitude.inspection.identity.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.UserRow;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 验证码登录与注册、换号：注销或停用的账号登不进来；换号要凭据，新号码被占用时拒绝，输错新号码的码时凭据不作废。 */
class PhoneAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SESSION = UUID.fromString("51111111-1111-1111-1111-111111111111");

    private final AccountMapper mapper = mock(AccountMapper.class);
    private final SmsCodeService codes = mock(SmsCodeService.class);
    private final LoginSessionService sessions = mock(LoginSessionService.class);
    private final PhoneAccountService service = new PhoneAccountService(mapper, codes, sessions,
            Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

    private static UserRow user(String status, String mobile) {
        return new UserRow(USER, status, "王女士", mobile, null, UUID.randomUUID(), "PHONE");
    }

    @Test
    void anExistingActiveAccountLogsInAfterTheCodeIsChecked() {
        when(mapper.findUserByMobile("13900001111")).thenReturn(user("ACTIVE", "13900001111"));

        PhoneAccountService.PhoneLogin login = service.login("13900001111", "123456");

        assertThat(login.userId()).isEqualTo(USER);
        assertThat(login.registered()).isFalse();
        InOrder order = inOrder(codes, mapper);
        order.verify(codes).verify("13900001111", "LOGIN", "123456", null);
        order.verify(mapper).findUserByMobile("13900001111");
    }

    @Test
    void aClosedOrDisabledAccountCannotLogIn() {
        when(mapper.findUserByMobile("13900001111")).thenReturn(user("DISABLED", "13900001111"));

        assertThatThrownBy(() -> service.login("13900001111", "123456"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.UNAUTHORIZED);
        verify(mapper, never()).insertPhoneUser(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aWrongCodeNeverReachesTheAccount() {
        doThrow(new AccountException(Error.SMS_CODE_INVALID)).when(codes).verify("13900001111", "LOGIN", "000000", null);

        assertThatThrownBy(() -> service.login("13900001111", "000000"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.SMS_CODE_INVALID);
        verify(mapper, never()).findUserByMobile(anyString());
    }

    @Test
    void anUnknownNumberRegistersAPersonalCustomer() {
        UUID organization = UUID.randomUUID();
        when(mapper.insertPhoneUser(startsWith("p-"), eq("用户1111"), eq("13900001111"), startsWith("PERSONAL-"),
                eq("个人客户 139****1111"), any())).thenReturn(new AccountMapper.PersonalRegistration(USER, organization));

        PhoneAccountService.PhoneLogin login = service.login("13900001111", "123456");

        assertThat(login.registered()).isTrue();
        verify(mapper).insertOrganizationMember(eq(organization), eq(USER), any());
        verify(mapper).grantCustomerRole(USER);
    }

    @Test
    void registrationRaceReturnsTheWinningAccountWithoutCreatingAnotherOrganization() {
        when(mapper.findUserByMobile("13900001111")).thenReturn(null, user("ACTIVE", "13900001111"));

        assertThat(service.login("13900001111", "123456")).isEqualTo(new PhoneAccountService.PhoneLogin(USER, false));
        verify(mapper, never()).insertOrganizationMember(any(), any(), any());
        verify(mapper, never()).grantCustomerRole(any());
    }

    @Test
    void changingAnExistingNumberNeedsTheTicket() {
        when(mapper.lockUser(USER)).thenReturn(user("ACTIVE", "13900001111"));
        doThrow(new AccountException(Error.PHONE_TICKET_INVALID)).when(codes)
                .checkChangeTicket(USER, "13900001111", null);

        assertThatThrownBy(() -> service.changePhone(USER, SESSION, "13900002222", "123456", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.PHONE_TICKET_INVALID);
        verify(mapper, never()).updateMobile(any(), anyString(), any());
    }

    @Test
    void aNumberOfAnotherAccountIsRefused() {
        when(mapper.lockUser(USER)).thenReturn(user("ACTIVE", null));
        when(mapper.findUserByMobile("13900002222")).thenReturn(
                new UserRow(UUID.randomUUID(), "ACTIVE", "别人", "13900002222", null, null, "PHONE"));

        assertThatThrownBy(() -> service.changePhone(USER, SESSION, "13900002222", "123456", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.PHONE_TAKEN);
        verify(codes, never()).verify(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aWrongNewCodeKeepsTheTicketForAnotherTry() {
        UUID ticket = UUID.randomUUID();
        when(mapper.lockUser(USER)).thenReturn(user("ACTIVE", "13900001111"));
        when(codes.checkChangeTicket(USER, "13900001111", "t".repeat(40))).thenReturn(ticket);
        doThrow(new AccountException(Error.SMS_CODE_INVALID)).when(codes)
                .verify("13900002222", "BIND_NEW", "000000", USER);

        assertThatThrownBy(() -> service.changePhone(USER, SESSION, "13900002222", "000000", "t".repeat(40)))
                .isInstanceOf(AccountException.class);
        verify(codes, never()).consume(ticket);
    }

    @Test
    void aChangedNumberSignsOutTheOtherDevices() {
        UUID ticket = UUID.randomUUID();
        when(mapper.lockUser(USER)).thenReturn(user("ACTIVE", "13900001111"));
        when(codes.checkChangeTicket(USER, "13900001111", "t".repeat(40))).thenReturn(ticket);
        when(sessions.revokeAllExcept(USER, SESSION, "PHONE_CHANGED")).thenReturn(2);

        AccountViews.PhoneBindingView view = service.changePhone(USER, SESSION, "13900002222", "123456",
                "t".repeat(40));

        assertThat(view.maskedPhone()).isEqualTo("139****2222");
        assertThat(view.signedOutSessions()).isEqualTo(2);
        verify(mapper).updateMobile(eq(USER), eq("13900002222"), any());
        verify(codes).consume(ticket);
    }

    @Test
    void firstBindingNeedsNoTicketAndSignsNobodyOut() {
        when(mapper.lockUser(USER)).thenReturn(user("ACTIVE", null));

        AccountViews.PhoneBindingView view = service.changePhone(USER, SESSION, "13900002222", "123456", null);

        assertThat(view.signedOutSessions()).isZero();
        verify(sessions, never()).revokeAllExcept(any(), any(), anyString());
    }

    @Test
    void codesForTheCurrentNumberNeedANumber() {
        when(mapper.findUser(USER)).thenReturn(user("ACTIVE", null));

        assertThatThrownBy(() -> service.sendAccountCode(USER, "VERIFY_CURRENT", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.PHONE_REQUIRED);
        assertThatThrownBy(() -> service.sendAccountCode(USER, "LOGIN", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.INVALID);
    }
}
