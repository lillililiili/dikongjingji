package com.lowaltitude.inspection.identity.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.PhoneGuardRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.SmsCodeRow;
import org.junit.jupiter.api.Test;

/** 验证码：频率限制、错码作废、号码锁定（防爆破）、只认发给本人的码、短信服务不可用时不假装发出。 */
class SmsCodeServiceTest {

    private static final String PHONE = "13900001111";
    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final OffsetDateTime AT = NOW.atOffset(ZoneOffset.UTC);
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AccountMapper mapper = mock(AccountMapper.class);
    private final List<String> sent = new ArrayList<>();
    private final SmsCodeService service = new SmsCodeService(mapper, (phone, content) -> sent.add(content),
            Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

    @Test
    void sendsASixDigitCodeAndStoresOnlyItsHash() {
        service.send(PHONE, SmsCodeService.LOGIN, null);

        assertThat(sent).hasSize(1);
        String code = sent.get(0).replaceAll("^验证码 (\\d{6})，.*$", "$1");
        assertThat(code).matches("\\d{6}");
        verify(mapper).retireSmsCodes(PHONE, SmsCodeService.LOGIN, AT);
        verify(mapper).insertSmsCode(PHONE, SmsCodeService.LOGIN, null, SmsCodeService.hash(PHONE, "LOGIN", code), AT,
                AT.plusMinutes(5));
        assertThat(SmsCodeService.hash(PHONE, "LOGIN", code)).doesNotContain(code).hasSize(64);
    }

    @Test
    void refusesASecondCodeWithinSixtySeconds() {
        when(mapper.findLastSentAt(PHONE, "LOGIN")).thenReturn(AT.minusSeconds(59));

        assertThatThrownBy(() -> service.send(PHONE, "LOGIN", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.SMS_TOO_FREQUENT);
        assertThat(sent).isEmpty();
    }

    @Test
    void refusesTheEleventhCodeOfTheDay() {
        when(mapper.countSentSince(eq(PHONE), any())).thenReturn(10);

        assertThatThrownBy(() -> service.send(PHONE, "LOGIN", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.SMS_TOO_FREQUENT);
    }

    @Test
    void aLockedNumberGetsNoCode() {
        when(mapper.findPhoneLockedUntil(PHONE)).thenReturn(AT.plusMinutes(10));

        assertThatThrownBy(() -> service.send(PHONE, "LOGIN", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.PHONE_LOCKED);
        assertThat(sent).isEmpty();
    }

    @Test
    void anUnconfiguredSmsProviderIsReportedNotFaked() {
        SmsCodeService unavailable = new SmsCodeService(mapper, (phone, content) -> {
            throw new AccountPorts.SmsUnavailableException("none");
        }, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

        assertThatThrownBy(() -> unavailable.send(PHONE, "LOGIN", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.SMS_UNAVAILABLE);
    }

    @Test
    void aWrongCodeCountsAgainstTheCodeAndTheNumber() {
        when(mapper.lockPhoneGuard(PHONE)).thenReturn(new PhoneGuardRow(3, AT.minusMinutes(10), null));
        UUID codeId = UUID.randomUUID();
        when(mapper.lockUsableSmsCode(PHONE, "LOGIN", AT, 5)).thenReturn(
                new SmsCodeRow(codeId, null, SmsCodeService.hash(PHONE, "LOGIN", "123456"), 0, AT.plusMinutes(4)));

        assertThatThrownBy(() -> service.verify(PHONE, "LOGIN", "654321", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.SMS_CODE_INVALID);
        verify(mapper).markSmsCodeFailed(codeId);
        verify(mapper).updatePhoneGuard(PHONE, 4, AT.minusMinutes(10), null, AT);
        verify(mapper, never()).consumeSmsCode(any(), any());
    }

    @Test
    void theTenthFailureInAnHourLocksTheNumberForThirtyMinutes() {
        when(mapper.lockPhoneGuard(PHONE)).thenReturn(new PhoneGuardRow(9, AT.minusMinutes(30), null));

        assertThatThrownBy(() -> service.verify(PHONE, "LOGIN", "000000", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.SMS_CODE_INVALID);
        verify(mapper).updatePhoneGuard(PHONE, 0, AT, AT.plusMinutes(30), AT);
    }

    @Test
    void aLockedNumberRejectsEvenTheRightCode() {
        when(mapper.lockPhoneGuard(PHONE)).thenReturn(new PhoneGuardRow(0, AT, AT.plusMinutes(5)));

        assertThatThrownBy(() -> service.verify(PHONE, "LOGIN", "123456", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.PHONE_LOCKED);
        verify(mapper, never()).lockUsableSmsCode(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void failuresOutsideTheHourStartANewWindow() {
        when(mapper.lockPhoneGuard(PHONE)).thenReturn(new PhoneGuardRow(9, AT.minusMinutes(61), null));

        assertThatThrownBy(() -> service.verify(PHONE, "LOGIN", "000000", null)).isInstanceOf(AccountException.class);
        verify(mapper).updatePhoneGuard(PHONE, 1, AT, null, AT);
    }

    @Test
    void aCodeSentForOneAccountDoesNotWorkForAnother() {
        when(mapper.lockPhoneGuard(PHONE)).thenReturn(new PhoneGuardRow(0, AT, null));
        when(mapper.lockUsableSmsCode(PHONE, "EXPORT", AT, 5)).thenReturn(new SmsCodeRow(UUID.randomUUID(),
                UUID.randomUUID(), SmsCodeService.hash(PHONE, "EXPORT", "123456"), 0, AT.plusMinutes(4)));

        assertThatThrownBy(() -> service.verify(PHONE, "EXPORT", "123456", USER))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.SMS_CODE_INVALID);
    }

    @Test
    void theRightCodeIsUsedUpAndClearsTheFailures() {
        UUID codeId = UUID.randomUUID();
        when(mapper.lockPhoneGuard(PHONE)).thenReturn(new PhoneGuardRow(4, AT.minusMinutes(5), null));
        when(mapper.lockUsableSmsCode(PHONE, "LOGIN", AT, 5)).thenReturn(
                new SmsCodeRow(codeId, null, SmsCodeService.hash(PHONE, "LOGIN", "123456"), 2, AT.plusMinutes(4)));

        service.verify(PHONE, "LOGIN", "123456", null);

        verify(mapper).consumeSmsCode(codeId, AT);
        verify(mapper).updatePhoneGuard(PHONE, 0, AT, null, AT);
    }

    @Test
    void malformedCodesAreRejectedBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.verify(PHONE, "LOGIN", "12ab56", null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.INVALID);
        verify(mapper, never()).lockPhoneGuard(anyString());
    }

    @Test
    void aChangeTicketIsOnlyForItsAccount() {
        String ticket = service.issueChangeTicket(USER, PHONE);
        assertThat(ticket).hasSizeGreaterThanOrEqualTo(32);
        verify(mapper).insertSmsCode(eq(PHONE), eq("CHANGE_TICKET"), eq(USER), anyString(), eq(AT), eq(AT.plusMinutes(10)));

        when(mapper.lockUsableSmsCode(PHONE, "CHANGE_TICKET", AT, 5)).thenReturn(new SmsCodeRow(UUID.randomUUID(),
                USER, SmsCodeService.hash(PHONE, "CHANGE_TICKET", ticket), 0, AT.plusMinutes(9)));
        assertThat(service.checkChangeTicket(USER, PHONE, ticket)).isNotNull();
        assertThatThrownBy(() -> service.checkChangeTicket(UUID.randomUUID(), PHONE, ticket))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.PHONE_TICKET_INVALID);
        assertThatThrownBy(() -> service.checkChangeTicket(USER, PHONE, ticket + "x"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.PHONE_TICKET_INVALID);
        assertThatThrownBy(() -> service.checkChangeTicket(USER, PHONE, null))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.PHONE_TICKET_INVALID);
        verify(mapper, never()).retireSmsCodes(eq(PHONE), eq("LOGIN"), isNull());
    }
}
