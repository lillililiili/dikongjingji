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

    private static Method method(String name) {
        return Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
