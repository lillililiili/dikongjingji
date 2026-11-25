package com.lowaltitude.inspection.bootstrap.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.identity.application.account.AccountException;
import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.application.account.AccountViews.SmsCodeSentView;
import com.lowaltitude.inspection.identity.application.account.LoginSessionService;
import com.lowaltitude.inspection.identity.application.account.PhoneNumbers;
import com.lowaltitude.inspection.identity.application.account.SignedInAccount;
import com.lowaltitude.inspection.identity.application.account.SmsCodeService;
import com.lowaltitude.inspection.identity.domain.LoginDevice;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PersonalPasswordMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PersonalPasswordMapper.AttemptWindow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PersonalPasswordMapper.Credential;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PersonalPasswordService {
    private static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*[0-9])[^\\p{Z}\\s\\p{Cc}]{8,64}$");
    private final PersonalPasswordMapper passwords;
    private final AccountMapper accounts;
    private final SmsCodeService sms;
    private final LoginSessionService sessions;
    private final PasswordEncoder encoder;
    private final AuthenticationService auth;
    private final Clock clock;
    private final String dummyHash;

    public PersonalPasswordService(PersonalPasswordMapper passwords, AccountMapper accounts, SmsCodeService sms,
                                   LoginSessionService sessions,
                                   PasswordEncoder encoder, AuthenticationService auth, Clock identityClock) {
        this.passwords = passwords;
        this.accounts = accounts;
        this.sms = sms;
        this.sessions = sessions;
        this.encoder = encoder;
        this.auth = auth;
        this.clock = identityClock;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public record PasswordState(boolean eligible, boolean hasPassword, boolean canSetWithoutCode) {}

    @Transactional
    public PasswordState state(SignedInAccount account) {
        Credential user = passwords.lockById(account.subjectId());
        if (user == null || !user.eligible()) return new PasswordState(false, false, false);
        boolean fresh = sessionProof(account);
        return new PasswordState(true, user.hasPassword(), !user.hasPassword() && fresh);
    }

    @Transactional
    public SmsCodeSentView sendSetupCode(SignedInAccount account) {
        Credential user = requireUnset(account);
        sessionProof(account);
        sms.send(user.mobile(), SmsCodeService.PASSWORD_SETUP, user.id());
        if (passwords.bindSetupCode(user.id(), user.mobile(), account.sessionId()) != 1) {
            throw new IllegalStateException("setup code was not bound to its session");
        }
        return sent(user.mobile());
    }

    @Transactional(noRollbackFor = AccountException.class)
    public void setup(SignedInAccount account, String password, String code) {
        requirePassword(password);
        Credential user = requireUnset(account);
        boolean fresh = sessionProof(account);
        if (code != null && !code.isEmpty()) {
            if (!passwords.hasSessionCode(user.id(), user.mobile(), account.sessionId(), now())) {
                throw new AccountException(Error.SMS_CODE_INVALID);
            }
            sms.verify(user.mobile(), SmsCodeService.PASSWORD_SETUP, code, user.id());
        } else if (!fresh) {
            throw new AccountException(Error.PASSWORD_CODE_REQUIRED);
        }
        OffsetDateTime at = now();
        if (passwords.setFirstPassword(user.id(), encoder.encode(password), at) != 1) {
            throw new AccountException(Error.PASSWORD_ALREADY_SET);
        }
        accounts.clearPasswordSetupProofs(user.id());
        accounts.retireSmsCodes(user.mobile(), SmsCodeService.PASSWORD_SETUP, at);
        audit(user.id(), "ACCOUNT_PASSWORD_SET", at);
    }

    @Transactional(noRollbackFor = AccountException.class)
    public AuthenticationService.LoginToken login(String phone, String password, LoginDevice device) {
        PhoneNumbers.require(phone);
        checkPasswordInput(password);
        Credential user = passwords.lockByPhone(phone);
        verifyPassword(phone, user, password, Error.PASSWORD_CREDENTIALS_INVALID);
        // Keep the account row locked through token issuance, including its persisted session and refresh token.
        return auth.issueForVerifiedAccount(user.id(), device, "PASSWORD")
                .orElseThrow(() -> new AccountException(Error.PASSWORD_CREDENTIALS_INVALID));
    }

    @Transactional(noRollbackFor = AccountException.class)
    public void change(SignedInAccount account, String currentPassword, String newPassword) {
        requirePassword(newPassword);
        checkPasswordInput(currentPassword);
        Credential user = requireEligible(account);
        if (!user.hasPassword()) throw new AccountException(Error.PASSWORD_NOT_SET);
        sessionProof(account);
        verifyPassword(user.mobile(), user, currentPassword, Error.PASSWORD_CURRENT_INVALID);
        if (currentPassword.equals(newPassword)) throw new AccountException(Error.PASSWORD_UNCHANGED);
        replacePassword(user, newPassword, "ACCOUNT_PASSWORD_CHANGE");
    }

    @Transactional
    public SmsCodeSentView sendResetCode(String phone) {
        PhoneNumbers.require(phone);
        Credential user = passwords.lockByPhone(phone);
        // All numbers receive the same message and throttling. Only an eligible account gets a bound code.
        UUID recipient = user != null && user.hasPassword() ? user.id() : null;
        sms.send(phone, SmsCodeService.PASSWORD_RESET, recipient);
        return sent(phone);
    }

    @Transactional(noRollbackFor = AccountException.class)
    public void reset(String phone, String code, String newPassword) {
        requirePassword(newPassword);
        PhoneNumbers.require(phone);
        Credential user = passwords.lockByPhone(phone);
        boolean recoverable = user != null && user.hasPassword();
        sms.verify(phone, SmsCodeService.PASSWORD_RESET, code, recoverable ? user.id() : null);
        if (!recoverable) throw new AccountException(Error.SMS_CODE_INVALID);
        if (encoder.matches(newPassword, user.passwordHash())) throw new AccountException(Error.PASSWORD_UNCHANGED);
        replacePassword(user, newPassword, "ACCOUNT_PASSWORD_RESET");
    }

    private void replacePassword(Credential user, String password, String action) {
        OffsetDateTime at = now();
        passwords.replacePassword(user.id(), encoder.encode(password), at);
        sessions.revokeAllExcept(user.id(), null, "PASSWORD_CHANGED");
        accounts.clearPasswordSetupProofs(user.id());
        accounts.retireSmsCodes(user.mobile(), SmsCodeService.PASSWORD_RESET, at);
        accounts.retireSmsCodes(user.mobile(), SmsCodeService.LOGIN, at);
        passwords.saveAttempts(user.mobile(), 0, at, null);
        audit(user.id(), action, at);
    }

    private void verifyPassword(String phone, Credential user, String password, Error failure) {
        OffsetDateTime at = now();
        passwords.ensureAttempts(phone, at);
        AttemptWindow attempts = passwords.lockAttempts(phone);
        if (attempts.lockedUntil() != null && attempts.lockedUntil().isAfter(at)) {
            throw new AccountException(Error.PASSWORD_TRY_LATER);
        }
        boolean usable = user != null && user.hasPassword();
        boolean matches = encoder.matches(password, usable ? user.passwordHash() : dummyHash);
        if (!usable || !matches) {
            boolean continuing = attempts.lockedUntil() == null && attempts.windowStartedAt().plusHours(1).isAfter(at);
            int count = continuing ? attempts.failedCount() + 1 : 1;
            passwords.saveAttempts(phone, count, continuing ? attempts.windowStartedAt() : at,
                    count >= 10 ? at.plusMinutes(30) : null);
            throw new AccountException(failure);
        }
        passwords.saveAttempts(phone, 0, at, null);
    }

    private Credential requireUnset(SignedInAccount account) {
        Credential user = requireEligible(account);
        if (user.hasPassword()) throw new AccountException(Error.PASSWORD_ALREADY_SET);
        return user;
    }

    private Credential requireEligible(SignedInAccount account) {
        Credential user = passwords.lockById(account.subjectId());
        if (user == null || !user.eligible()) throw new AccountException(Error.PASSWORD_NOT_ELIGIBLE);
        return user;
    }

    private boolean sessionProof(SignedInAccount account) {
        Boolean fresh = passwords.lockSessionProof(account.subjectId(), account.sessionId(), now());
        if (fresh == null) throw new AccountException(Error.UNAUTHORIZED);
        return fresh;
    }

    private static void checkPasswordInput(String password) {
        if (password == null || password.isEmpty() || password.length() > 128
                || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new AccountException(Error.INVALID);
    }

    static void requirePassword(String password) {
        if (password == null || password.length() > 128 || !PASSWORD.matcher(password).matches()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new AccountException(Error.PASSWORD_INVALID);
    }

    private void audit(UUID userId, String action, OffsetDateTime at) {
        accounts.insertAudit(userId, action, "USER", userId, null, UUID.randomUUID().toString(), at);
    }

    private static SmsCodeSentView sent(String phone) {
        return new SmsCodeSentView(PhoneNumbers.mask(phone), (int) SmsCodeService.RESEND_INTERVAL.toSeconds(),
                (int) SmsCodeService.CODE_TTL.toSeconds());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
