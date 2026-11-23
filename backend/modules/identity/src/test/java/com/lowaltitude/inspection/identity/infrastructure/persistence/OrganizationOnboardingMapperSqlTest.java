package com.lowaltitude.inspection.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本（S3-1 机构入驻与资质审核）：只用绑定参数；只碰经营机构和企业客户；
 * 改资料、审核按版本号；角色只授不删（收回只标结束时间）；读账号不读口令和手机号。再检查 V007 迁移的约束。
 */
class OrganizationOnboardingMapperSqlTest {

    @Test
    void everyStatementBindsItsParameters() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(OrganizationOnboardingMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        assertThat(statements.values()).noneMatch(sql -> sql.contains("DELETE"))
                .noneMatch(sql -> sql.contains("UPDATE identity_organization_operation"));
    }

    @Test
    void organizationsAreLimitedToProvidersAndCustomers() {
        for (String method : List.of("countOrganizations", "findOrganizations")) {
            assertThat(normalized(sql(method))).as(method)
                    .contains("WHERE o.organization_type IN ('PROVIDER', 'CUSTOMER')");
        }
        for (String method : List.of("findOrganization", "lockOrganization")) {
            assertThat(normalized(sql(method))).as(method)
                    .contains("o.organization_type IN ('PROVIDER', 'CUSTOMER')");
        }
        assertThat(normalized(sql("lockOrganization"))).endsWith("FOR UPDATE");
        assertThat(normalized(sql("insertOrganization")))
                .contains("ON CONFLICT (ops_create_key) WHERE ops_create_key IS NOT NULL DO NOTHING RETURNING id");
    }

    @Test
    void qualificationChangesGoBackToPendingAndReviewsNeedTheCurrentVersion() {
        assertThat(normalized(sql("updateQualification")))
                .contains("status = 'SUBMITTED'")
                .contains("reviewed_at = NULL, reviewed_by = NULL, reject_reason = NULL")
                .endsWith("WHERE organization_id = #{organizationId} AND version = #{version}");
        assertThat(normalized(sql("reviewQualification")))
                .endsWith("WHERE organization_id = #{organizationId} AND status = 'SUBMITTED' AND version = #{version}");
        assertThat(normalized(sql("reopenQualification"))).endsWith("AND status <> 'SUBMITTED'");
        assertThat(normalized(sql("updateQualificationReviewedAt")))
                .contains("SET qualification_reviewed_at = #{reviewedAt}");
    }

    @Test
    void rolesAreGrantedOrEndedAndAccountsNeverExposeSecrets() {
        assertThat(normalized(sql("grantRole")))
                .contains("ON CONFLICT (user_id, role_id, COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid))")
                .contains("DO UPDATE SET valid_from = EXCLUDED.valid_from, valid_until = NULL");
        assertThat(normalized(sql("revokeRole"))).contains("SET valid_until = #{at}")
                .contains("ur.organization_id IS NOT DISTINCT FROM CAST(#{organizationId} AS uuid)");
        assertThat(normalized(sql("bumpTokenVersion"))).contains("SET token_version = token_version + 1");
        for (String method : List.of("findAccounts", "lockAccount", "findAccountRoles")) {
            assertThat(sql(method)).as(method).doesNotContain("password").doesNotContain("mobile");
        }
        // 用 @Select 写的插入要清一级缓存，不然同一事务里随后的查询读到旧结果。
        for (String method : List.of("insertOrganization", "insertAttachment", "insertAccount")) {
            Options options = method(method).getAnnotation(Options.class);
            assertThat(options).as(method).isNotNull();
            assertThat(options.flushCache()).isEqualTo(Options.FlushCachePolicy.TRUE);
        }
    }

    @Test
    void assembledQueriesKeepTheirKeywordsApart() {
        assertThat(statements().values()).allSatisfy(sql -> assertThat(sql)
                .doesNotContainPattern("[0-9a-z_)]FROM ")
                .doesNotContainPattern("[0-9a-z_)]WHERE ")
                .doesNotContainPattern("[0-9a-z_)]ORDER BY")
                .doesNotContainPattern("[0-9a-z_)]LIMIT "));
    }

    @Test
    void migrationKeepsTheRulesInTheDatabase() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/identity/V007__identity_organization_onboarding.sql")) {
            assertThat(stream).isNotNull();
            migration = normalized(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(migration)
                .contains("ADD COLUMN city_code varchar(16), ADD COLUMN ops_create_key uuid;")
                .contains("license_number varchar(18) NOT NULL CHECK (license_number ~ '^[0-9A-HJ-NPQRTUWXY]{18}$')")
                .contains("status varchar(16) NOT NULL CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED'))")
                .contains("CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))")
                .contains("content_type IN ('application/pdf', 'image/jpeg', 'image/png')")
                .contains("size_bytes > 0 AND size_bytes <= 10485760")
                .contains("CREATE TABLE identity_organization_operation");
        // 只加不改：不回填、不删、不动已有列。
        assertThat(migration).doesNotContain("UPDATE ").doesNotContain("DELETE FROM").doesNotContain("DROP ")
                .doesNotContain("INSERT INTO");
    }

    private static Method method(String name) {
        return Arrays.stream(OrganizationOnboardingMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : OrganizationOnboardingMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            Update update = method.getAnnotation(Update.class);
            Insert insert = method.getAnnotation(Insert.class);
            String[] text = select != null ? select.value() : update != null ? update.value()
                    : insert != null ? insert.value() : null;
            if (text != null) {
                statements.put(method.getName(), String.join("\n", text));
            }
        }
        return statements;
    }

    private static String sql(String methodName) {
        String sql = statements().get(methodName);
        assertThat(sql).as(methodName).isNotNull();
        return sql;
    }

    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }
}
