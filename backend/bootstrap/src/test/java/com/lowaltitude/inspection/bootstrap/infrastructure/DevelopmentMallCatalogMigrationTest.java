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

    // 购物车按原型做以后，示例供给要写明配送方式：本地门店只能到店自提，示例机器人商家的东营仓两种都行。
    @Test
    void seedSetsTheDeliveryModesOfEveryOfferAndKeepsThemOnReruns() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/mall/R__dev_mall_catalog.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String offers = sql.substring(sql.indexOf("INSERT INTO mall_offer ("));
        offers = offers.substring(0, offers.indexOf("updated_at = now();"));

        assertThat(offers.replaceAll("\\s+", " "))
                .contains("delivery_note, shipping_note, fulfilled_from, express_available, pickup_point )")
                .contains("seed.kind <> 'LOCAL_STORE' OR seed.unit <> 'ITEM',")
                .contains("WHEN seed.kind = 'LOCAL_STORE' AND seed.unit = 'ITEM' THEN")
                .contains("WHEN seed.product = '101' AND seed.merchant = '1' THEN")
                .contains("express_available = EXCLUDED.express_available,")
                .contains("pickup_point = EXCLUDED.pickup_point,");
        // 不能走快递的条件和有自提点的条件是同一个，满足 ck_mall_offer_fulfilment（express_available OR pickup_point IS NOT NULL）。
        assertThat(offers.replaceAll("\\s+", " "))
                .contains("THEN '示例科创器材店 东营门店（示例路 18 号）'");
        // 每一行都写了计价单位，上面的两个条件不会因为 NULL 落空。
        assertThat(offers).doesNotContainPattern("[0-9], NULL, '(到店|含|全国|东营|济南|按|型号)");
    }

    private static Method method(String name) {
        return Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
