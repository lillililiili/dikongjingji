package com.lowaltitude.inspection.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.crypto.KeyGenerator;

import com.lowaltitude.inspection.identity.domain.LoginDevice;
import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.IdentityOpsStatsMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.IdentityPersistenceMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.IdentityRefreshTokenMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OpsUserLookupMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PostgresIdentityAccountAdapter;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PostgresRefreshTokenAdapter;
import com.lowaltitude.inspection.infrastructure.persistence.PostgresUuidTypeHandler;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jwt.SignedJWT;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Runs against the isolated E2E PostgreSQL database. Every fixture and token is rolled back. */
class IdentityGrantClockPostgresIT {

    @ParameterizedTest
    @ValueSource(ints = {-5581, 5581})
    void firstPhoneLoginUsesTheRoleWindowInPostgresEvenWithClockSkew(int hostOffsetMicros) throws Exception {
        String database = System.getenv("E2E_PG_DATABASE");
        assertThat(database).matches("inspection_e2e(?:_[a-z0-9_]+)?");
        try (Connection connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15438/" + database,
                "inspection_e2e", System.getenv("E2E_DATABASE_PASSWORD"))) {
            // Isolate count comparisons from other acceptance tests creating their own customers.
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            SingleConnectionDataSource source = new SingleConnectionDataSource(connection, true);
            JdbcTemplate sql = new JdbcTemplate(source);
            Configuration config = new Configuration(new Environment("e2e", new JdbcTransactionFactory(), source));
            config.setMapUnderscoreToCamelCase(true);
            config.getTypeHandlerRegistry().register(PostgresUuidTypeHandler.class);
            config.addMapper(AccountMapper.class);
            config.addMapper(IdentityPersistenceMapper.class);
            config.addMapper(IdentityRefreshTokenMapper.class);
            config.addMapper(OrganizationOnboardingMapper.class);
            config.addMapper(IdentityOpsStatsMapper.class);
            config.addMapper(OpsUserLookupMapper.class);

            SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(source.getConnection());
            try {
                OffsetDateTime accountTime = sql.queryForObject("SELECT statement_timestamp()", OffsetDateTime.class);
                UUID suffix = UUID.randomUUID();
                String phone = "139%08d".formatted(Math.floorMod(suffix.getLeastSignificantBits(), 100_000_000));
                AccountMapper registration = session.getMapper(AccountMapper.class);
                var user = registration.insertPhoneUser("clock-" + suffix, "时钟回归", phone,
                        "clock-" + suffix, "时钟回归", accountTime);
                assertThat(registration.insertOrganizationMember(user.organizationId(), user.userId(), accountTime)).isOne();
                assertThat(registration.grantCustomerRole(user.userId())).isOne();
                var grantedAt = sql.queryForObject("SELECT valid_from FROM identity_user_role WHERE user_id = ?",
                        OffsetDateTime.class, user.userId());
                OffsetDateTime hostTime = grantedAt.plusNanos(hostOffsetMicros * 1_000L);

                assertThat(sql.update("""
                        INSERT INTO identity_user_role (user_id, role_id, valid_from, valid_until)
                        SELECT ?, id,
                               CASE WHEN role_code = 'SYSTEM' THEN statement_timestamp() + interval '1 hour'
                                    ELSE statement_timestamp() - interval '1 second' END,
                               CASE WHEN role_code = 'PLATFORM_OPERATOR' THEN statement_timestamp() END
                        FROM identity_role WHERE role_code IN ('SYSTEM', 'PLATFORM_OPERATOR')
                        """, user.userId())).isEqualTo(2);
                var identities = session.getMapper(IdentityPersistenceMapper.class);
                assertThat(identities.findRolePermissions(user.userId()))
                        .isNotEmpty().allSatisfy(role -> {
                            assertThat(role.validFrom()).isEqualTo(grantedAt.toInstant());
                            assertThat(role.evaluatedAt()).isAfterOrEqualTo(grantedAt.toInstant());
                        });
                var refresh = new PostgresRefreshTokenAdapter(session.getMapper(IdentityRefreshTokenMapper.class));
                var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KeyGenerator.getInstance("HmacSHA256").generateKey()));
                var auth = new AuthenticationService(new PostgresIdentityAccountAdapter(identities), refresh,
                        new BCryptPasswordEncoder(), encoder, Clock.fixed(hostTime.toInstant(), ZoneOffset.UTC));
                var token = auth.issueForVerifiedAccount(user.userId(), LoginDevice.unknown()).orElseThrow();
                var claims = SignedJWT.parse(token.accessToken()).getJWTClaimsSet();
                assertThat(claims.getStringListClaim("roles")).containsExactly("CUSTOMER");
                assertThat(claims.getStringListClaim("permissions")).contains("inspection.reservation.create");
                assertThat(refresh.findByHash(AuthenticationService.hash(token.refreshToken())).orElseThrow().issuedAt())
                        .isEqualTo(hostTime.toInstant());

