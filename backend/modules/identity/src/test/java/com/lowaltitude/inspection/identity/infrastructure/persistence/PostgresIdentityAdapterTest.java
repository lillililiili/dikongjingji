package com.lowaltitude.inspection.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityAccount;
import org.junit.jupiter.api.Test;

class PostgresIdentityAdapterTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORGANIZATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_ORGANIZATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-07-28T02:00:00Z");

    @Test
    void mapsOnlyActiveCurrentRolesForTheDefaultOrganization() {
        StubIdentityPersistenceReader reader = new StubIdentityPersistenceReader(
                validUser(),
                List.of(
                        role("PROVIDER_OPERATOR", "ACTIVE", "ORGANIZATION", ORGANIZATION_ID,
                                NOW.minusSeconds(60), NOW.plusSeconds(60), "inspection.order.read.assigned"),
                        role("GLOBAL_AUDITOR", "ACTIVE", "GLOBAL", null,
                                NOW.minusSeconds(60), null, "inspection.audit.read"),
                        role("OTHER_TENANT", "ACTIVE", "ORGANIZATION", OTHER_ORGANIZATION_ID,
                                NOW.minusSeconds(60), null, "inspection.order.read.all"),
                        role("DISABLED_ROLE", "DISABLED", "ORGANIZATION", ORGANIZATION_ID,
                                NOW.minusSeconds(60), null, "inspection.plan.read.all"),
                        role("EXPIRED_ROLE", "ACTIVE", "ORGANIZATION", ORGANIZATION_ID,
                                NOW.minusSeconds(120), NOW.minusSeconds(1), "inspection.delivery.download.all")));
        PostgresIdentityAccountAdapter adapter = new PostgresIdentityAccountAdapter(reader);

        Optional<IdentityAccount> account = adapter.findByLoginName("provider");

        assertThat(account).isPresent();
        assertThat(account.orElseThrow().organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(account.orElseThrow().roles()).containsExactlyInAnyOrder("PROVIDER_OPERATOR", "GLOBAL_AUDITOR");
        assertThat(account.orElseThrow().permissions())
                .containsExactlyInAnyOrder("inspection.order.read.assigned", "inspection.audit.read");
    }

    // 登录续期按主体重读账号：同样只认默认组织下当前有效的角色，组织或成员无效时读不到。
    @Test
    void readsTheAccountBySubjectForRenewalWithTheSameRules() {
        PostgresIdentityAccountAdapter adapter = new PostgresIdentityAccountAdapter(
                new StubIdentityPersistenceReader(validUser(), List.of(
                        role("PROVIDER_OPERATOR", "ACTIVE", "ORGANIZATION", ORGANIZATION_ID,
                                NOW.minusSeconds(60), null, "inspection.order.read.assigned"),
                        role("OTHER_TENANT", "ACTIVE", "ORGANIZATION", OTHER_ORGANIZATION_ID,
                                NOW.minusSeconds(60), null, "inspection.order.read.all"))));

        IdentityAccount account = adapter.findBySubjectId(USER_ID).orElseThrow();
        assertThat(account.roles()).containsExactly("PROVIDER_OPERATOR");
        assertThat(account.permissions()).containsExactly("inspection.order.read.assigned");

        PostgresIdentityAccountAdapter withoutMembership = new PostgresIdentityAccountAdapter(
                new StubIdentityPersistenceReader(user(ORGANIZATION_ID, "ACTIVE", "DISABLED"), List.of()));
        assertThat(withoutMembership.findBySubjectId(USER_ID)).isEmpty();
    }

    @Test
    void failsClosedWhenDefaultOrganizationOrMembershipIsInvalid() {
        for (PersistedIdentityUser invalid : List.of(
                user(null, "ACTIVE", "ACTIVE"),
                user(ORGANIZATION_ID, "DISABLED", "ACTIVE"),
                user(ORGANIZATION_ID, "ACTIVE", "DISABLED"))) {
            PostgresIdentityAccountAdapter adapter = new PostgresIdentityAccountAdapter(
                    new StubIdentityPersistenceReader(invalid, List.of()));

            assertThat(adapter.findByLoginName("provider")).isEmpty();
        }
    }

    @Test
    void evaluatesNewFutureAndExpiredGrantsAtTheSameDatabaseInstant() {
        Instant databaseTime = NOW.plusNanos(5_581_000);
        StubIdentityPersistenceReader reader = new StubIdentityPersistenceReader(validUser(), List.of(
                new PersistedRolePermission("CUSTOMER", "ACTIVE", "GLOBAL", null,
                        databaseTime, null, "new-grant", databaseTime),
                new PersistedRolePermission("FUTURE", "ACTIVE", "GLOBAL", null,
                        databaseTime.plusNanos(1_000), null, "pending", databaseTime),
                new PersistedRolePermission("EXPIRED", "ACTIVE", "GLOBAL", null,
                        NOW, databaseTime, "expired", databaseTime)));

        IdentityAccount account = new PostgresIdentityAccountAdapter(reader).findBySubjectId(USER_ID).orElseThrow();

        assertThat(account.roles()).containsExactly("CUSTOMER");
        assertThat(account.permissions()).containsExactly("new-grant");
    }

    @Test
    void tokenVersionAlsoFailsClosedForInvalidIdentityRelationships() {
        StubIdentityPersistenceReader reader = new StubIdentityPersistenceReader(
                user(ORGANIZATION_ID, "DISABLED", "ACTIVE"), List.of());
        PostgresTokenVersionAdapter adapter = new PostgresTokenVersionAdapter(reader);

        OptionalInt tokenVersion = adapter.findCurrentTokenVersion(USER_ID);

        assertThat(tokenVersion).isEmpty();
    }

    @Test
    void tokenVersionLoadsForAnActiveUserInAnActiveDefaultOrganization() {
        PostgresTokenVersionAdapter adapter = new PostgresTokenVersionAdapter(
                new StubIdentityPersistenceReader(validUser(), List.of()));

        assertThat(adapter.findCurrentTokenVersion(USER_ID)).hasValue(7);
    }

    private static PersistedIdentityUser validUser() {
        return user(ORGANIZATION_ID, "ACTIVE", "ACTIVE");
    }

    private static PersistedIdentityUser user(UUID organizationId, String organizationStatus, String memberStatus) {
        return new PersistedIdentityUser(
                USER_ID, "provider", "$2a$10$hash", "ACTIVE", 7,
                organizationId, organizationStatus, memberStatus);
    }

    private static PersistedRolePermission role(
            String roleCode,
            String roleStatus,
            String scopeType,
            UUID organizationId,
            Instant validFrom,
            Instant validUntil,
            String permissionCode
    ) {
        return new PersistedRolePermission(
                roleCode, roleStatus, scopeType, organizationId, validFrom, validUntil, permissionCode, NOW);
    }

    private record StubIdentityPersistenceReader(
            PersistedIdentityUser user,
            List<PersistedRolePermission> roles
    ) implements IdentityPersistenceReader {

        @Override
        public Optional<PersistedIdentityUser> findUserByLoginName(String loginName) {
            return Optional.ofNullable(user);
        }

        @Override
        public Optional<PersistedIdentityUser> findUserById(UUID subjectId) {
            return Optional.ofNullable(user);
        }

        @Override
        public List<PersistedRolePermission> findRolePermissions(UUID subjectId) {
            return roles;
        }
    }
}
