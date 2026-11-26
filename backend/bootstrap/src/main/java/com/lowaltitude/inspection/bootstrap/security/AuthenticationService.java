package com.lowaltitude.inspection.bootstrap.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.AccountStatePort;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
import com.lowaltitude.inspection.identity.domain.IdentityAccountStatus;
import com.lowaltitude.inspection.identity.domain.LoginDevice;
import com.lowaltitude.inspection.identity.domain.LoginSessionStatus;
import com.lowaltitude.inspection.identity.domain.RefreshTokenPort;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRecord;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason;
import com.lowaltitude.inspection.identity.domain.RefreshTokenStatus;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedAccountStatePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

/**
 * Login, renewal and sign-out. The access token is a 900-second JWT. Next to it a login gets a
 * refresh token: 32 random bytes, stored only as their SHA-256, valid 30 days and exchanged for a new
 * one at every renewal (rotation). All refresh tokens of one login form a family that lives at most
 * 90 days. Presenting a token that was already exchanged means a copy is in someone else's hands:
 * the whole family is withdrawn and that login has to sign in again. Signing out withdraws the
 * family. A renewal re-reads the account, so a disabled account or a raised token version ends it.
 *
 * <p>S5-7, S5-8: every login (password or phone code) also records a login session for 登录设备; the
 * session id is the family id and access tokens carry it as {@code sid}. Signing a device out ends the
 * session and its family; a renewal of an ended session is refused, one of a live session touches it.
 * Access tokens also carry {@code identity}, the enterprise the account acts as (none for a person),
 * read again at every issue. Without a database no session is recorded and everything works as before.
 */
@Service
public class AuthenticationService {