                var onboarding = session.getMapper(OrganizationOnboardingMapper.class);
                assertThat(onboarding.revokeRole(user.userId(), "CUSTOMER", null)).isOne();
                assertThat(registration.hasCustomerRole(user.userId())).isFalse();
                assertThat(onboarding.findAccountRoles(user.organizationId(), List.of(user.userId()))).isEmpty();
                var revokedToken = auth.issueForVerifiedAccount(user.userId(), LoginDevice.unknown()).orElseThrow();
                assertThat(SignedJWT.parse(revokedToken.accessToken()).getJWTClaimsSet()
                        .getStringListClaim("roles")).isEmpty();
                assertThat(onboarding.grantRole(user.userId(), "CUSTOMER", null)).isOne();
                assertThat(registration.hasCustomerRole(user.userId())).isTrue();
                assertThat(onboarding.findAccountRoles(user.organizationId(), List.of(user.userId())))
                        .extracting(OrganizationOnboardingMapper.AccountRoleRow::roleCode).containsExactly("CUSTOMER");
                var restoredToken = auth.issueForVerifiedAccount(user.userId(), LoginDevice.unknown()).orElseThrow();
                assertThat(SignedJWT.parse(restoredToken.accessToken()).getJWTClaimsSet()
                        .getStringListClaim("roles")).containsExactly("CUSTOMER");
                assertOperatorLookupAndDashboardFollowEffectiveGrants(sql, session, user.userId(), user.organizationId());
            } finally {
                try {
                    session.rollback(true);
                } finally {
                    session.close();
                }
            }
        }
    }

    private static void assertOperatorLookupAndDashboardFollowEffectiveGrants(
            JdbcTemplate sql, SqlSession session, UUID userId, UUID organizationId) {
        var lookup = session.getMapper(OpsUserLookupMapper.class);
        var stats = session.getMapper(IdentityOpsStatsMapper.class);
        var onboarding = session.getMapper(OrganizationOnboardingMapper.class);
        long withCustomer = stats.customers(OpsStatsExclusion.NONE);
        var excludedCustomer = new OpsStatsExclusion(List.of(userId), List.of());
        assertThat(stats.customers(excludedCustomer)).isEqualTo(withCustomer - 1);
        assertThat(lookup.findUser(userId).roles()).isEqualTo("CUSTOMER");

        UUID otherOrganization = sql.queryForObject("""
                INSERT INTO identity_organization (organization_code, organization_name, organization_type)
                VALUES (?, '机构范围回归', 'PROVIDER') RETURNING id
                """, UUID.class, "scope-" + UUID.randomUUID());
        var excludedProvider = new OpsStatsExclusion(List.of(), List.of(otherOrganization));
        assertThat(stats.providers(excludedProvider)).isEqualTo(stats.providers(OpsStatsExclusion.NONE) - 1);
        assertThat(onboarding.grantRole(userId, "LIVE_HOST", otherOrganization)).isOne();
        assertThat(onboarding.grantRole(userId, "PROVIDER_OPERATOR", organizationId)).isOne();
        assertThat(lookup.findUsers(null, userId, null, null, 20, 0))
                .extracting(OpsUserLookupMapper.UserRow::roles).containsExactly("CUSTOMER,PROVIDER_OPERATOR");

        assertThat(sql.update("""
                UPDATE identity_organization_member SET member_status = 'DISABLED'
                WHERE user_id = ? AND organization_id = ?
                """, userId, organizationId)).isOne();
        assertThat(sql.update("""
                UPDATE identity_user_role SET valid_from = statement_timestamp() + interval '1 hour'
                WHERE user_id = ? AND role_id = (SELECT id FROM identity_role WHERE role_code = 'CUSTOMER')
                """, userId)).isOne();
        session.clearCache();
        assertThat(lookup.findUser(userId).roles()).isEmpty();
        assertThat(stats.customers(OpsStatsExclusion.NONE)).isEqualTo(withCustomer - 1);
        assertThat(stats.customers(excludedCustomer)).isEqualTo(withCustomer - 1);
    }
}
