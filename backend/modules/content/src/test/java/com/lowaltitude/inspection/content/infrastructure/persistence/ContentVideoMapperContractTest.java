package com.lowaltitude.inspection.content.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 视频（1.2.0）的 SQL 文本：只用绑定参数；可见规则和图文一样（视频都有城市）；视频流的顺序和翻页位置；写入只碰本模块的表；
 * 读出的列和行记录一一对应。没有可用的数据库测试容器，真实执行由一次性库上的迁移和开发种子覆盖。
 */
class ContentVideoMapperContractTest {

    private static final String VISIBLE_VIDEO = "a.kind = 'VIDEO' AND a.status = 'PUBLISHED'"
            + " AND a.published_at <= #{now} AND a.city_code = #{cityCode}";
    private static final String ORDER = "ORDER BY a.video_rank DESC, a.published_at DESC, a.id DESC";

    @Test
    void everyStatementIsBoundAndOnlyWritesThisModulesTables() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(ContentVideoMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        assertThat(statements.values()).allSatisfy(sql -> {
            String text = normalized(sql);
            for (String verb : List.of("INSERT INTO ", "UPDATE ", "DELETE FROM ")) {
                int at = text.indexOf(verb);
                if (at >= 0) {
                    assertThat(text.substring(at + verb.length())).startsWith("content_");
                }
            }
        });
        // 可能为空的参数都声明了类型。
        assertThat(normalized(sql("findStream")))
                .contains("CAST(#{followerId} AS uuid) IS NULL")
                .contains("CAST(#{afterId} AS uuid) IS NULL")
                .contains("CAST(#{afterRank} AS integer)")
                .contains("CAST(#{afterPublishedAt} AS timestamptz)")
                .contains("CAST(#{inclusive} AS boolean)");
        assertThat(normalized(ContentVideoMapper.VIDEO_COLUMNS)).contains("l.account_id = CAST(#{viewerId} AS uuid)")
                .contains("f.account_id = CAST(#{viewerId} AS uuid)");
    }

    @Test
    void videosAreReadByTheSameVisibilityAsArticlesAndInStreamOrder() {
        assertThat(normalized(ContentVideoMapper.VISIBLE_VIDEO)).isEqualTo(VISIBLE_VIDEO);
        assertThat(normalized(sql("findVideo"))).endsWith("WHERE a.id = #{videoId} AND " + VISIBLE_VIDEO);
        assertThat(normalized(sql("findStream")))
                .contains("WHERE " + VISIBLE_VIDEO)
                .contains("(a.video_rank, a.published_at, a.id) < (CAST(#{afterRank} AS integer),"
                        + " CAST(#{afterPublishedAt} AS timestamptz), CAST(#{afterId} AS uuid))")
                .contains("OR (CAST(#{inclusive} AS boolean) AND a.id = CAST(#{afterId} AS uuid))")
                .endsWith(ORDER + " LIMIT #{limit}");
        // 写操作和评论列表看的是“已发布的视频”，不看城市。
        assertThat(normalized(sql("findPublishedVideo"))).contains("a.kind = 'VIDEO' AND a.status = 'PUBLISHED'"
                + " AND a.published_at <= #{now}").doesNotContain("city_code");
        // 评论数只数没隐藏的；评论列表只给没隐藏的，新的在前。
        assertThat(normalized(ContentVideoMapper.VIDEO_COLUMNS)).contains("c.status = 'VISIBLE') AS comment_count");
        assertThat(normalized(sql("countVisibleComments"))).endsWith("AND c.status = 'VISIBLE'");
        assertThat(normalized(sql("findVisibleComments")))
                .endsWith("AND c.status = 'VISIBLE' ORDER BY c.created_at DESC, c.id DESC LIMIT #{limit} OFFSET #{offset}");
        assertThat(normalized(sql("hideComment"))).contains("WHERE id = #{commentId} AND status = 'VISIBLE'");
    }

