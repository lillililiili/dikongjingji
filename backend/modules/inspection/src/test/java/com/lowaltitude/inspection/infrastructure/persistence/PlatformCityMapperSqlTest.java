package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class PlatformCityMapperSqlTest {

    @Test
    void selectsActiveCitiesInStableProvinceAndCityOrder() throws Exception {
        Method method = PlatformCityMapper.class.getMethod("findActiveCities");
        String source = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(source)
                .contains("ORDER BY province_initials, province_pinyin")
                .contains("city_initials, city_pinyin, city_code")
                .contains("WHERE status = 'ACTIVE'");
    }

    @Test
    void atomicallyIncrementsOnlyActiveCityPopularity() throws Exception {
        Method method = PlatformCityMapper.class.getMethod("incrementSearchCount", String.class);
        String source = String.join(" ", method.getAnnotation(Update.class).value());

        assertThat(source)
                .contains("SET search_count = search_count + 1")
                .contains("version = version + 1")
                .contains("WHERE city_code = #{cityCode}")
                .contains("AND status = 'ACTIVE'");
    }

    // S3-1 运营端“城市开通”：只碰可选城市；全部绑定参数；按版本号改；记录只追加。
    @Test
    void opsStatementsBindParametersAndChangeOnlyTheCurrentVersion() throws Exception {
        java.util.Map<String, String> statements = new java.util.LinkedHashMap<>();
        for (Method method : PlatformCityMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            Update update = method.getAnnotation(Update.class);
            org.apache.ibatis.annotations.Insert insert = method.getAnnotation(org.apache.ibatis.annotations.Insert.class);
            String[] text = select != null ? select.value() : update != null ? update.value()
                    : insert != null ? insert.value() : null;
            if (text != null) {
                statements.put(method.getName(), String.join(" ", text).replaceAll("\\s+", " "));
            }
        }
        assertThat(statements).hasSize(8);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        assertThat(statements.get("findActiveCities")).contains("search_count, service_status");
        for (String name : java.util.List.of("countOpsCities", "findOpsCities", "findOpsCity")) {
            assertThat(statements.get(name)).as(name).contains("c.status = 'ACTIVE'");
        }
        assertThat(statements.get("updateServiceStatus"))
                .contains("SET service_status = #{serviceStatus}, service_status_changed_at = #{at}, version = version + 1")
                .contains("AND version = #{version}");
        assertThat(statements.get("insertServiceStatusChange")).startsWith("INSERT INTO platform_city_service_status_change");
        assertThat(statements.values()).noneMatch(sql -> sql.contains("DELETE"))
                .noneMatch(sql -> sql.contains("UPDATE platform_city_service_status_change"));
    }

    @Test
    void serviceStatusMigrationKeepsTheRulesAndTheReferenceSeedNeverOverwritesIt() throws Exception {
        String migration;
        String reference;
        try (var stream = getClass().getResourceAsStream("/db/migration/inspection/V012__platform_city_service_status.sql");
             var referenceStream = getClass().getResourceAsStream(
                     "/db/migration/inspection/R__platform_city_reference.sql")) {
            migration = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            reference = new String(referenceStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(migration)
                .contains("ADD COLUMN service_status varchar(16) NOT NULL DEFAULT 'CLOSED'")
                .contains("CHECK (service_status IN ('OPEN', 'PREPARING', 'CLOSED'))")
                .contains("CREATE TABLE platform_city_service_status_change")
                .contains("CHECK (from_status <> to_status)");
        // 可重复的参考数据只写行政区划 status；东营的初始开通只在从没改过时写一次。
        String upsert = reference.substring(reference.indexOf("ON CONFLICT (city_code) DO UPDATE"),
                reference.indexOf("UPDATE platform_city\nSET status = 'INACTIVE'"));
        assertThat(upsert).doesNotContain("service_status");
        assertThat(reference).contains("WHERE city_code = '370500'\n  AND service_status = 'CLOSED'\n"
                + "  AND service_status_changed_at IS NULL;");
    }
}
