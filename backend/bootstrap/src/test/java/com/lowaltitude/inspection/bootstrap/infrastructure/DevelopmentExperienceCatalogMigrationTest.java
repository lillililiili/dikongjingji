package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

class DevelopmentExperienceCatalogMigrationTest {

    @Test
    void loadsSampleVenuesOnlyInDevFromAnIsolatedFlywayHistory() throws Exception {
        Method migrationMethod = method("experienceDevelopmentDataFlyway");

        assertThat(migrationMethod.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactly("experienceFlyway");

        migrationMethod.setAccessible(true);
        Flyway flyway = (Flyway) migrationMethod.invoke(
                new ModuleFlywayConfiguration(),
                mock(DataSource.class));

        assertThat(flyway.getConfiguration().getTable())
                .isEqualTo("flyway_history_experience_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/experience");
    }

    @Test
    void experienceTablesMigrateAfterTheModulesTheyReference() {
        Method migrationMethod = method("experienceFlyway");

        assertThat(migrationMethod.getAnnotation(Profile.class)).isNull();
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactly("inspectionFlyway");
    }

    @Test
    void seedIsRepeatableAndCoversEveryTypeCategoryAndTrialMode() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/experience/R__dev_experience_catalog.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("'SIMULATION'")
                .contains("'RECORDED_DEMO'")
                .contains("'SOFTWARE_TRIAL'")
                .contains("'COURSE_TRIAL'")
                .contains("'ROBOT'")
                .contains("'DRONE'")
                .contains("'PARTS'")
                .contains("'ACCOMPANIED_TRIAL'")
                .contains("'DISPLAY_ONLY'")
                .contains("'OFFLINE'")
                .contains("'DRAFT'")
                .contains("'370500'");
        assertThat(sql.split("ON CONFLICT \\(id\\) DO UPDATE", -1)).hasSize(8);
        assertThat(sql).doesNotContain("DELETE FROM").doesNotContain("TRUNCATE");
    }

    // R6-2 先体验：体验项目和馆内设备照画板关联商城开发数据里的商品（只写商品 ID），重跑时关联跟着更新。
    @Test
    void seedLinksItemsAndDevicesToTheSampleProducts() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/experience/R__dev_experience_catalog.sql")) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("INSERT INTO experience_item (id, type, title, summary, login_required, sort_order, status, product_id)")
                .contains("INSERT INTO venue_device (id, venue_id, category, name, summary, trial_mode, sort_order, product_id)")
                .contains("'机器人避障模拟', '约 3 分钟 · 免登录可试 · 手机即可', false, 10, 'PUBLISHED', 'c2000000-0000-4000-8000-000000000101'")
                .contains("'3 分钟看懂编程机器人', '视频 3:12 · 示例机器人商家', false, 30, 'PUBLISHED', 'c2000000-0000-4000-8000-000000000101'")
                .contains("'无人机起降与悬停', '约 4 分钟 · 免登录可试', false, 20, 'PUBLISHED', 'c2000000-0000-4000-8000-000000000201'")
                .contains("'巡检机器狗', '园区巡逻与巡检演示', 'DISPLAY_ONLY', 30, 'c2000000-0000-4000-8000-000000000401'");
        assertThat(sql.split("product_id = EXCLUDED.product_id", -1)).hasSize(3);
        // 不碰商城的表。
        assertThat(sql).doesNotContain("mall_product");
    }

    // 场次相对执行当天排；种子里的日期占位符让它每天自动重跑，不用再手改文件。
    @Test
    void seedReRunsOnceADayThroughTheDatePlaceholder() throws Exception {
        Method migrationMethod = method("experienceDevelopmentDataFlyway");
        migrationMethod.setAccessible(true);
        Flyway flyway = (Flyway) migrationMethod.invoke(
                new ModuleFlywayConfiguration(),
                mock(DataSource.class));

        assertThat(flyway.getConfiguration().isPlaceholderReplacement()).isTrue();
        assertThat(flyway.getConfiguration().getPlaceholders())
                .containsEntry("devSeedDate", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());
        try (var stream = getClass().getResourceAsStream(
                "/db/development/experience/R__dev_experience_catalog.sql")) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("${devSeedDate}");
        }
    }

    private static Method method(String name) {
        return Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