    private static final long TOKEN_TTL_SECONDS = 900;
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);
    static final Duration LOGIN_MAX_AGE = Duration.ofDays(90);
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final IdentityAccountPort identityAccountPort;
    private final RefreshTokenPort refreshTokenPort;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AccountStatePort accountState;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public AuthenticationService(
            IdentityAccountPort identityAccountPort,
            RefreshTokenPort refreshTokenPort,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            AccountStatePort accountState
    ) {
        this(identityAccountPort, refreshTokenPort, passwordEncoder, jwtEncoder, accountState, Clock.systemUTC());
    }

    /** Without login sessions (tests of the refresh rules). */
    AuthenticationService(
            IdentityAccountPort identityAccountPort,
            RefreshTokenPort refreshTokenPort,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            Clock clock
    ) {
        this(identityAccountPort, refreshTokenPort, passwordEncoder, jwtEncoder, new FailClosedAccountStatePort(), clock);
    }

    AuthenticationService(
            IdentityAccountPort identityAccountPort,
            RefreshTokenPort refreshTokenPort,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            AccountStatePort accountState,
            Clock clock
    ) {
        this.identityAccountPort = identityAccountPort;
        this.refreshTokenPort = refreshTokenPort;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.accountState = accountState;
        this.clock = clock;
    }

    public Optional<LoginToken> authenticate(String loginName, String password) {
        return authenticate(loginName, password, LoginDevice.unknown());
    }

    public Optional<LoginToken> authenticate(String loginName, String password, LoginDevice device) {
        return identityAccountPort.findByLoginName(loginName)
                .filter(account -> account.status() == IdentityAccountStatus.ACTIVE)
                .filter(account -> passwordEncoder.matches(password, account.passwordHash()))
                .map(account -> startLogin(account, device, "PASSWORD"));
    }

    /** S5-8 code login: the identity module checked the code and named the account; it must be active. */
    public Optional<LoginToken> issueForVerifiedAccount(UUID subjectId, LoginDevice device) {
        return issueForVerifiedAccount(subjectId, device, "SMS_CODE");
    }

    Optional<LoginToken> issueForVerifiedAccount(UUID subjectId, LoginDevice device, String method) {
        return identityAccountPort.findBySubjectId(subjectId)
                .filter(account -> account.status() == IdentityAccountStatus.ACTIVE)
                .map(account -> startLogin(account, device, method));
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token. Empty when the token
     * is unknown, expired, withdrawn, already exchanged (then its whole login is withdrawn), when the
     * account can no longer sign in, or when its device was signed out.
     */
    public Optional<LoginToken> refresh(String refreshToken) {
        Instant now = clock.instant();
        Optional<RefreshTokenRecord> found = refreshTokenPort.findByHash(hash(refreshToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshTokenRecord current = found.get();
        if (current.status() == RefreshTokenStatus.ROTATED) {
            endLogin(current, RefreshTokenRevokeReason.REUSE_DETECTED, now);
            return Optional.empty();
        }
        if (!current.usableAt(now)) {
            return Optional.empty();
        }
        Optional<IdentityAccount> account = identityAccountPort.findBySubjectId(current.subjectId())
                .filter(candidate -> candidate.status() == IdentityAccountStatus.ACTIVE)
                .filter(candidate -> candidate.tokenVersion() == current.tokenVersion());
        if (account.isEmpty()) {
            endLogin(current, RefreshTokenRevokeReason.ACCOUNT_CHANGED, now);
            return Optional.empty();
        }
        LoginSessionStatus session = accountState.sessionStatus(current.familyId(), current.subjectId());
        if (session == LoginSessionStatus.ENDED) {
            refreshTokenPort.revokeFamily(current.familyId(), RefreshTokenRevokeReason.LOGOUT, now);
            return Optional.empty();
        }
        // The new token is stored first; only if this request is the one that retires the old token is
        // it handed out. A lost race means the same token was used twice: withdraw the whole login.
        IssuedRefreshToken next = issueRefreshToken(account.get(), current.familyId(), current.familyExpiresAt(), now);
        if (!refreshTokenPort.markRotated(current.tokenId(), next.record().tokenId(), now)) {
            endLogin(current, RefreshTokenRevokeReason.REUSE_DETECTED, now);
            return Optional.empty();
        }
        UUID sessionId = null;
        if (session == LoginSessionStatus.ACTIVE) {
            accountState.touchSession(current.familyId(), current.subjectId());
            sessionId = current.familyId();
        }
        return Optional.of(loginToken(account.get(), next, now, sessionId));
    }

    /** Withdraws the login the refresh token belongs to, and signs its device out. Unknown tokens are ignored. */
    public void logout(String refreshToken) {
        refreshTokenPort.findByHash(hash(refreshToken)).ifPresent(token ->
                endLogin(token, RefreshTokenRevokeReason.LOGOUT, clock.instant()));
    }

    /**
     * S5-7 switching identity: a new access token for the same device (session), with the identity
     * read again. The refresh token stays as it is; the next renewal reads the identity too. Empty
     * when the device was signed out or the account can no longer sign in.
     */
    public Optional<LoginToken> reissue(UUID subjectId, UUID sessionId) {
        if (sessionId != null && !accountState.isSessionActive(sessionId, subjectId)) {
            return Optional.empty();
        }
        return identityAccountPort.findBySubjectId(subjectId)
                .filter(account -> account.status() == IdentityAccountStatus.ACTIVE)
                .map(account -> new LoginToken(issueAccessToken(account, clock.instant(), sessionId),
                        TOKEN_TTL_SECONDS, null, 0));
    }

    private LoginToken startLogin(IdentityAccount account, LoginDevice device, String method) {
        Instant now = clock.instant();
        Optional<UUID> session = accountState.openSession(account.subjectId(), device, method);
        UUID familyId = session.orElseGet(UUID::randomUUID);
        IssuedRefreshToken refresh = issueRefreshToken(account, familyId, now.plus(LOGIN_MAX_AGE), now);
        return loginToken(account, refresh, now, session.orElse(null));
    }

    private void endLogin(RefreshTokenRecord token, RefreshTokenRevokeReason reason, Instant now) {
        refreshTokenPort.revokeFamily(token.familyId(), reason, now);
        accountState.signOut(token.familyId(), token.subjectId());
    }

    private IssuedRefreshToken issueRefreshToken(
            IdentityAccount account,
            UUID familyId,
            Instant familyExpiresAt,
            Instant now
    ) {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(REFRESH_TOKEN_TTL);
        if (expiresAt.isAfter(familyExpiresAt)) {
            expiresAt = familyExpiresAt;
        }
        RefreshTokenRecord record = new RefreshTokenRecord(
                UUID.randomUUID(),
                familyId,
                account.subjectId(),
                hash(token),
                account.tokenVersion(),
                RefreshTokenStatus.ACTIVE,
                now,
                expiresAt,
                familyExpiresAt);
        refreshTokenPort.save(record);
        return new IssuedRefreshToken(token, record);
    }

    private LoginToken loginToken(IdentityAccount account, IssuedRefreshToken refresh, Instant now, UUID sessionId) {
        long refreshSeconds = Math.max(1, Duration.between(now, refresh.record().expiresAt()).getSeconds());
        return new LoginToken(issueAccessToken(account, now, sessionId), TOKEN_TTL_SECONDS, refresh.token(),
                refreshSeconds);
    }

    private String issueAccessToken(IdentityAccount account, Instant issuedAt, UUID sessionId) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(account.subjectId().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(TOKEN_TTL_SECONDS))
                .claim("subjectId", account.subjectId().toString())
                .claim("roles", account.roles())
                .claim("organizationId", account.organizationId().toString())
                .claim("permissions", account.permissions())
                .claim("tokenVersion", account.tokenVersion());
        if (sessionId != null) {
            claims.claim("sid", sessionId.toString());
        }
        CurrentIdentity identity = accountState.currentIdentity(account.subjectId());
        if (identity.isEnterprise()) {
            claims.claim("identity", identity.enterpriseId().toString());
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()
        )).getTokenValue();
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }

    private record IssuedRefreshToken(String token, RefreshTokenRecord record) {
    }

    /** refreshToken is null (and refreshExpiresInSeconds 0) on a reissue after switching identity. */
    public record LoginToken(
            String accessToken,
            long expiresInSeconds,
            String refreshToken,
            long refreshExpiresInSeconds
    ) {
    }
}