    @Test
    void writesAreIdempotentByTheirKeys() {
        assertThat(normalized(sql("insertLike"))).endsWith("ON CONFLICT DO NOTHING");
        assertThat(normalized(sql("insertFollow"))).endsWith("ON CONFLICT DO NOTHING");
        assertThat(normalized(sql("insertReport"))).contains("ON CONFLICT DO NOTHING RETURNING");
        assertThat(normalized(sql("lockCommenter")))
                .contains("pg_advisory_xact_lock(hashtextextended('content-commenter:' || CAST(#{accountId} AS text), 0))");
        assertThat(normalized(sql("findCommentByKey")))
                .endsWith("WHERE c.account_id = #{accountId} AND c.idempotency_key = #{idempotencyKey}");
        assertThat(normalized(sql("countCommentsSince")))
                .endsWith("WHERE c.account_id = #{accountId} AND c.created_at > #{since}");
    }

    // 列别名按下划线转驼峰后正好是行记录的字段，顺序也一致（MyBatis 按构造参数映射）。
    @Test
    void selectedColumnsMatchTheRowRecordsInOrder() {
        assertThat(columns(ContentVideoMapper.VIDEO_COLUMNS)).containsExactlyElementsOf(fields(ContentVideoMapper.VideoRow.class));
        assertThat(columns(ContentVideoMapper.COMMENT_COLUMNS))
                .containsExactlyElementsOf(fields(ContentVideoMapper.CommentRow.class));
        assertThat(columns(ContentVideoMapper.REPORT_COLUMNS))
                .containsExactlyElementsOf(fields(ContentVideoMapper.ReportRow.class));
        assertThat(columns(sql("findPublishedVideo")))
                .containsExactlyElementsOf(fields(ContentVideoMapper.VideoTargetRow.class));
        assertThat(columns(sql("findCommentTarget")))
                .containsExactlyElementsOf(fields(ContentVideoMapper.CommentTargetRow.class));
        assertThat(returning(sql("insertComment"))).containsExactlyElementsOf(fields(ContentVideoMapper.CommentRow.class));
        assertThat(returning(sql("insertReport"))).containsExactlyElementsOf(fields(ContentVideoMapper.ReportRow.class));
    }

    @Test
    void assembledQueriesKeepTheirKeywordsApart() {
        assertThat(statements().values()).allSatisfy(sql -> assertThat(sql)
                .doesNotContainPattern("[0-9a-z_)}]FROM ")
                .doesNotContainPattern("[0-9a-z_)}]WHERE ")
                .doesNotContainPattern("[0-9a-z_)}]AND ")
                .doesNotContainPattern("[0-9a-z_)}]ORDER BY"));
    }

    /** SELECT 和第一个顶层 FROM 之间的列，按顶层逗号分开，取别名（AS 后面）或列名。 */
    private static List<String> columns(String select) {
        String text = normalized(select);
        int start = text.indexOf("SELECT ") + "SELECT ".length();
        int depth = 0;
        int end = -1;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && text.startsWith(" FROM ", i)) {
                end = i;
                break;
            }
        }
        return names(text.substring(start, end));
    }

    private static List<String> returning(String sql) {
        String text = normalized(sql);
        return names(text.substring(text.indexOf("RETURNING ") + "RETURNING ".length()));
    }

    private static List<String> names(String list) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : list.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().trim());
        return parts.stream()
                .map(column -> column.contains(" AS ")
                        ? column.substring(column.lastIndexOf(" AS ") + 4)
                        : column.substring(column.indexOf('.') + 1))
                .map(ContentVideoMapperContractTest::camel)
                .toList();
    }

    private static String camel(String snake) {
        StringBuilder camel = new StringBuilder();
        boolean upper = false;
        for (char c : snake.trim().toCharArray()) {
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

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : ContentVideoMapper.class.getDeclaredMethods()) {
            for (Annotation annotation : method.getAnnotations()) {
                String[] value = switch (annotation) {
                    case Select select -> select.value();
                    case Insert insert -> insert.value();
                    case Update update -> update.value();
                    case Delete delete -> delete.value();
                    default -> null;
                };
                if (value != null) {
                    statements.put(method.getName(), String.join("\n", value));
                }
            }
        }
        return statements;
    }

    private static String sql(String methodName) {
        String sql = statements().get(methodName);
        assertThat(sql).as(methodName).isNotNull();
        return sql;
    }

    /** 去掉注释、把连续空白并成一个空格。 */
    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .replace("( ", "(")
                .replace(" )", ")")
                .trim();
    }
}
