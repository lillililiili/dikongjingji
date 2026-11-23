package com.lowaltitude.inspection.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IdentityMigrationTest {

    @Test
    void v002AddsNullableDefaultOrganizationForeignKeyAndIndex() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/identity/V002__identity_default_organization.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("ADD COLUMN default_organization_id uuid")
                .doesNotContain("default_organization_id uuid NOT NULL")
                .contains("REFERENCES identity_organization(id)")
                .contains("CREATE INDEX idx_identity_user_default_organization");
    }

    // G1：平台客服角色只是一个全局角色，不带任何权限，和平台运营分开。
    @Test
    void v004AddsAGlobalPlatformSupportRoleWithoutPermissions() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/identity/V004__identity_platform_support_role.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("VALUES ('PLATFORM_SUPPORT', '平台客服', 'GLOBAL')")
                .contains("ON CONFLICT (role_code) DO NOTHING")
                .doesNotContain("identity_role_permission")
                .doesNotContain("identity_user");
    }

    // R6-4：资质审核通过时间只加一个可空列，不回填、不加约束。
    @Test
    void v005AddsANullableQualificationReviewTime() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/identity/V005__identity_organization_qualification.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("ALTER TABLE identity_organization")
                .contains("ADD COLUMN qualification_reviewed_at timestamptz;")
                .doesNotContain("NOT NULL")
                .doesNotContain("UPDATE ")
                .doesNotContain("DEFAULT");
    }

    // 登录续期：刷新令牌只存哈希，状态、过期和撤销原因都有约束，旧迁移不动。
    @Test
    void v008StoresRefreshTokensOnlyAsHashesWithRotationAndRevocation() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/identity/V008__identity_refresh_token.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("CREATE TABLE identity_refresh_token")
                .contains("token_hash varchar(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$')")
                .contains("CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED'))")
                .contains("revoke_reason IN ('LOGOUT', 'REUSE_DETECTED', 'ACCOUNT_CHANGED')")
                .contains("family_expires_at timestamptz NOT NULL")
                .contains("version integer NOT NULL DEFAULT 0")
                .contains("REFERENCES identity_user(id)")
                .doesNotContain("refresh_token varchar")
                .doesNotContain("ALTER TABLE identity_user");
    }
}
