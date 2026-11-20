package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

/** F2 的示例专业服务和示例服务商只在开发环境加载，并且挂在有服务商资料的服务商名下。 */
class DevelopmentProServiceCatalogMigrationTest {

    private static final String YUNYI = "22222222-2222-2222-2222-222222222222";
    private static final String EAGLE = "f2000000-0000-4000-8000-000000000001";
    private static final String SURVEY = "f2000000-0000-4000-8000-000000000002";
    private static final String ROBOT = "c1000000-0000-4000-8000-000000000001";

    @Test
    void loadsSampleServicesOnlyInDevAfterTheProvidersExist() throws Exception {
        Method migrationMethod = method("proserviceDevelopmentDataFlyway");

        assertThat(migrationMethod.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactlyInAnyOrder(
                "proserviceFlyway", "inspectionDevelopmentDataFlyway", "mallDevelopmentDataFlyway");
        migrationMethod.setAccessible(true);
        Flyway flyway = (Flyway) migrationMethod.invoke(new ModuleFlywayConfiguration(), mock(DataSource.class));

        assertThat(flyway.getConfiguration().getTable()).isEqualTo("flyway_history_proservice_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/proservice");
    }

    @Test
    void proServiceTablesMigrateAfterInspectionInEveryEnvironment() {
        Method migrationMethod = method("proserviceFlyway");

        assertThat(migrationMethod.getAnnotation(Profile.class)).isNull();
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactly("inspectionFlyway");
    }

    // 16 项服务、四类各 4 项；全部是示例，没有照片；东营全部上架，8 项也上架济南；两种价格都有。
    @Test
    void seedHasTheSixteenBoardServicesAsSamplesWithoutPhotos() throws Exception {
        String sql = read("/db/development/proservice/R__dev_proservice_catalog.sql");
        String services = sql.substring(sql.indexOf("INSERT INTO pro_service ("), sql.indexOf("INSERT INTO pro_service_city"));

        assertThat(count(services, "'f2100000-0000-4000-8000-0000000000\\d\\d'")).isEqualTo(16);
        for (String type : new String[] {"AERIAL", "SURVEY", "MAINTENANCE", "TECH_SUPPORT"}) {
            assertThat(count(services, "'" + type + "'")).as(type).isEqualTo(4);
        }
        assertThat(count(services, "NULL, true, \\d+, 'PUBLISHED'\\)")).isEqualTo(16);
        assertThat(count(services, "'PROJECT'")).isEqualTo(6);
        assertThat(count(services, "'PRICE_FROM'")).isEqualTo(10);
        assertThat(services).contains("'园区与工程航拍'").contains("128000, '/次起', '含拍摄和素材整理'")
                .contains("'光伏场地地形测绘'").contains("380000, '/平方公里起'");
        String cities = sql.substring(sql.indexOf("INSERT INTO pro_service_city"), sql.indexOf("INSERT INTO pro_service_item"));
        assertThat(count(cities, "'370500'\\)")).isEqualTo(16);
        assertThat(count(cities, "'370100'\\)")).isEqualTo(8);
        // 每项服务都有卡片标签、筛选项、能力标签、三行价格与取消、服务内容、流程和保障。
        String items = sql.substring(sql.indexOf("INSERT INTO pro_service_item"));
        for (String kind : new String[] {"'TAG'", "'FACET'", "'CAPABILITY'", "'SCOPE'", "'STEP'", "'GUARANTEE'"}) {
            assertThat(count(items, kind)).as(kind).isGreaterThanOrEqualTo(16);
        }
        assertThat(count(items, "'PRICE_ROW'")).isEqualTo(48);
        assertThat(count(items, "'取消说明', '方案确认前取消不收费，确认后按方案约定'")).isEqualTo(16);
        // 只删自己的 16 项再插入，可以重复执行。
        assertThat(sql).contains("DELETE FROM pro_service\nWHERE id IN (").doesNotContain("TRUNCATE");
        // 四家服务商：云翼科技（dev-provider）和三家示例服务商。
        assertThat(services).contains(YUNYI).contains(EAGLE).contains(SURVEY).contains(ROBOT);
    }

    // 示例服务商的机构和服务商资料：有了在用的资料，服务商主页才能打开，需求的平台匹配和邀请才能找到它们。
    @Test
    void sampleProvidersGetActiveInspectionProfiles() throws Exception {
        String sql = read("/db/development/inspection/R__dev_inspection_provider_profiles.sql");

        assertThat(sql)
                .contains("('" + EAGLE + "', 'DEV-PROVIDER-EAGLE', '鹰眼智能巡检', 'PROVIDER', 'ACTIVE')")
                .contains("('" + SURVEY + "', 'DEV-PROVIDER-SURVEY', '示例测绘服务商', 'PROVIDER', 'ACTIVE')")
                .contains("('" + ROBOT + "', 'DEV-MALL-ROBOT-MAKER', '示例机器人商家', 'PROVIDER', 'ACTIVE')")
                .contains("INSERT INTO inspection_provider_profile")
                .contains("ON CONFLICT (provider_id) DO UPDATE");
        assertThat(count(sql, "'企业认证', '[^']+', 'ACTIVE'\\)")).isEqualTo(3);
        // 机构编码和商城开发数据里的一致，谁先执行都一样。
        assertThat(read("/db/development/mall/R__dev_mall_catalog.sql"))
                .contains("('" + ROBOT + "', 'DEV-MALL-ROBOT-MAKER', '示例机器人商家', 'PROVIDER')");
    }

    private static int count(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    private String read(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Method method(String name) {
        return Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
