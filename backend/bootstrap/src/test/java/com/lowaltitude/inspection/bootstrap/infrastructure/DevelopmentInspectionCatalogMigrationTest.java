package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class DevelopmentInspectionCatalogMigrationTest {

    @Test
    void loadsDevelopmentCatalogFromAnIsolatedFlywayHistory() throws Exception {
        Method migrationMethod = Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("inspectionDevelopmentDataFlyway"))
                .findFirst()
                .orElse(null);

        assertThat(migrationMethod).isNotNull();
        assertThat(migrationMethod.getAnnotation(Profile.class).value()).containsExactly("dev");

        migrationMethod.setAccessible(true);
        Flyway flyway = (Flyway) migrationMethod.invoke(
                new ModuleFlywayConfiguration(),
                mock(DataSource.class));

        assertThat(flyway.getConfiguration().getTable())
                .isEqualTo("flyway_history_inspection_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations())
                .map(Object::toString))
                .containsExactly("classpath:db/development/inspection");
    }

    @Test
    void developmentSeedDefinesTheFiveDongyingCatalogCategories() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/inspection/R__dev_inspection_catalog.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("'370500'")
                .contains("'POWER'")
                .contains("'THERMAL'")
                .contains("'OIL_GAS'")
                .contains("'PHOTOVOLTAIC'")
                .contains("'PARK'");
    }

    @Test
    void developmentSeedDefinesSimulatedPlatformCityPopularity() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/inspection/R__dev_platform_city_popularity.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("'370100', 1260::bigint")
                .contains("'370200', 1120::bigint")
                .contains("'370500', 980::bigint");
    }
}
