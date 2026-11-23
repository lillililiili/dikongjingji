package com.lowaltitude.inspection.experience.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import com.lowaltitude.inspection.experience.application.ExperienceRecordRows.RecordRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/** 体验记录（S5-4）的 SQL 文本和 V004 迁移；真实执行由 dev 环境启动和接口联调覆盖。 */
class ExperienceRecordMapperContractTest {

    @Test
    void everyStatementBindsItsParameters() {
        List<String> statements = Arrays.stream(ExperienceRecordMapper.class.getDeclaredMethods())
                .map(ExperienceRecordMapperContractTest::text)
                .filter(text -> text != null)
                .toList();

        assertThat(statements).hasSize(11);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    // 别人的记录当作不存在：每一条读写记录的语句都以账号为条件，并且只给行状态为 ACTIVE 的。
    @Test
    void everyRecordStatementIsScopedToTheAccount() throws Exception {
        for (String name : List.of("findRecordByKey", "findActiveTrial", "findRecord", "lockRecord",
                "countRecords", "findRecords")) {
            assertThat(sql(name)).as(name).contains("r.account_id = #{accountId}").contains("r.status = 'ACTIVE'");
        }
        assertThat(sql("endTrial"))
                .contains("account_id = #{accountId}")
                .contains("status = 'ACTIVE'")
                .contains("kind = 'TRIAL'")
                .contains("ended_at IS NULL")
                .contains("expires_at > #{at}");
        assertThat(sql("lockRecord")).contains("FOR UPDATE OF r");
        assertThat(sql("insertSimulationRecord")).contains("ON CONFLICT (account_id, idempotency_key) DO NOTHING");
        assertThat(sql("lockTrialStart")).contains("pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))");
        assertThat(sql("findRecords"))
                .contains("ORDER BY r.started_at DESC, r.id DESC")
                .contains("LIMIT #{limit} OFFSET #{offset}");
    }

    /** RecordRow 按列的顺序构造：列数和字段数一致。 */
    @Test
    void recordColumnsMatchTheRow() {
        String columns = ExperienceRecordMapper.RECORD_COLUMNS;
        String selected = columns.substring(columns.indexOf("SELECT") + 6, columns.indexOf("FROM"));
        List<String> names = Arrays.stream(selected.split(","))
                .map(String::trim)
                .map(column -> column.contains(" ") ? column.substring(column.lastIndexOf(' ') + 1) : column)
                .map(column -> column.contains(".") ? column.substring(column.indexOf('.') + 1) : column)
                .map(column -> {
                    StringBuilder camel = new StringBuilder();
                    for (String part : column.split("_")) {
                        camel.append(camel.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1));
                    }
                    return camel.toString();
                })
                .toList();

        assertThat(names).containsExactlyElementsOf(Arrays.stream(RecordRow.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList());
    }

    @Test
    void theV004MigrationAddsTheItemSettingsAndTheRecordTable() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/experience/V004__experience_records.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains("ADD COLUMN simulation_template varchar(24)")
                .contains("ADD COLUMN trial_minutes integer")
                .contains("CREATE TABLE experience_record (")
                .contains("account_id uuid NOT NULL REFERENCES identity_user(id)")
                .contains("UNIQUE (account_id, idempotency_key)")
                .contains("status varchar(16) NOT NULL DEFAULT 'ACTIVE'")
                .contains("version integer NOT NULL DEFAULT 0")
                .contains("created_at timestamptz NOT NULL DEFAULT now()")
                .contains("updated_at timestamptz NOT NULL DEFAULT now()");
        String upper = migration.toUpperCase();
        assertThat(upper).doesNotContain("INSERT INTO").doesNotContain("DROP ");
    }

    private static String sql(String methodName) {
        Method method = Arrays.stream(ExperienceRecordMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return text(method);
    }

    private static String text(Method method) {
        for (Class<? extends Annotation> type : List.of(Select.class, Insert.class, Update.class)) {
            Annotation annotation = method.getAnnotation(type);
            if (annotation instanceof Select select) {
                return String.join("\n", select.value());
            }
            if (annotation instanceof Insert insert) {
                return String.join("\n", insert.value());
            }
            if (annotation instanceof Update update) {
                return String.join("\n", update.value());
            }
        }
        return null;
    }
}
