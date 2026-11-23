package com.lowaltitude.inspection.identity.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.domain.LoginDevice;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.SessionRow;
import org.junit.jupiter.api.Test;

/** 登录设备：同一台设备再登录替换上一次的会话；本机在最前；本机不在列表里退；别人的会话当作不存在。 */
class LoginSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final OffsetDateTime AT = NOW.atOffset(ZoneOffset.UTC);
    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AccountMapper mapper = mock(AccountMapper.class);
    private final com.lowaltitude.inspection.identity.domain.RefreshTokenPort refreshTokens =
            mock(com.lowaltitude.inspection.identity.domain.RefreshTokenPort.class);
    private final LoginSessionService service = new LoginSessionService(mapper,
            codes -> Map.of("370500", "东营市"), refreshTokens, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theSameDeviceReplacesItsOldSessionAndBadValuesAreDropped() {
        UUID previous = UUID.randomUUID();
        when(mapper.findDeviceSessionIds(ME, "device-key-0001")).thenReturn(List.of(previous));
        service.open(ME, new LoginDevice("device-key-0001", "  iPhone\u0007 15 ", "IOS", "370500"), "SMS_CODE");
        verify(mapper).replaceDeviceSessions(ME, "device-key-0001", AT);
        verify(mapper).insertSession(ME, "device-key-0001", "iPhone 15", "IOS", "370500", "SMS_CODE", AT);
        verify(refreshTokens).revokeFamily(previous, com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason.LOGOUT,
                NOW);

        service.open(ME, new LoginDevice("bad key!", "", "WINDOWS", "dongying"), "PASSWORD");
        verify(mapper).insertSession(ME, null, "未知设备", "OTHER", null, "PASSWORD", AT);
    }

    @Test
    void thisDeviceComesFirstWithTheCityName() {
        UUID current = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(mapper.findActiveSessions(ME, AT.minusDays(30), 20)).thenReturn(List.of(
                new SessionRow(other, "Android 手机", "ANDROID", "370500", AT, AT),
                new SessionRow(current, "iPhone 15", "IOS", null, AT.minusDays(1), AT.minusDays(1))));

        AccountViews.SessionListView view = service.list(ME, current);

        assertThat(view.items()).extracting(AccountViews.SessionView::sessionId).containsExactly(current, other);
        assertThat(view.items().get(1).cityName()).isEqualTo("东营市");
    }

    @Test
    void thisDeviceAndOtherPeoplesSessionsCannotBeRevokedHere() {
        UUID current = UUID.randomUUID();
        assertThatThrownBy(() -> service.revoke(ME, current, current))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.SESSION_IS_CURRENT);
        assertThatThrownBy(() -> service.revoke(ME, current, UUID.randomUUID()))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);
        verify(mapper, never()).revokeSession(any(), any(), anyString(), any());
    }

    // The session is the refresh-token family of its login: ending it ends the family too.
    @Test
    void signingADeviceOutWithdrawsItsRefreshTokens() {
        UUID current = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(mapper.findSession(other, ME)).thenReturn(new SessionRow(other, "iPad", "IOS", null, AT, AT));
        when(mapper.revokeSession(other, ME, "REMOTE", AT)).thenReturn(1);

        service.revoke(ME, current, other);

        verify(refreshTokens).revokeFamily(other, com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason.LOGOUT,
                NOW);
    }

    @Test
    void aPhoneChangeOrClosureEndsEveryOtherLogin() {
        UUID keep = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(mapper.findActiveSessionIds(ME, keep)).thenReturn(List.of(a, b));
        when(mapper.revokeOtherSessions(ME, keep, "PHONE_CHANGED", AT)).thenReturn(2);

        assertThat(service.revokeAllExcept(ME, keep, "PHONE_CHANGED")).isEqualTo(2);
        verify(refreshTokens).revokeFamily(a, com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason.LOGOUT, NOW);
        verify(refreshTokens).revokeFamily(b, com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason.LOGOUT, NOW);

        when(mapper.findActiveSessionIds(ME, null)).thenReturn(List.of(a));
        service.revokeAllExcept(ME, null, "CLOSED");
        verify(refreshTokens).revokeFamily(a,
                com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason.ACCOUNT_CHANGED, NOW);
    }

    @Test
    void renewalsAskWhetherTheDeviceIsStillSignedIn() {
        UUID session = UUID.randomUUID();
        // No row: a login from before devices were recorded.
        when(mapper.findSessionRevoked(session, ME)).thenReturn(null);
        assertThat(service.status(session, ME)).isEqualTo(com.lowaltitude.inspection.identity.domain.LoginSessionStatus.UNKNOWN);
        when(mapper.findSessionRevoked(session, ME)).thenReturn(false);
        assertThat(service.status(session, ME)).isEqualTo(com.lowaltitude.inspection.identity.domain.LoginSessionStatus.ACTIVE);
        when(mapper.findSessionRevoked(session, ME)).thenReturn(true);
        assertThat(service.status(session, ME)).isEqualTo(com.lowaltitude.inspection.identity.domain.LoginSessionStatus.ENDED);
    }

    @Test
    void sessionsAreCheckedAgainstTheirOwner() {
        UUID session = UUID.randomUUID();
        assertThat(service.isActive(session, ME)).isFalse();
        assertThat(service.isActive(null, ME)).isFalse();
        when(mapper.isSessionActive(session, ME)).thenReturn(true);
        assertThat(service.isActive(session, ME)).isTrue();
    }
}
