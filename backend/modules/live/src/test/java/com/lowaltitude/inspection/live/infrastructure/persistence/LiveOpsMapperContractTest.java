package com.lowaltitude.inspection.live.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 运营端“直播间”的 SQL 文本（没有数据库测试容器，照 LiveMapperContractTest 的做法）：只用绑定参数；读出的列和行记录
 * 一一对应；强制结束、回看都按版本号和状态写；改动记录只追加；不读推流、播放地址。再检查 V002 迁移。
 */
class LiveOpsMapperContractTest {

    @Test
    void everyStatementBindsItsParametersAndNeverReadsStreamAddresses() {
        Map<String, String> statements = statements();
        assertThat(statements).hasSize(LiveOpsMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${")
                .doesNotContain("push_url").doesNotContainPattern("(?<![a-z_])play_url"));
        assertThat(statements.values()).noneMatch(sql -> sql.contains("DELETE"))
                .noneMatch(sql -> sql.contains("UPDATE live_ops_change"));
    }

    @Test
    void theColumnsReadAreTheFieldsOfTheRowsInTheSameOrder() {
        assertThat(columns(LiveOpsMapper.COLUMNS)).containsExactlyElementsOf(fields(LiveOpsMapper.OpsRoomRow.class));
        assertThat(columns(sql("findChanges"))).containsExactlyElementsOf(fields(LiveOpsMapper.ChangeRow.class));
        assertThat(columns(sql("countByHost"))).containsExactlyElementsOf(fields(LiveOpsMapper.HostCountRow.class));
    }

    @Test
    void writesCheckTheVersionAndTheState() {
        assertThat(normalized(sql("endRoom")))
                .contains("SET status = 'ENDED', ended_at = #{at}, end_reason = 'PLATFORM', stream_interrupted = false,"
                        + " version = version + 1")
                .endsWith("WHERE id = #{roomId} AND version = #{version} AND status <> 'ENDED'");
        assertThat(normalized(sql("updateReplay")))
                .endsWith("WHERE id = #{roomId} AND version = #{version} AND status = 'ENDED'");
        assertThat(normalized(sql("lockRoom"))).endsWith("FOR UPDATE OF r");
        assertThat(normalized(sql("countRooms"))).contains(normalized(LiveOpsMapper.FILTER));
        assertThat(normalized(sql("findRooms"))).contains(normalized(LiveOpsMapper.FILTER))
                .contains("ORDER BY CASE r.status WHEN 'LIVE' THEN 0 WHEN 'SCHEDULED' THEN 1 ELSE 2 END,");
    }

    @Test
    void theMigrationKeepsAnAppendOnlyRecord() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/live/V002__live_ops.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
        assertThat(migration).contains("CREATE TABLE live_ops_change")
                .contains("room_id uuid NOT NULL REFERENCES live_room(id) ON DELETE RESTRICT")
                .contains("CHECK (action IN ('FORCE_END', 'SET_REPLAY', 'CLEAR_REPLAY'))")
                .contains("operator_id uuid NOT NULL REFERENCES identity_user(id)")
                .doesNotContain("ALTER TABLE live_room");
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : LiveOpsMapper.class.getDeclaredMethods()) {
            statements.put(method.getName(), sqlOf(method));
        }
        return statements;
    }

    private static String sql(String name) {
        return Arrays.stream(LiveOpsMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .map(LiveOpsMapperContractTest::sqlOf)
                .orElseThrow(() -> new AssertionError(name + " is not a mapper method"));
    }

    private static String sqlOf(Method method) {
        if (method.getAnnotation(Select.class) != null) {
            return String.join("\n", method.getAnnotation(Select.class).value());
        }
        if (method.getAnnotation(Insert.class) != null) {
            return String.join("\n", method.getAnnotation(Insert.class).value());
        }
        if (method.getAnnotation(Update.class) != null) {
            return String.join("\n", method.getAnnotation(Update.class).value());
        }
        throw new AssertionError(method.getName() + " has no statement");
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** 读出的列名（别名优先），转成驼峰。count(*) FILTER (...) AS x 只取别名。 */
    private static List<String> columns(String sql) {
        String select = normalized(sql).replaceAll("count\\(\\*\\) FILTER \\([^)]*\\)", "count");
        String list = select.substring(select.indexOf("SELECT ") + 7, select.indexOf(" FROM "));
        return Arrays.stream(list.split(","))
                .map(String::trim)
                .map(column -> {
                    String[] parts = column.split(" ");
                    String name = parts[parts.length - 1];
                    return name.substring(name.indexOf('.') + 1);
                })
                .map(LiveOpsMapperContractTest::camel)
                .toList();
    }

    private static String camel(String snake) {
        Matcher matcher = Pattern.compile("_([a-z])").matcher(snake);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, matcher.group(1).toUpperCase());
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static List<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
