package com.lowaltitude.inspection.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException;
import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.application.account.SignedInAccount;
import com.lowaltitude.inspection.identity.application.account.LoginSessionService;
import com.lowaltitude.inspection.identity.application.account.SmsCodeService;
import com.lowaltitude.inspection.identity.domain.LoginDevice;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PersonalPasswordMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PersonalPasswordMapper.AttemptWindow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PersonalPasswordMapper.Credential;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PersonalPasswordServiceTest {
    private static final String PHONE = "13900001111";
    private static final String PASSWORD = "PrivatePass123";
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final OffsetDateTime AT = Instant.parse("2026-11-25T04:00:00Z").atOffset(ZoneOffset.UTC);
    private static final SignedInAccount SIGNED_IN = new SignedInAccount(USER, SESSION, List.of("CUSTOMER"));
    private final PersonalPasswordMapper passwords = mock(PersonalPasswordMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final SmsCodeService sms = mock(SmsCodeService.class);
    private final LoginSessionService sessions = mock(LoginSessionService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthenticationService auth = mock(AuthenticationService.class);
    private final PersonalPasswordService service = new PersonalPasswordService(passwords, accounts, sms,
            sessions, encoder, auth, Clock.fixed(AT.toInstant(), ZoneOffset.UTC));

    @Test
    void acceptsTheBcryptByteBoundaryAndRejectsAnOverlongOrBlankPassword() {
        assertThatCode(() -> PersonalPasswordService.requirePassword("简".repeat(23) + "a1x")).doesNotThrowAnyException();
        for (String invalid : List.of("简".repeat(24) + "a1", "12345678", "abcdefghij", "a 12345678", "a\u00a012345678")) {
            assertThatThrownBy(() -> PersonalPasswordService.requirePassword(invalid))
                    .isInstanceOf(AccountException.class);
        }
    }

    @Test
    void freshSmsSessionCanSetTheFirstPasswordAndClearsAllOutstandingProofs() {
        when(passwords.lockById(USER)).thenReturn(new Credential(USER, PHONE, "!", true));
        when(passwords.lockSessionProof(USER, SESSION, AT)).thenReturn(true);
        when(encoder.encode(PASSWORD)).thenReturn("digest");
        when(passwords.setFirstPassword(USER, "digest", AT)).thenReturn(1);
        assertThat(service.state(SIGNED_IN)).isEqualTo(new PersonalPasswordService.PasswordState(true, false, true));
        service.setup(SIGNED_IN, PASSWORD, null);
        verify(accounts).clearPasswordSetupProofs(USER);
        verify(accounts).retireSmsCodes(PHONE, "PASSWORD_SETUP", AT);
    }

    @Test
    void expiredFreshnessRequiresAnSmsBoundToThisSessionAndPurpose() {
        when(passwords.lockById(USER)).thenReturn(new Credential(USER, PHONE, "!", true));
        when(passwords.lockSessionProof(USER, SESSION, AT)).thenReturn(false);
        assertThatThrownBy(() -> service.setup(SIGNED_IN, PASSWORD, null))
                .extracting(error -> ((AccountException) error).error()).isEqualTo(Error.PASSWORD_CODE_REQUIRED);
        assertThatThrownBy(() -> service.setup(SIGNED_IN, PASSWORD, "123456"))
                .extracting(error -> ((AccountException) error).error()).isEqualTo(Error.SMS_CODE_INVALID);
        verify(sms, never()).verify(anyString(), anyString(), anyString(), any());
    }

    @Test
    void anIneligibleAccountHasNoPasswordEntryOrSetupPermission() {
        when(passwords.lockById(USER)).thenReturn(new Credential(USER, PHONE, "old", false));
        assertThat(service.state(SIGNED_IN)).isEqualTo(new PersonalPasswordService.PasswordState(false, false, false));
        assertThatThrownBy(() -> service.setup(SIGNED_IN, PASSWORD, null))
                .extracting(error -> ((AccountException) error).error()).isEqualTo(Error.PASSWORD_NOT_ELIGIBLE);
        verify(passwords, never()).setFirstPassword(eq(USER), anyString(), any());
    }

    @Test
    void tenthPasswordErrorBlocksFurtherAttemptsEvenForAnUnknownPhone() {
        when(passwords.lockAttempts(PHONE)).thenReturn(new AttemptWindow(9, AT.minusMinutes(4), null));
        assertThatThrownBy(() -> service.login(PHONE, PASSWORD, LoginDevice.unknown()))
                .extracting(error -> ((AccountException) error).error()).isEqualTo(Error.PASSWORD_CREDENTIALS_INVALID);
        verify(passwords).saveAttempts(PHONE, 10, AT.minusMinutes(4), AT.plusMinutes(30));
        verify(auth, never()).issueForVerifiedAccount(any(), any(), any());
    }

    @Test
    void anUnregisteredPhoneCanRequestButCannotResetAnyAccount() {
        assertThat(service.sendResetCode(PHONE).maskedPhone()).isEqualTo("139****1111");
        verify(sms).send(PHONE, "PASSWORD_RESET", null);
        assertThatThrownBy(() -> service.reset(PHONE, "123456", PASSWORD))
                .extracting(error -> ((AccountException) error).error()).isEqualTo(Error.SMS_CODE_INVALID);
        verify(passwords, never()).replacePassword(any(), anyString(), any());
    }

    @Test
    void resetValidatesTheAccountBoundCodeAndRevokesEverySession() {
        when(passwords.lockByPhone(PHONE)).thenReturn(new Credential(USER, PHONE, "old", true));
        when(encoder.encode(PASSWORD)).thenReturn("new");
        service.reset(PHONE, "123456", PASSWORD);
        verify(sms).verify(PHONE, "PASSWORD_RESET", "123456", USER);
        verify(passwords).replacePassword(USER, "new", AT);
        verify(sessions).revokeAllExcept(USER, null, "PASSWORD_CHANGED");
        verify(passwords).saveAttempts(PHONE, 0, AT, null);
    }

    @Test
    void wrongCurrentPasswordReturnsABusinessErrorAndLeavesSessionsActive() {
        when(passwords.lockById(USER)).thenReturn(new Credential(USER, PHONE, "old", true));
        when(passwords.lockSessionProof(USER, SESSION, AT)).thenReturn(false);
        when(passwords.lockAttempts(PHONE)).thenReturn(new AttemptWindow(0, AT, null));
        assertThatThrownBy(() -> service.change(SIGNED_IN, "IncorrectPassword19", PASSWORD))
                .extracting(error -> ((AccountException) error).error()).isEqualTo(Error.PASSWORD_CURRENT_INVALID);
        verify(sessions, never()).revokeAllExcept(any(), any(), anyString());
    }
}
