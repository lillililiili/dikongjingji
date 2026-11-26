package com.lowaltitude.inspection.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本（S5-7、S5-8）：只用绑定参数、不删行；验证码只按号码和用途取、
 * 加锁后再核对；下载凭据用掉即清、要没过期；会话按本人撤销；企业读取都以本人有效成员关系为条件。
 * 真实执行用 infra/scripts/identity-account-smoke.cjs 对开发库跑过。再检查 V009、V010 迁移的约束。
 */
class AccountMapperSqlTest {

    @Test
    void customerGrantAndEligibilityUseTheDatabaseStatementClock() {
        assertThat(normalized(sql("grantCustomerRole")))
                .contains("SELECT #{userId}, r.id, NULL, statement_timestamp()", "r.scope_type = 'GLOBAL'");
        assertThat(normalized(sql("hasCustomerRole")))
                .contains("ur.valid_from <= statement_timestamp()", "ur.valid_until > statement_timestamp()",
                        "r.scope_type = 'GLOBAL'", "ur.organization_id IS NULL");
    }

    @Test
    void operatorLookupAndCustomerMetricsShareEffectiveRoleRules() {
        for (String query : new String[]{OpsUserLookupMapper.COLUMNS, IdentityOpsStatsMapper.CUSTOMER}) {
            assertThat(normalized(query)).contains("r.status = 'ACTIVE'", "statement_timestamp() >= ur.valid_from",
                    "ur.valid_until > statement_timestamp()", "r.scope_type = 'GLOBAL' AND ur.organization_id IS NULL");
        }
        assertThat(normalized(OpsUserLookupMapper.COLUMNS)).contains(
                "r.scope_type = 'ORGANIZATION' AND ur.organization_id = u.default_organization_id",
                "m.organization_id = ur.organization_id AND m.user_id = u.id",
                "m.member_status = 'ACTIVE' AND o.status = 'ACTIVE'");
        assertThat(normalized(IdentityOpsStatsMapper.CUSTOMER)).contains("r.role_code = 'CUSTOMER'");
    }

    @Test
    void everyStatementBindsItsParametersAndDeletesNothing() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(AccountMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql)
                .doesNotContain("${")
                .doesNotContain("DELETE FROM"));
        // 读账号从不读口令摘要（新注册账号写入的是不能通过校验的“!”）。
        assertThat(normalized(AccountMapper.USER_COLUMNS)).doesNotContain("password_hash");
    }

    @Test
    void codesAreLockedBeforeTheyAreCheckedAndUsedOnce() {
        assertThat(normalized(sql("lockUsableSmsCode")))
                .contains("WHERE phone = #{phone} AND purpose = #{purpose} AND consumed_at IS NULL")
                .contains("AND expires_at > #{at} AND failed_attempts < #{maxAttempts}")
                .endsWith("FOR UPDATE");
        assertThat(normalized(sql("consumeSmsCode"))).endsWith("WHERE id = #{id} AND consumed_at IS NULL");
        assertThat(normalized(sql("lockPhoneGuard"))).endsWith("FOR UPDATE");
        assertThat(normalized(sql("countSentSince"))).contains("purpose <> 'CHANGE_TICKET'");
    }

    @Test
    void downloadTokensAreUsedOnceAndOnlyWhileFresh() {
        assertThat(normalized(sql("consumeDownloadToken")))
                .contains("SET download_token_hash = NULL, download_token_expires_at = NULL, download_count = download_count + 1")
                .contains("AND status = 'READY' AND download_token_hash = #{tokenHash}")
                .contains("AND download_token_expires_at > #{at} AND expires_at > #{at}");
        assertThat(normalized(sql("setDownloadToken"))).contains("AND user_id = #{userId} AND status = 'READY'");
        assertThat(normalized(sql("findExport"))).contains("WHERE id = #{exportId} AND user_id = #{userId}");
        assertThat(normalized(sql("lockPendingExports"))).endsWith("FOR UPDATE SKIP LOCKED");
    }

    @Test
    void sessionsAreScopedToTheirOwner() {
        for (String method : new String[] {"isSessionActive", "findSession", "revokeSession"}) {
            assertThat(normalized(sql(method))).as(method).contains("AND user_id = #{userId}");
        }
        assertThat(normalized(sql("revokeOtherSessions"))).contains("WHERE user_id = #{userId}");
    }

    @Test
    void enterprisesAreReadOnlyThroughAnActiveMembership() {
        assertThat(normalized(AccountMapper.ENTERPRISE_COLUMNS))
                .contains("AND m.user_id = #{userId} AND m.status = 'ACTIVE'")
                .contains("WHERE e.status = 'ACTIVE' AND o.status = 'ACTIVE'");
        assertThat(normalized(sql("findActingEnterprise")))
                .contains("m.user_id = u.id AND m.status = 'ACTIVE'");
        assertThat(normalized(sql("disableOrganization"))).contains("AND organization_type = 'CUSTOMER'");
    }

    @Test
    void anonymizationClosesTheAccountAndInvalidatesTokens() {
        assertThat(normalized(sql("anonymizeUser")))
                .contains("display_name = '已注销用户'")
                .contains("password_hash = '!'")
                .contains("mobile = NULL")
                .contains("status = 'CLOSED'")
                .contains("token_version = token_version + 1");
        assertThat(normalized(sql("insertPhoneUser")))
                .contains("ON CONFLICT (mobile) WHERE mobile IS NOT NULL DO NOTHING");
    }

    @Test
    void migrationsKeepCodesHashedAndOneMobilePerAccount() throws Exception {
        String v009 = resource("/db/migration/identity/V009__identity_enterprise.sql");
        String v010 = resource("/db/migration/identity/V010__identity_phone_account.sql");
        assertThat(v009)
                .contains("CHECK (credit_code ~ '^[0-9A-HJ-NPQRTUWXY]{18}$')")
                .contains("CHECK (member_role IN ('ADMIN', 'MEMBER'))")
                .contains("code_hash char(64)")
                .contains("ADD COLUMN acting_organization_id uuid")
                .doesNotContain("DROP TABLE");
        assertThat(v010)
                .contains("CREATE UNIQUE INDEX uq_identity_user_mobile")
                .contains("code_hash char(64) NOT NULL")
                .contains("CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED', 'CLOSED'))")
                .contains("download_token_hash char(64)")
                .doesNotContain("DROP TABLE")
                .doesNotContain("UPDATE identity_user");
    }

    private String resource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : AccountMapper.class.getDeclaredMethods()) {
            String sql = method.isAnnotationPresent(Select.class) ? String.join("\n", method.getAnnotation(Select.class).value())
                    : method.isAnnotationPresent(Insert.class) ? String.join("\n", method.getAnnotation(Insert.class).value())
                    : method.isAnnotationPresent(Update.class) ? String.join("\n", method.getAnnotation(Update.class).value())
                    : null;
            if (sql != null) {
                statements.put(method.getName(), sql);
            }
        }
        return statements;
    }

    private static String sql(String method) {
        return Arrays.stream(AccountMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(method))
                .findFirst()
                .map(candidate -> statements().get(candidate.getName()))
                .orElseThrow();
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
