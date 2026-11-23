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

import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
import com.lowaltitude.inspection.identity.domain.IdentityAccountStatus;
import com.lowaltitude.inspection.identity.domain.RefreshTokenPort;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRecord;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason;
import com.lowaltitude.inspection.identity.domain.RefreshTokenStatus;
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
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public AuthenticationService(
            IdentityAccountPort identityAccountPort,
            RefreshTokenPort refreshTokenPort,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder
    ) {
        this(identityAccountPort, refreshTokenPort, passwordEncoder, jwtEncoder, Clock.systemUTC());
    }

    AuthenticationService(
            IdentityAccountPort identityAccountPort,
            RefreshTokenPort refreshTokenPort,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            Clock clock
    ) {
        this.identityAccountPort = identityAccountPort;
        this.refreshTokenPort = refreshTokenPort;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
    }

    public Optional<LoginToken> authenticate(String loginName, String password) {
        return identityAccountPort.findByLoginName(loginName)
                .filter(account -> account.status() == IdentityAccountStatus.ACTIVE)
                .filter(account -> passwordEncoder.matches(password, account.passwordHash()))
                .map(account -> {
                    Instant now = clock.instant();
                    IssuedRefreshToken refresh = issueRefreshToken(
                            account, UUID.randomUUID(), now.plus(LOGIN_MAX_AGE), now);
                    return loginToken(account, refresh, now);
                });
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token. Empty when the token
     * is unknown, expired, withdrawn, already exchanged (then its whole login is withdrawn) or when
     * the account can no longer sign in.
     */
    public Optional<LoginToken> refresh(String refreshToken) {
        Instant now = clock.instant();
        Optional<RefreshTokenRecord> found = refreshTokenPort.findByHash(hash(refreshToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshTokenRecord current = found.get();
        if (current.status() == RefreshTokenStatus.ROTATED) {
            refreshTokenPort.revokeFamily(current.familyId(), RefreshTokenRevokeReason.REUSE_DETECTED, now);
            return Optional.empty();
        }
        if (!current.usableAt(now)) {
            return Optional.empty();
        }
        Optional<IdentityAccount> account = identityAccountPort.findBySubjectId(current.subjectId())
                .filter(candidate -> candidate.status() == IdentityAccountStatus.ACTIVE)
                .filter(candidate -> candidate.tokenVersion() == current.tokenVersion());
        if (account.isEmpty()) {
            refreshTokenPort.revokeFamily(current.familyId(), RefreshTokenRevokeReason.ACCOUNT_CHANGED, now);
            return Optional.empty();
        }
        // The new token is stored first; only if this request is the one that retires the old token is
        // it handed out. A lost race means the same token was used twice: withdraw the whole login.
        IssuedRefreshToken next = issueRefreshToken(account.get(), current.familyId(), current.familyExpiresAt(), now);
        if (!refreshTokenPort.markRotated(current.tokenId(), next.record().tokenId(), now)) {
            refreshTokenPort.revokeFamily(current.familyId(), RefreshTokenRevokeReason.REUSE_DETECTED, now);
            return Optional.empty();
        }
        return Optional.of(loginToken(account.get(), next, now));
    }

    /** Withdraws the login the refresh token belongs to. Unknown tokens are ignored. */
    public void logout(String refreshToken) {
        refreshTokenPort.findByHash(hash(refreshToken)).ifPresent(token ->
                refreshTokenPort.revokeFamily(token.familyId(), RefreshTokenRevokeReason.LOGOUT, clock.instant()));
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

    private LoginToken loginToken(IdentityAccount account, IssuedRefreshToken refresh, Instant now) {
        long refreshSeconds = Math.max(1, Duration.between(now, refresh.record().expiresAt()).getSeconds());
        return new LoginToken(issueAccessToken(account, now), TOKEN_TTL_SECONDS, refresh.token(), refreshSeconds);
    }

    private String issueAccessToken(IdentityAccount account, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(account.subjectId().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(TOKEN_TTL_SECONDS))
                .claim("subjectId", account.subjectId().toString())
                .claim("roles", account.roles())
                .claim("organizationId", account.organizationId().toString())
                .claim("permissions", account.permissions())
                .claim("tokenVersion", account.tokenVersion())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims
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

    public record LoginToken(
            String accessToken,
            long expiresInSeconds,
            String refreshToken,
            long refreshExpiresInSeconds
    ) {
    }
}
