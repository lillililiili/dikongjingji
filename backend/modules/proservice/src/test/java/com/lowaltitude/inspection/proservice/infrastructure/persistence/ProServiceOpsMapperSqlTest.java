package com.lowaltitude.inspection.proservice.infrastructure.persistence;

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

/** 运营端“专业服务目录”的 SQL（S3-1）：全部绑定参数、按版本号改、新建先未上架、城市只停用、记录只追加。 */
class ProServiceOpsMapperSqlTest {

    @Test
    void statementsBindParametersAndGuardVersions() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : ProServiceOpsMapper.class.getDeclaredMethods()) {
            String[] text = method.getAnnotation(Select.class) != null ? method.getAnnotation(Select.class).value()
                    : method.getAnnotation(Update.class) != null ? method.getAnnotation(Update.class).value()
                    : method.getAnnotation(Insert.class) != null ? method.getAnnotation(Insert.class).value()
                    : method.getAnnotation(Delete.class) != null ? method.getAnnotation(Delete.class).value() : null;
            if (text != null) {
                statements.put(method.getName(), String.join(" ", text).replaceAll("\\s+", " "));
            }
        }
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        for (String name : new String[] {"updateService", "updateStatus"}) {
            assertThat(statements.get(name)).as(name).contains("version = version + 1")
                    .contains("AND version = #{version}");
        }
        assertThat(statements.get("insertService")).contains("'UNPUBLISHED'").contains("false");
        assertThat(statements.entrySet()).filteredOn(entry -> entry.getValue().startsWith("DELETE"))
                .extracting(Map.Entry::getKey).containsExactly("deleteItems");
        assertThat(statements.get("disableOtherCities")).contains("SET status = 'DISABLED'");
        assertThat(statements.get("insertChange")).startsWith("INSERT INTO pro_service_change");
    }

    @Test
    void migrationAddsAnAppendOnlyRecord() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/proservice/V002__pro_service_ops.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("CREATE TABLE pro_service_change").contains("REFERENCES identity_user(id)")
                .doesNotContain("INSERT INTO").doesNotContain("DROP ");
    }
}
