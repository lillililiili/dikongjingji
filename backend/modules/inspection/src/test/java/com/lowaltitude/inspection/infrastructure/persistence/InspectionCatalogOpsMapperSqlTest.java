package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/** 运营端“巡检服务目录”的 SQL（S3-1）：全部绑定参数、按版本号改、被引用的行只停用不删、记录只追加。 */
class InspectionCatalogOpsMapperSqlTest {

    @Test
    void statementsBindParametersAndGuardVersions() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : InspectionCatalogOpsMapper.class.getDeclaredMethods()) {
            String[] text = method.getAnnotation(Select.class) != null ? method.getAnnotation(Select.class).value()
                    : method.getAnnotation(Update.class) != null ? method.getAnnotation(Update.class).value()
                    : method.getAnnotation(Insert.class) != null ? method.getAnnotation(Insert.class).value()
                    : method.getAnnotation(Delete.class) != null ? method.getAnnotation(Delete.class).value() : null;
            if (text != null) {
                statements.put(method.getName(), String.join(" ", text).replaceAll("\\s+", " "));
            }
        }
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        for (String name : new String[] {"updateCategory", "updateCategoryStatus", "updateService",
                "updateServiceStatus"}) {
            assertThat(statements.get(name)).as(name).contains("version = version + 1").contains("AND version = #{version}");
        }
        // 覆盖城市、服务项目、详情项被预约和方案按编码引用：只停用，不删。巡检方式没被引用，可以整组替换。
        assertThat(statements.entrySet()).filteredOn(entry -> entry.getValue().contains("DELETE"))
                .extracting(Map.Entry::getKey).containsExactly("deleteMethods");
        assertThat(statements.get("disableOtherCities")).contains("SET status = 'DISABLED'");
        assertThat(statements.get("upsertBookingItem")).contains("ON CONFLICT (service_id, item_code) DO UPDATE");
        assertThat(statements.get("upsertDetailItem"))
                .contains("ON CONFLICT (service_id, section_type, item_code) DO UPDATE");
        assertThat(statements.get("insertService")).contains("'DISABLED'");
        assertThat(statements.get("insertChange")).startsWith("INSERT INTO inspection_catalog_change");
        assertThat(statements.get("findServices")).contains("service.status IN ('ACTIVE', 'DISABLED')");
        assertThat(statements.get("findDirectoryCities")).contains("WHERE status = 'ACTIVE'");
    }

    @Test
    void migrationAddsTheVersionAndAnAppendOnlyRecord() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/inspection/V014__inspection_catalog_ops.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("ADD COLUMN version integer NOT NULL DEFAULT 0")
                .contains("CREATE TABLE inspection_catalog_change")
                .contains("REFERENCES identity_user(id)")
                .doesNotContain("INSERT INTO")
                .doesNotContain("DROP ");
    }
}
