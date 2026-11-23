package com.lowaltitude.inspection.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.spec.SecretKeySpec;

import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
import com.lowaltitude.inspection.identity.domain.IdentityAccountStatus;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRecord;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason;
import com.lowaltitude.inspection.identity.domain.RefreshTokenStatus;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 登录续期的规则：轮换、重放检测、过期、撤销、账号变化。 */
class AuthenticationServiceRefreshTest {

    private static final UUID SUBJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORGANIZATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final byte[] SECRET = Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final String passwordHash = passwordEncoder.encode("test-password");
    private final InMemoryRefreshTokenPort store = new InMemoryRefreshTokenPort();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-22T02:00:00Z"));
    private final AtomicInteger tokenVersion = new AtomicInteger(7);
    private IdentityAccountStatus accountStatus = IdentityAccountStatus.ACTIVE;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        IdentityAccountPort accounts = new IdentityAccountPort() {
            @Override
            public Optional<IdentityAccount> findByLoginName(String loginName) {
                return "test-user".equals(loginName) ? Optional.of(account()) : Optional.empty();
            }

            @Override
            public Optional<IdentityAccount> findBySubjectId(UUID subjectId) {
                return SUBJECT_ID.equals(subjectId) ? Optional.of(account()) : Optional.empty();
            }
        };
        service = new AuthenticationService(accounts, store, passwordEncoder,
                new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(new SecretKeySpec(SECRET, "HmacSHA256"))),
                clock);
    }

    // ---- S5-8 登录设备：会话 ID 就是刷新令牌 family ----

    /** 记录会话的账号状态：开会话返回 family 要用的 ID，记下退出、续期时的触碰。 */
    private static final class RecordingSessions implements com.lowaltitude.inspection.identity.domain.AccountStatePort {
        final java.util.Map<UUID, Boolean> sessions = new java.util.HashMap<>();
        final java.util.List<UUID> touched = new java.util.ArrayList<>();

        @Override
        public Optional<UUID> openSession(UUID subjectId, com.lowaltitude.inspection.identity.domain.LoginDevice device, String method) {
            UUID id = UUID.randomUUID();
            sessions.put(id, true);
            return Optional.of(id);
        }

        @Override
        public boolean isSessionActive(UUID sessionId, UUID subjectId) {
            return Boolean.TRUE.equals(sessions.get(sessionId));
        }

        @Override
        public void signOut(UUID sessionId, UUID subjectId) {
            sessions.computeIfPresent(sessionId, (id, active) -> false);
        }

        @Override
        public com.lowaltitude.inspection.identity.domain.LoginSessionStatus sessionStatus(UUID sessionId, UUID subjectId) {
            Boolean active = sessions.get(sessionId);
            return active == null ? com.lowaltitude.inspection.identity.domain.LoginSessionStatus.UNKNOWN
                    : active ? com.lowaltitude.inspection.identity.domain.LoginSessionStatus.ACTIVE
                    : com.lowaltitude.inspection.identity.domain.LoginSessionStatus.ENDED;
        }

        @Override
        public void touchSession(UUID sessionId, UUID subjectId) {
            touched.add(sessionId);
        }

        @Override
        public com.lowaltitude.inspection.identity.domain.CurrentIdentity currentIdentity(UUID subjectId) {
            return com.lowaltitude.inspection.identity.domain.CurrentIdentity.personal();
        }

        @Override
        public Optional<String> maskedPhone(UUID subjectId) {
            return Optional.empty();
        }
    }

    private AuthenticationService withSessions(RecordingSessions sessions) {
        IdentityAccountPort accounts = new IdentityAccountPort() {
            @Override
            public Optional<IdentityAccount> findByLoginName(String loginName) {
                return "test-user".equals(loginName) ? Optional.of(account()) : Optional.empty();
            }

            @Override
            public Optional<IdentityAccount> findBySubjectId(UUID subjectId) {
                return SUBJECT_ID.equals(subjectId) ? Optional.of(account()) : Optional.empty();
            }
        };
        return new AuthenticationService(accounts, store, passwordEncoder,
                new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(new SecretKeySpec(SECRET, "HmacSHA256"))),
                sessions, clock);
    }

    @Test
    void theLoginSessionIsTheRefreshFamilyAndRenewalsKeepAndTouchIt() throws Exception {
        RecordingSessions sessions = new RecordingSessions();
        AuthenticationService sessionService = withSessions(sessions);
        AuthenticationService.LoginToken login = sessionService.authenticate("test-user", "test-password").orElseThrow();
        UUID sid = UUID.fromString((String) SignedJWT.parse(login.accessToken()).getJWTClaimsSet().getClaim("sid"));

        assertThat(stored(login.refreshToken()).familyId()).isEqualTo(sid);
        AuthenticationService.LoginToken renewed = sessionService.refresh(login.refreshToken()).orElseThrow();
        assertThat(SignedJWT.parse(renewed.accessToken()).getJWTClaimsSet().getClaim("sid")).isEqualTo(sid.toString());
        assertThat(sessions.touched).containsExactly(sid);
    }

    @Test
    void aDeviceSignedOutCannotRenewAndSigningOutEndsTheSession() {
        RecordingSessions sessions = new RecordingSessions();
        AuthenticationService sessionService = withSessions(sessions);
        AuthenticationService.LoginToken kicked = sessionService.authenticate("test-user", "test-password").orElseThrow();
        UUID kickedFamily = stored(kicked.refreshToken()).familyId();
        sessions.signOut(kickedFamily, SUBJECT_ID);

        assertThat(sessionService.refresh(kicked.refreshToken())).isEmpty();
        assertThat(store.all()).filteredOn(token -> token.familyId().equals(kickedFamily))
                .allSatisfy(token -> assertThat(token.status()).isEqualTo(RefreshTokenStatus.REVOKED));

        AuthenticationService.LoginToken other = sessionService.authenticate("test-user", "test-password").orElseThrow();
        UUID otherFamily = stored(other.refreshToken()).familyId();
        sessionService.logout(other.refreshToken());
        assertThat(sessions.isSessionActive(otherFamily, SUBJECT_ID)).isFalse();
    }

    @Test
    void loginsFromBeforeDevicesWereRecordedRenewAsBefore() throws Exception {
        AuthenticationService.LoginToken legacy = login();
        AuthenticationService sessionService = withSessions(new RecordingSessions());

        AuthenticationService.LoginToken renewed = sessionService.refresh(legacy.refreshToken()).orElseThrow();
        assertThat(SignedJWT.parse(renewed.accessToken()).getJWTClaimsSet().getClaim("sid")).isNull();
    }

    @Test
    void loginHandsOutARefreshTokenThatIsStoredOnlyAsItsHash() {
        AuthenticationService.LoginToken login = login();

        assertThat(login.refreshToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(login.refreshExpiresInSeconds()).isEqualTo(Duration.ofDays(30).getSeconds());
        assertThat(store.all()).singleElement().satisfies(stored -> {
            assertThat(stored.tokenHash()).isEqualTo(AuthenticationService.hash(login.refreshToken()))
                    .doesNotContain(login.refreshToken());
            assertThat(stored.subjectId()).isEqualTo(SUBJECT_ID);
            assertThat(stored.tokenVersion()).isEqualTo(7);
            assertThat(stored.familyExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(90)));
        });
    }

    @Test
    void renewingRotatesTheTokenAndIssuesAFreshAccessToken() throws Exception {
        AuthenticationService.LoginToken login = login();
        clock.advance(Duration.ofMinutes(20));

        AuthenticationService.LoginToken renewed = service.refresh(login.refreshToken()).orElseThrow();

        assertThat(renewed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(renewed.expiresInSeconds()).isEqualTo(900);
        var claims = SignedJWT.parse(renewed.accessToken()).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(SUBJECT_ID.toString());
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(clock.instant());
        assertThat(((Number) claims.getClaim("tokenVersion")).intValue()).isEqualTo(7);
        // 同一次登录：新令牌和旧令牌同一个 family，旧的已换掉。
        RefreshTokenRecord old = stored(login.refreshToken());
        RefreshTokenRecord next = stored(renewed.refreshToken());
        assertThat(old.status()).isEqualTo(RefreshTokenStatus.ROTATED);
        assertThat(next.status()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(next.familyId()).isEqualTo(old.familyId());
        // 换来的令牌还能接着续。
        assertThat(service.refresh(renewed.refreshToken())).isPresent();
    }

    @Test
    void replayingAnExchangedTokenWithdrawsTheWholeLogin() {
        AuthenticationService.LoginToken login = login();
        AuthenticationService.LoginToken renewed = service.refresh(login.refreshToken()).orElseThrow();

        // 旧令牌再被用一次：当作被盗用，这次登录的所有令牌都撤销，连合法持有人手里的新令牌也不能用。
        assertThat(service.refresh(login.refreshToken())).isEmpty();
        assertThat(service.refresh(renewed.refreshToken())).isEmpty();
        assertThat(stored(renewed.refreshToken()).status()).isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(store.revokeReason(stored(renewed.refreshToken()).tokenId()))
                .contains(RefreshTokenRevokeReason.REUSE_DETECTED);
    }

    @Test
    void losingTheRaceForOneTokenCountsAsReuse() {
        AuthenticationService.LoginToken login = login();
        store.loseNextRotation = true;

        assertThat(service.refresh(login.refreshToken())).isEmpty();
        // 这次请求先存下的新令牌也随整个 family 撤销了，不会留下能用的令牌。
        assertThat(store.all()).noneMatch(token -> token.status() == RefreshTokenStatus.ACTIVE);
    }

    @Test
    void anExpiredRefreshTokenCannotBeExchanged() {
        AuthenticationService.LoginToken login = login();
        clock.advance(Duration.ofDays(30));

        assertThat(service.refresh(login.refreshToken())).isEmpty();
    }

    @Test
    void aLoginEndsNinetyDaysAfterSigningInHoweverOftenItIsRenewed() {
        Instant signedInAt = clock.instant();
        String token = login().refreshToken();
        for (int renewal = 0; renewal < 3; renewal++) {
            clock.advance(Duration.ofDays(29));
            token = service.refresh(token).orElseThrow().refreshToken();
        }
        // 第 87 天换来的令牌只到第 90 天，不是再给 30 天。
        assertThat(stored(token).expiresAt()).isEqualTo(signedInAt.plus(Duration.ofDays(90)));
        clock.advance(Duration.ofDays(4));

        assertThat(service.refresh(token)).isEmpty();
    }

    @Test
    void signingOutWithdrawsTheLoginAndIgnoresUnknownTokens() {
        AuthenticationService.LoginToken login = login();
        AuthenticationService.LoginToken other = login();

        service.logout(login.refreshToken());
        service.logout("not-a-token-we-issued");

        assertThat(service.refresh(login.refreshToken())).isEmpty();
        assertThat(store.revokeReason(stored(login.refreshToken()).tokenId())).contains(RefreshTokenRevokeReason.LOGOUT);
        // 另一台设备上的登录不受影响。
        assertThat(service.refresh(other.refreshToken())).isPresent();
    }

    @Test
    void aRaisedTokenVersionOrADisabledAccountEndsTheLogin() {
        AuthenticationService.LoginToken first = login();
        tokenVersion.set(8);
        assertThat(service.refresh(first.refreshToken())).isEmpty();
        assertThat(store.revokeReason(stored(first.refreshToken()).tokenId()))
                .contains(RefreshTokenRevokeReason.ACCOUNT_CHANGED);

        AuthenticationService.LoginToken second = login();
        accountStatus = IdentityAccountStatus.DISABLED;
        assertThat(service.refresh(second.refreshToken())).isEmpty();
    }

    @Test
    void unknownTokensAreRejected() {
        login();

        assertThat(service.refresh("A".repeat(43))).isEmpty();
    }

    private AuthenticationService.LoginToken login() {
        return service.authenticate("test-user", "test-password").orElseThrow();
    }

    private RefreshTokenRecord stored(String token) {
        return store.findByHash(AuthenticationService.hash(token)).orElseThrow();
    }

    private IdentityAccount account() {
        return new IdentityAccount(SUBJECT_ID, "test-user", passwordHash, accountStatus, Set.of("CUSTOMER"),
                ORGANIZATION_ID, Set.of("inspection.reservation.create"), tokenVersion.get());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
