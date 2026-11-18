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

class DevelopmentActivityCatalogMigrationTest {

    @Test
    void loadsSampleActivitiesOnlyInDevFromAnIsolatedFlywayHistory() throws Exception {
        Method migrationMethod = method("activityDevelopmentDataFlyway");

        assertThat(migrationMethod.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactly("activityFlyway");

        migrationMethod.setAccessible(true);
        Flyway flyway = (Flyway) migrationMethod.invoke(
                new ModuleFlywayConfiguration(),
                mock(DataSource.class));

        assertThat(flyway.getConfiguration().getTable())
                .isEqualTo("flyway_history_activity_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/activity");
    }

    @Test
    void activityTablesMigrateAfterTheModulesTheyReference() {
        Method migrationMethod = method("activityFlyway");

        assertThat(migrationMethod.getAnnotation(Profile.class)).isNull();
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactly("inspectionFlyway");
    }

    @Test
    void seedIsRepeatableAndCoversEveryTypeAndPriceMode() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/activity/R__dev_activity_catalog.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("'CONTEST'")
                .contains("'TRAINING'")
                .contains("'STUDY_TOUR'")
                .contains("'OPEN_DAY'")
                .contains("'PER_PERSON'")
                .contains("'FREE'")
                .contains("'INQUIRY'")
                .contains("'OFFLINE'")
                .contains("'370500'");
        assertThat(sql.split("ON CONFLICT \\(id\\) DO UPDATE", -1)).hasSize(4);
        assertThat(sql).doesNotContain("DELETE FROM").doesNotContain("TRUNCATE");
    }

    // 场次相对执行当天排；种子里的日期占位符让它每天自动重跑，不用再手改文件。
    @Test
    void seedReRunsOnceADayThroughTheDatePlaceholder() throws Exception {
        Method migrationMethod = method("activityDevelopmentDataFlyway");
        migrationMethod.setAccessible(true);
        Flyway flyway = (Flyway) migrationMethod.invoke(
                new ModuleFlywayConfiguration(),
                mock(DataSource.class));

        assertThat(flyway.getConfiguration().isPlaceholderReplacement()).isTrue();
        assertThat(flyway.getConfiguration().getPlaceholders())
                .containsEntry("devSeedDate", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());
        try (var stream = getClass().getResourceAsStream(
                "/db/development/activity/R__dev_activity_catalog.sql")) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("${devSeedDate}");
        }
    }

    // 每天重排场次时，“已报人数”是示例人数加上本机真实报名、还没取消的凭证数，不超过容量；
    // 否则重排一次，真实报名占的名额就被示例数字盖掉了。
    @Test
    void reseedingKeepsTheSeatsTakenByRealEnrollments() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/activity/R__dev_activity_catalog.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String sessions = sql.substring(sql.indexOf("INSERT INTO activity_session ("));
        String onConflict = sessions.substring(sessions.indexOf("ON CONFLICT (id) DO UPDATE"))
                .replaceAll("--[^\\n]*", "")
                .replaceAll("\\s+", " ");

        assertThat(onConflict)
                .contains("capacity = EXCLUDED.capacity,")
                .contains("enrolled_count = LEAST( EXCLUDED.capacity, EXCLUDED.enrolled_count + ("
                        + " SELECT count(*)::integer FROM activity_enrollment_ticket ticket"
                        + " WHERE ticket.session_id = activity_session.id"
                        + " AND ticket.status <> 'CANCELLED' )),")
                // 用的是种子里的示例人数，不是库里现有的数：重跑多少次结果都一样。
                .doesNotContain("activity_session.enrolled_count")
                .doesNotContain("enrolled_count = EXCLUDED.enrolled_count,");
        // 凭证表由活动模块的 V002 创建，开发种子排在活动迁移之后，所以这里查得到它。
        assertThat(method("activityDevelopmentDataFlyway").getAnnotation(DependsOn.class).value())
                .containsExactly("activityFlyway");
        // 种子本身不造报名单和凭证。
        assertThat(sql)
                .doesNotContain("INSERT INTO activity_enrollment")
                .doesNotContain("INSERT INTO activity_participant");
    }

    private static Method method(String name) {
        return Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
