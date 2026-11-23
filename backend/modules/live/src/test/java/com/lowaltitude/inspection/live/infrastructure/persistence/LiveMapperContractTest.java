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

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本（M20 直播）：只用绑定参数；读出的列和行记录一一对应；
 * 列表只列可见的；状态迁移和点赞、弹幕序号的写法；再检查 V001 迁移的约束。
 */
class LiveMapperContractTest {

    @Test
    void everyStatementBindsItsParametersAndNeverInterpolatesText() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(LiveMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        // 弹幕只追加：不删弹幕，改弹幕只有隐藏。
        assertThat(statements.values()).noneMatch(sql -> sql.contains("DELETE FROM live_comment"));
        assertThat(statements.entrySet().stream().filter(entry -> entry.getValue().contains("UPDATE live_comment"))
                .map(Map.Entry::getKey)).containsExactly("hideComment");
    }

    @Test
    void theColumnsReadAreTheFieldsOfTheRowsInTheSameOrder() {
        assertThat(columns(LiveMapper.ROOM_COLUMNS)).containsExactlyElementsOf(fields(LiveMapper.RoomRow.class));
        assertThat(columns(LiveMapper.COMMENT_COLUMNS)).containsExactlyElementsOf(fields(LiveMapper.CommentRow.class));
        assertThat(columns(sql("findItems"))).containsExactlyElementsOf(fields(LiveMapper.ItemRow.class));
    }

    @Test
    void theListShowsOnlyRoomsVisibleInTheCityAndOrdersLiveFirst() {
        String listed = normalized(sql("findListed"));
        assertThat(listed)
                .contains("(r.city_code IS NULL OR r.city_code = #{cityCode})")
                .contains("(r.status <> 'SCHEDULED' OR r.scheduled_at > #{staleBefore})")
                .contains("(r.status <> 'ENDED' OR r.replay_url IS NOT NULL)")
                .contains("ORDER BY CASE r.status WHEN 'LIVE' THEN 0 WHEN 'SCHEDULED' THEN 1 ELSE 2 END,")
                .endsWith("r.id LIMIT #{limit} OFFSET #{offset}");
        assertThat(normalized(sql("countListed"))).contains(normalized(LiveMapper.LISTED));
        assertThat(normalized(sql("findHostRooms"))).contains("WHERE r.host_org_id = #{hostOrgId}");
        // 1.1.0：工作台按状态筛，两条语句同一个条件。
        for (String method : List.of("countHostRooms", "findHostRooms")) {
            assertThat(normalized(sql(method))).as(method).contains(normalized(LiveMapper.HOSTED))
                    .contains("(CAST(#{status} AS varchar) IS NULL OR r.status = CAST(#{status} AS varchar))");
        }
    }

    @Test
    void writesThatDependOnStateCheckItInTheStatement() {
        assertThat(normalized(sql("updatePreview"))).endsWith("WHERE id = #{roomId} AND status = 'SCHEDULED'");
        assertThat(normalized(sql("addViewer"))).endsWith("WHERE id = #{roomId} AND status = 'LIVE'");
        assertThat(normalized(sql("nextCommentSeq")))
                .isEqualTo("UPDATE live_room SET comment_count = comment_count + 1 WHERE id = #{roomId} RETURNING comment_count");
        assertThat(normalized(sql("claimReminders")))
                .contains("SET notified_at = #{at}").contains("AND notified_at IS NULL").endsWith("RETURNING account_id");
        assertThat(normalized(sql("hideComment"))).endsWith("AND hidden_at IS NULL");
        assertThat(normalized(sql("lockLikeWindow"))).endsWith("FOR UPDATE");
        for (String insert : List.of("insertViewer", "insertReminder", "insertFollow", "insertLikeWindow",
                "insertStreamEvent")) {
            assertThat(normalized(sql(insert))).as(insert).endsWith("ON CONFLICT DO NOTHING");
        }
        assertThat(normalized(sql("lockRoom"))).endsWith("WHERE r.id = #{roomId} FOR UPDATE OF r");
        assertThat(normalized(sql("lockRoomByStream"))).endsWith("WHERE r.stream_id = #{streamId} FOR UPDATE OF r");
        assertThat(normalized(sql("findComments")))
                .contains("c.hidden_at IS NULL").endsWith("ORDER BY c.seq DESC LIMIT #{limit}");
    }

    @Test
    void theMigrationKeepsTheRulesInConstraints() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/live/V001__live.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }

        assertThat(migration)
                .contains("status varchar(16) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'LIVE', 'ENDED'))")
                .contains("cover_url varchar(500) CHECK (cover_url IS NULL OR cover_url LIKE 'https://%')")
                .contains("replay_url varchar(500) CHECK (replay_url IS NULL OR replay_url LIKE 'https://%')")
                .contains("stream_id varchar(80) NOT NULL UNIQUE")
                .contains("UNIQUE (host_org_id, idempotency_key)")
                .contains("CONSTRAINT ck_live_room_ended CHECK ((status = 'ENDED') = (ended_at IS NOT NULL))")
                .contains("CONSTRAINT ck_live_room_interrupted CHECK (status = 'LIVE' OR NOT stream_interrupted)")
                .contains("CONSTRAINT ck_live_room_replay CHECK ((replay_url IS NULL) = (replay_platform_name IS NULL))")
                .contains("item_type varchar(32) NOT NULL CHECK (item_type IN ('PRODUCT', 'PRO_SERVICE', "
                        + "'INSPECTION_SERVICE', 'ACTIVITY', 'VENUE'))")
                .contains("seq integer NOT NULL CHECK (seq BETWEEN 1 AND 10)")
                .contains("UNIQUE (room_id, item_type, ref_id)")
                .contains("PRIMARY KEY (room_id, viewer_key)")
                .contains("PRIMARY KEY (room_id, account_id)")
                .contains("PRIMARY KEY (account_id, host_org_id)")
                .contains("like_count integer NOT NULL DEFAULT 0 CHECK (like_count BETWEEN 0 AND 30)")
                .contains("body varchar(50) NOT NULL")
                .contains("UNIQUE (room_id, seq)")
                .contains("UNIQUE (author_id, idempotency_key)")
                .contains("event_id varchar(80) PRIMARY KEY")
                .doesNotContain("REFERENCES mall_").doesNotContain("REFERENCES inspection_")
                .doesNotContain("REFERENCES activity_").doesNotContain("REFERENCES content_");
    }

    // ---- 小工具 ----

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : LiveMapper.class.getDeclaredMethods()) {
            statements.put(method.getName(), sqlOf(method));
        }
        return statements;
    }

    private static String sql(String name) {
        return Arrays.stream(LiveMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .map(LiveMapperContractTest::sqlOf)
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
        if (method.getAnnotation(Delete.class) != null) {
            return String.join("\n", method.getAnnotation(Delete.class).value());
        }
        throw new AssertionError(method.getName() + " has no statement");
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** 读出的列名（别名优先），转成驼峰。 */
    private static List<String> columns(String sql) {
        String select = normalized(sql);
        String list = select.substring(select.indexOf("SELECT ") + 7, select.indexOf(" FROM "));
        return Arrays.stream(list.split(","))
                .map(String::trim)
                .map(column -> {
                    String[] parts = column.split(" ");
                    String name = parts[parts.length - 1];
                    return name.substring(name.indexOf('.') + 1);
                })
                .map(LiveMapperContractTest::camel)
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
