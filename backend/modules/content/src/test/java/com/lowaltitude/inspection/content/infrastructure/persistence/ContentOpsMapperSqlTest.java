package com.lowaltitude.inspection.content.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 运营端“发现内容”（S3-1）的 SQL 文本检查（没有数据库测试容器，按现有做法）：只用绑定参数；改内容和改状态都带版本号；
 * 改之前锁行；删除只删正文块、提到的对象和专题条目（整组替换），不删内容和专题本身；读出的列和行记录一一对应。
 */
class ContentOpsMapperSqlTest {

    @Test
    void everyStatementOnlyUsesBoundParameters() {
        Map<String, String> statements = statements();
        assertThat(statements).hasSize(ContentOpsMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql)
                .doesNotContainPattern("[0-9a-z_)]FROM ")
                .doesNotContainPattern("[0-9a-z_)]WHERE ")
                .doesNotContainPattern("[0-9a-z_)]AND ")
                .doesNotContainPattern("[0-9a-z_)]ORDER BY"));
    }

    @Test
    void writesCarryTheVersionAndLocksComeFirst() {
        for (String method : List.of("updateArticle", "updateArticleStatus", "updateTopic", "updateTopicStatus")) {
            assertThat(normalized(sql(method))).as(method).contains("version = version + 1")
                    .endsWith("AND version = #{version}");
        }
        assertThat(normalized(sql("lockArticle"))).endsWith("WHERE a.id = #{id} FOR UPDATE");
        assertThat(normalized(sql("lockTopic"))).endsWith("WHERE t.id = #{id} FOR UPDATE");
        // 新建一律是草稿，没有发布时间。
        assertThat(normalized(sql("insertArticle"))).contains("'DRAFT', NULL,");
        assertThat(normalized(sql("insertTopic"))).contains("'DRAFT', 0,");
        // 改内容不碰状态和发布时间。
        assertThat(normalized(sql("updateArticle"))).doesNotContain("status").doesNotContain("published_at");
    }

    @Test
    void deletesOnlyReplaceTheChildRows() {
        assertThat(normalized(sql("deleteBlocks"))).isEqualTo("DELETE FROM content_article_block WHERE article_id = #{articleId}");
        assertThat(normalized(sql("deleteRefs"))).isEqualTo("DELETE FROM content_article_ref WHERE article_id = #{articleId}");
        assertThat(normalized(sql("deleteTopicItems"))).isEqualTo("DELETE FROM content_topic_item WHERE topic_id = #{topicId}");
        assertThat(statements().entrySet().stream().filter(entry -> entry.getValue().contains("DELETE")))
                .hasSize(3);
    }

    @Test
    void listsSeparateVideosFromArticlesAndFilterOnlyWhenAsked() {
        String filter = normalized(ContentOpsMapper.ARTICLE_FILTER);
        assertThat(filter).startsWith("WHERE (a.kind = 'VIDEO') = #{video}")
                .contains("AND (CAST(#{kind} AS varchar) IS NULL OR a.kind = #{kind})")
                .contains("AND (CAST(#{status} AS varchar) IS NULL OR a.status = #{status})")
                .contains("a.title ILIKE '%' || #{keyword} || '%'");
        assertThat(normalized(sql("findArticles"))).endsWith("ORDER BY a.updated_at DESC, a.id DESC LIMIT #{limit} OFFSET #{offset}");
        assertThat(normalized(sql("findTopics"))).endsWith("ORDER BY t.updated_at DESC, t.id DESC LIMIT #{limit} OFFSET #{offset}");
        assertThat(normalized(sql("countPlainArticle"))).endsWith("WHERE a.id = #{id} AND a.kind <> 'VIDEO'");
        assertThat(normalized(sql("findChanges"))).endsWith("ORDER BY c.changed_at DESC, c.id DESC LIMIT #{limit}");
    }

    @Test
    void selectedColumnsMatchTheRowRecordsInOrder() {
        assertThat(aliases(ContentOpsMapper.ARTICLE_COLUMNS))
                .containsExactlyElementsOf(fields(ContentOpsMapper.OpsArticleRow.class));
        assertThat(aliases(ContentOpsMapper.TOPIC_COLUMNS))
                .containsExactlyElementsOf(fields(ContentOpsMapper.OpsTopicRow.class));
        assertThat(aliases(sql("findChanges"))).containsExactlyElementsOf(fields(ContentOpsMapper.ChangeRow.class));
    }

    @Test
    void codesMatchTheMigrationChecks() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/content/V004__content_ops.sql")) {
            assertThat(stream).isNotNull();
            migration = normalized(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(migration).contains("block_type IN (" + codes(ContentOpsCodes.OpsBlockType.class) + ")")
                .contains("subject_type IN (" + codes(ContentOpsCodes.OpsContentSubject.class) + ")")
                .contains("action IN (" + codes(ContentOpsCodes.OpsContentAction.class) + ")");
    }

    private static String codes(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : ContentOpsMapper.class.getDeclaredMethods()) {
            String[] sql = null;
            if (method.getAnnotation(Select.class) != null) {
                sql = method.getAnnotation(Select.class).value();
            } else if (method.getAnnotation(Insert.class) != null) {
                sql = method.getAnnotation(Insert.class).value();
            } else if (method.getAnnotation(Update.class) != null) {
                sql = method.getAnnotation(Update.class).value();
            } else if (method.getAnnotation(Delete.class) != null) {
                sql = method.getAnnotation(Delete.class).value();
            }
            if (sql != null) {
                statements.put(method.getName(), String.join("\n", sql));
            }
        }
        return statements;
    }

    private static String sql(String method) {
        String sql = statements().get(method);
        assertThat(sql).as(method).isNotNull();
        return sql;
    }

    private static List<String> aliases(String select) {
        String columns = normalized(select);
        columns = columns.substring(columns.indexOf("SELECT ") + "SELECT ".length(), columns.indexOf(" FROM "));
        return Arrays.stream(columns.split(","))
                .map(String::trim)
                .map(column -> column.substring(column.indexOf('.') + 1))
                .map(ContentOpsMapperSqlTest::camel)
                .toList();
    }

    private static String camel(String snake) {
        StringBuilder camel = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }

    private static List<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }
}
