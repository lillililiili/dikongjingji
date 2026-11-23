package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

/**
 * S5-9：示例系统通知“隐私政策更新摘要”只在开发环境加载；内容和画板 NoticeDetail 一致，
 * 结构满足 SystemNoticeService 的校验，没有发布人、幂等键和账号。
 */
class DevelopmentNotificationSeedTest {

    private static final String SEED = "/db/development/notification/R__dev_notification_system_notice.sql";

    @Test
    void theSeedLoadsInDevOnlyAfterTheNotificationTables() throws Exception {
        Method seed = ModuleFlywayConfiguration.class.getDeclaredMethod("notificationDevelopmentDataFlyway",
                DataSource.class);

        assertThat(seed.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(seed.getAnnotation(DependsOn.class).value()).containsExactly("notificationFlyway");
        seed.setAccessible(true);
        Flyway flyway = (Flyway) seed.invoke(new ModuleFlywayConfiguration(), mock(DataSource.class));
        assertThat(flyway.getConfiguration().getTable()).isEqualTo("flyway_history_notification_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/notification");
    }

    @Test
    void theNoticeIsTheBoardsPrivacyPolicyUpdateForEveryone() throws Exception {
        String sql = seed();

        assertThat(sql)
                .contains("'隐私政策更新摘要'")
                .contains("'查看主要变化与生效日期'")
                .contains("'PRIVACY_POLICY',\n    'ALL',")
                .contains("TIMESTAMPTZ '2026-11-01 09:00:00+08'")
                .contains("ON CONFLICT (id) DO UPDATE")
                .doesNotContain("notification_message")
                .doesNotContain("crypt(")
                .doesNotContain("PLATFORM_OPERATOR");

        String json = sql.substring(sql.indexOf("$json$") + 6, sql.lastIndexOf("$json$"));
        JsonNode content = new ObjectMapper().readTree(json);
        assertThat(content.get("paragraphs")).hasSize(1);
        assertThat(content.get("facts").findValuesAsText("value")).containsExactly("v1.1", "2026-11-08");
        assertThat(content.get("items").findValuesAsText("title")).containsExactly("参与人信息", "权限用途", "查看与导出");
        assertThat(content.get("items").findValuesAsText("type"))
                .containsExactly("ACTIVITY_PARTICIPANTS", "PRIVACY_PERMISSIONS", "PERSONAL_DATA_EXPORT");
        assertThat(content.get("action").get("label").asText()).isEqualTo("查看完整隐私政策");
        assertThat(content.get("action").get("link").get("agreement").asText()).isEqualTo("PRIVACY_POLICY");
    }

    private String seed() throws Exception {
        try (var stream = getClass().getResourceAsStream(SEED)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
