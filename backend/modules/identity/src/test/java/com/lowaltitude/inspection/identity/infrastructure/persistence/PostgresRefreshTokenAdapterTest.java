package com.lowaltitude.inspection.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.RefreshTokenRecord;
import com.lowaltitude.inspection.identity.domain.RefreshTokenRevokeReason;
import com.lowaltitude.inspection.identity.domain.RefreshTokenStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class PostgresRefreshTokenAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-22T02:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void storesActiveTokensAndMapsRowsBack() {
        StubMapper mapper = new StubMapper();
        PostgresRefreshTokenAdapter adapter = new PostgresRefreshTokenAdapter(mapper);
        RefreshTokenRecord token = record(RefreshTokenStatus.ACTIVE);

        adapter.save(token);

        assertThat(mapper.inserted).isEqualTo(token.tokenId());
        assertThat(adapter.findByHash(HASH)).contains(token);
    }

    @Test
    void refusesToStoreATokenThatIsNotActive() {
        PostgresRefreshTokenAdapter adapter = new PostgresRefreshTokenAdapter(new StubMapper());

        assertThatThrownBy(() -> adapter.save(record(RefreshTokenStatus.ROTATED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRowWithAnUnknownStatusIsNotATokenAndOnlyOneRowCountsAsRotated() {
        StubMapper mapper = new StubMapper();
        mapper.row = row("SOMETHING_ELSE");
        PostgresRefreshTokenAdapter adapter = new PostgresRefreshTokenAdapter(mapper);

        assertThat(adapter.findByHash(HASH)).isEmpty();
        mapper.updated = 0;
        assertThat(adapter.markRotated(UUID.randomUUID(), UUID.randomUUID(), NOW)).isFalse();
        mapper.updated = 1;
        assertThat(adapter.markRotated(UUID.randomUUID(), UUID.randomUUID(), NOW)).isTrue();
        mapper.updated = 3;
        assertThat(adapter.revokeFamily(UUID.randomUUID(), RefreshTokenRevokeReason.LOGOUT, NOW)).isEqualTo(3);
        assertThat(mapper.reason).isEqualTo("LOGOUT");
    }

    @Test
    void statementsUseBoundParametersAndOnlyChangeActiveTokens() throws Exception {
        for (Method method : IdentityRefreshTokenMapper.class.getDeclaredMethods()) {
            assertThat(sql(method)).doesNotContain("${");
        }
        assertThat(sql(IdentityRefreshTokenMapper.class.getMethod("markRefreshTokenRotated", UUID.class, UUID.class, OffsetDateTime.class)))
                .contains("WHERE id = #{id}")
                .contains("AND status = 'ACTIVE'");
        assertThat(sql(IdentityRefreshTokenMapper.class.getMethod("revokeRefreshTokenFamily", UUID.class, String.class, OffsetDateTime.class)))
                .contains("WHERE family_id = #{familyId}")
                .contains("AND status = 'ACTIVE'");
    }

    private static String sql(Method method) {
        return Arrays.stream(method.getAnnotations())
                .map(annotation -> annotation instanceof Select select ? String.join(" ", select.value())
                        : annotation instanceof Insert insert ? String.join(" ", insert.value())
                        : annotation instanceof Update update ? String.join(" ", update.value()) : "")
                .reduce("", String::concat);
    }

    private static final UUID TOKEN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FAMILY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static RefreshTokenRecord record(RefreshTokenStatus status) {
        return new RefreshTokenRecord(TOKEN_ID, FAMILY_ID, USER_ID, HASH, 7, status,
                NOW, NOW.plusSeconds(3600), NOW.plusSeconds(7200));
    }

    private static PersistedRefreshToken row(String status) {
        return new PersistedRefreshToken(TOKEN_ID, FAMILY_ID, USER_ID, HASH, 7, status,
                NOW.atOffset(ZoneOffset.UTC), NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(7200).atOffset(ZoneOffset.UTC));
    }

    private static final class StubMapper implements IdentityRefreshTokenMapper {
        UUID inserted;
        PersistedRefreshToken row;
        int updated;
        String reason;

        @Override
        public int insertRefreshToken(UUID id, UUID familyId, UUID userId, String tokenHash, int tokenVersion,
                                      OffsetDateTime issuedAt, OffsetDateTime expiresAt, OffsetDateTime familyExpiresAt) {
            inserted = id;
            row = new PersistedRefreshToken(id, familyId, userId, tokenHash, tokenVersion, "ACTIVE",
                    issuedAt, expiresAt, familyExpiresAt);
            return 1;
        }

        @Override
        public Optional<PersistedRefreshToken> findRefreshTokenByHash(String tokenHash) {
            return Optional.ofNullable(row).filter(candidate -> candidate.tokenHash().equals(tokenHash));
        }

        @Override
        public int markRefreshTokenRotated(UUID id, UUID replacedBy, OffsetDateTime at) {
            return updated;
        }

        @Override
        public int revokeRefreshTokenFamily(UUID familyId, String reason, OffsetDateTime at) {
            this.reason = reason;
            return updated;
        }
    }
}
