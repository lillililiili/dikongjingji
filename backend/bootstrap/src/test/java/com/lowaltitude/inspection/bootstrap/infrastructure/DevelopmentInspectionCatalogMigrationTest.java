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
    void developmentSeedDefinesCatalogCoverageForDongyingAndJinan() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/inspection/R__dev_inspection_catalog.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("inspection_service_city")
                .contains("'370500'")
                .contains("'370100'")
                .contains("'POWER'")
                .contains("'THERMAL'")
                .contains("'OIL_GAS'")
                .contains("'PHOTOVOLTAIC'")
                .contains("'PARK'")
                .contains("'INS-SVC-DEV-SUBSTATION'")
                .contains("'INS-SVC-DEV-DISTRIBUTION'")
                .contains("'INS-SVC-DEV-CABLE'")
                .contains("'SUBSTATION'")
                .contains("'DISTRIBUTION'")
                .contains("'CABLE'")
                .contains("8560")
                .contains("6230")
                .contains("5420")
                .contains("7310")
                .contains("3210");
    }

    @Test
    void developmentSeedDefinesCompletePowerServiceDetail() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/inspection/R__dev_inspection_detail.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("'33333333-3333-3333-3333-333333333333'")
                .contains("inspection_provider_profile")
                .contains("'CAPABILITY'")
                .contains("'PROCESS'")
                .contains("'DELIVERABLE'")
                .contains("'GUARANTEE'")
                .contains("inspection_service_review")
                .contains("inspection_service_review_tag")
                .contains("inspection_service_review_image")
                .contains("WHEN series_no <= 322 THEN 4")
                .contains("ELSE 3");
    }

    @Test
    void developmentSeedDefinesPowerPricingAndCancellationButNoProjectQuotePriceNote() throws Exception {
        String detailSql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/inspection/R__dev_inspection_detail.sql")) {
            assertThat(stream).isNotNull();
            detailSql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String catalogSql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/inspection/R__dev_inspection_catalog.sql")) {
            assertThat(stream).isNotNull();
            catalogSql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(detailSql)
                .contains("price_note = '按线路公里计价，起步价含现场巡检和报告'")
                .contains("cancellation_note = '方案确认前可取消，不收费；确认后按方案约定'")
                .contains("'PRICE_FACTOR'")
                .contains("'LINE_LENGTH'")
                .contains("'TOWER_COUNT'")
                .contains("'SITE_CONDITION'");
        // 按项目报价的园区服务不配置 price_note，列表卡由服务端派生“方案确认后定价”。
        assertThat(catalogSql).doesNotContain("price_note");
        assertThat(detailSql).doesNotContain("'77777777-7777-7777-7777-777777777777'");
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
