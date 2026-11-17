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

class DevelopmentMallCatalogMigrationTest {

    @Test
    void loadsSampleProductsOnlyInDevFromAnIsolatedFlywayHistory() throws Exception {
        Method migrationMethod = method("mallDevelopmentDataFlyway");

        assertThat(migrationMethod.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactly("mallFlyway");

        migrationMethod.setAccessible(true);
        Flyway flyway = (Flyway) migrationMethod.invoke(
                new ModuleFlywayConfiguration(),
                mock(DataSource.class));

        assertThat(flyway.getConfiguration().getTable())
                .isEqualTo("flyway_history_mall_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/mall");
    }

    @Test
    void mallTablesMigrateAfterTheModulesTheyReference() {
        Method migrationMethod = method("mallFlyway");

        assertThat(migrationMethod.getAnnotation(Profile.class)).isNull();
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactly("inspectionFlyway");
    }

    @Test
    void seedIsRepeatableAndCoversSaleModesUnitsAndCityCases() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/mall/R__dev_mall_catalog.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("'BUY'")
                .contains("'RENT'")
                .contains("'INQUIRY'")
                .contains("'DAY'")
                .contains("'HALF_DAY'")
                .contains("'FACTORY_DIRECT'")
                .contains("'AUTHORIZED_DEALER'")
                .contains("'LOCAL_STORE'")
                .contains("'OFFLINE'")
                .contains("'370500'")
                .contains("'370100'");
        assertThat(sql.split("ON CONFLICT \\(id\\) DO UPDATE", -1)).hasSize(7);
        assertThat(sql).doesNotContain("DELETE FROM").doesNotContain("TRUNCATE");
    }

    private static Method method(String name) {
        return Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
