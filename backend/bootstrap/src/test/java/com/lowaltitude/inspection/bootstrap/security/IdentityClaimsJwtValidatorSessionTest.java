package com.lowaltitude.inspection.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.AccountStatePort;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedAccountStatePort;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** S5-8 登录设备：带 sid 的令牌只在会话还在时有效；没有 sid 的旧令牌照旧只看令牌版本。 */
class IdentityClaimsJwtValidatorSessionTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SESSION = UUID.fromString("51111111-1111-1111-1111-111111111111");

    private static Jwt token(Object sid) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "HS256")
                .subject(SUBJECT.toString())
                .claim("subjectId", SUBJECT.toString())
                .claim("organizationId", UUID.randomUUID().toString())
                .claim("roles", List.of("CUSTOMER"))
                .claim("permissions", List.of())
                .claim("tokenVersion", 3)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (sid != null) {
            builder.claim("sid", sid);
        }
        return builder.build();
    }

    @Test
    void aTokenOfASignedOutDeviceIsRefused() {
        Set<UUID> active = new java.util.HashSet<>(Set.of(SESSION));
        AccountStatePort state = new AccountStatePortStub(active);
        IdentityClaimsJwtValidator validator = new IdentityClaimsJwtValidator(id -> OptionalInt.of(3), state);

        assertThat(validator.validate(token(SESSION.toString())).hasErrors()).isFalse();
        active.clear();
        assertThat(validator.validate(token(SESSION.toString())).hasErrors()).isTrue();
    }

    @Test
    void malformedSessionClaimsAndMissingPortsFailClosed() {
        IdentityClaimsJwtValidator validator = new IdentityClaimsJwtValidator(id -> OptionalInt.of(3),
                new AccountStatePortStub(Set.of(SESSION)));
        assertThat(validator.validate(token("not-a-uuid")).hasErrors()).isTrue();
        assertThat(validator.validate(token(42)).hasErrors()).isTrue();
        assertThat(new IdentityClaimsJwtValidator(id -> OptionalInt.of(3)).validate(token(SESSION.toString())).hasErrors())
                .isTrue();
    }

    @Test
    void tokensWithoutASessionStillOnlyCheckTheVersion() {
        IdentityClaimsJwtValidator validator = new IdentityClaimsJwtValidator(id -> OptionalInt.of(3),
                new AccountStatePortStub(Set.of()));
        assertThat(validator.validate(token(null)).hasErrors()).isFalse();
    }

    /** 会话是否有效看给定的集合，其余同 fail-closed。 */
    private static final class AccountStatePortStub extends FailClosedAccountStatePortAdapter {
        private final Set<UUID> active;

        AccountStatePortStub(Set<UUID> active) {
            this.active = active;
        }

        @Override
        public boolean isSessionActive(UUID sessionId, UUID subjectId) {
            return SUBJECT.equals(subjectId) && active.contains(sessionId);
        }
    }

    private static class FailClosedAccountStatePortAdapter implements AccountStatePort {
        private final FailClosedAccountStatePort delegate = new FailClosedAccountStatePort();

        public java.util.Optional<UUID> openSession(UUID subjectId, com.lowaltitude.inspection.identity.domain.LoginDevice device, String method) {
            return delegate.openSession(subjectId, device, method);
        }

        public boolean isSessionActive(UUID sessionId, UUID subjectId) {
            return delegate.isSessionActive(sessionId, subjectId);
        }

        public void signOut(UUID sessionId, UUID subjectId) {
        }

        public com.lowaltitude.inspection.identity.domain.LoginSessionStatus sessionStatus(UUID sessionId, UUID subjectId) {
            return delegate.sessionStatus(sessionId, subjectId);
        }

        public void touchSession(UUID sessionId, UUID subjectId) {
        }

        public com.lowaltitude.inspection.identity.domain.CurrentIdentity currentIdentity(UUID subjectId) {
            return delegate.currentIdentity(subjectId);
        }

        public java.util.Optional<String> maskedPhone(UUID subjectId) {
            return delegate.maskedPhone(subjectId);
        }
    }
}
