package com.lowaltitude.inspection.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 首个平台运营账号引导（S6）的 SQL 文本：只用绑定参数；只加不改不删；只建平台类机构；审计不带口令。
 * （没有可用的数据库测试容器，按现有做法检查文本。）
 */
class PlatformOperatorBootstrapMapperSqlTest {

    @Test
    void everyStatementBindsItsParametersAndOnlyAdds() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(PlatformOperatorBootstrapMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql)
                .doesNotContain("${")
                .doesNotContain("UPDATE ")
                .doesNotContain("DELETE")
                .doesNotContainIgnoringCase("password"));
    }

    @Test
    void onlyPlatformOrganizationsAreFoundOrCreated() {
        assertThat(normalized(statements().get("findActivePlatformOrganization")))
                .contains("WHERE organization_type = 'PLATFORM' AND status = 'ACTIVE'")
                .endsWith("LIMIT 1");
        assertThat(normalized(statements().get("insertPlatformOrganization")))
                .contains("VALUES (#{code}, #{name}, 'PLATFORM', #{at}, #{at})")
                .contains("ON CONFLICT (organization_code) DO NOTHING RETURNING id");
        Options options = method("insertPlatformOrganization").getAnnotation(Options.class);
        assertThat(options).isNotNull();
        assertThat(options.flushCache()).isEqualTo(Options.FlushCachePolicy.TRUE);
        assertThat(normalized(statements().get("loginNameTaken")))
                .isEqualTo("SELECT EXISTS (SELECT 1 FROM identity_user WHERE login_name = #{loginName})");
        assertThat(normalized(statements().get("recordBootstrap")))
                .startsWith("INSERT INTO identity_auth_audit")
                .contains("'PLATFORM_OPERATOR_BOOTSTRAP'");
    }

    private static Method method(String name) {
        return Arrays.stream(PlatformOperatorBootstrapMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : PlatformOperatorBootstrapMapper.class.getDeclaredMethods()) {
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

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")").trim();
    }
}
