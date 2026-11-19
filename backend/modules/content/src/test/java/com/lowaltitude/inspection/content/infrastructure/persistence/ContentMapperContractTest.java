package com.lowaltitude.inspection.content.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本（H1 发现页内容）：只读、只用绑定参数；内容按城市可见性过滤；
 * 专题按城市、日期过滤；读出的列和行记录一一对应。再检查 V001 迁移脚本的约束。
 * 真实执行由一次性数据库上的迁移和开发种子检查覆盖。
 */
class ContentMapperContractTest {

    private static final String VISIBLE = "a.status = 'PUBLISHED' AND a.published_at <= #{now}"
            + " AND (a.city_code IS NULL OR a.city_code = #{cityCode})";

    @Test
    void everyStatementIsABoundSelect() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(ContentMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(9);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(normalized(sql))
                .startsWith("SELECT")
                .doesNotContain("${")
                .doesNotContain("INSERT")
                .doesNotContain("UPDATE")
                .doesNotContain("DELETE"));
    }

    @Test
    void articlesAreOnlyReadWhenPublishedAlreadyAndShownInTheCity() {
        assertThat(normalized(ContentMapper.VISIBLE)).isEqualTo(VISIBLE);
        for (String method : List.of("countFeed", "findFeedPage", "findArticle")) {
            assertThat(normalized(sql(method))).as(method).contains(VISIBLE);
        }
        assertThat(normalized(sql("findFeedPage")))
                .contains("AND (CAST(#{kind} AS varchar) IS NULL OR a.kind = #{kind})")
                .endsWith("ORDER BY a.published_at DESC, a.id DESC LIMIT #{limit} OFFSET #{offset}");
        assertThat(normalized(sql("findArticle"))).contains("WHERE a.id = #{articleId} AND " + VISIBLE);
    }

    @Test
    void blocksItemsAndRefsKeepTheirOrder() {
        assertThat(normalized(sql("findBlocks")))
                .contains("WHERE b.article_id = #{articleId}")
                .endsWith("ORDER BY b.sort_order ASC");
        assertThat(normalized(sql("findBlockItems")))
                .contains("JOIN content_article_block b ON b.id = i.block_id")
                .endsWith("ORDER BY b.sort_order ASC, i.sort_order ASC");
        assertThat(normalized(sql("findRefs"))).endsWith("WHERE r.article_id = #{articleId} ORDER BY r.sort_order ASC");
        assertThat(normalized(sql("findTopicItems"))).endsWith("WHERE i.topic_id = #{topicId} ORDER BY i.sort_order ASC");
    }

    @Test
    void topicsAreReadByCityAndTheFeedOnlyTakesCurrentOnes() {
        assertThat(normalized(sql("findCurrentTopics")))
                .contains("WHERE t.status = 'PUBLISHED' AND t.city_code = #{cityCode}"
                        + " AND t.starts_on <= #{today} AND t.ends_on >= #{today}")
                .endsWith("ORDER BY t.starts_on DESC, t.id ASC");
        assertThat(normalized(sql("findTopic")))
                .endsWith("WHERE t.id = #{topicId} AND t.status = 'PUBLISHED' AND t.city_code = #{cityCode}")
                .doesNotContain("starts_on <=");
    }

    // 文本块拼接时两段之间漏掉空白，会把关键字粘在一起（巡检目录出过 "1FROM"）。
    @Test
    void assembledQueriesKeepTheirKeywordsApart() {
        assertThat(statements().values()).allSatisfy(sql -> assertThat(sql)
                .doesNotContainPattern("[0-9a-z_)]FROM ")
                .doesNotContainPattern("[0-9a-z_)]WHERE ")
                .doesNotContainPattern("[0-9a-z_)]AND ")
                .doesNotContainPattern("[0-9a-z_)]ORDER BY"));
    }

    // 列别名按下划线转驼峰后正好是行记录的字段，顺序也一致（MyBatis 按构造参数映射）。
    @Test
    void selectedColumnsMatchTheRowRecordsInOrder() {
        assertThat(aliases(ContentMapper.ARTICLE_COLUMNS)).containsExactlyElementsOf(fields(ContentMapper.ArticleRow.class));
        assertThat(aliases(ContentMapper.TOPIC_COLUMNS)).containsExactlyElementsOf(fields(ContentMapper.TopicRow.class));
        assertThat(aliases(sql("findBlocks"))).containsExactlyElementsOf(fields(ContentMapper.BlockRow.class));
        assertThat(aliases(sql("findBlockItems"))).containsExactlyElementsOf(fields(ContentMapper.BlockItemRow.class));
        assertThat(aliases(sql("findRefs"))).containsExactlyElementsOf(fields(ContentMapper.RefRow.class));
        assertThat(aliases(sql("findTopicItems"))).containsExactlyElementsOf(fields(ContentMapper.TopicItemRow.class));
    }

    @Test
    void migrationKeepsTheRulesInTheDatabase() throws Exception {
        String migration = normalized(migration());

        assertThat(migration)
                .contains("kind varchar(16) NOT NULL CHECK (kind IN ('CASE', 'KNOWLEDGE'))")
                .contains("author_org_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT")
                .contains("CONSTRAINT ck_content_article_author CHECK ((author_kind = 'PLATFORM') = (author_org_id IS NULL))")
                .contains("CONSTRAINT ck_content_article_case_author CHECK (kind <> 'CASE' OR author_kind IN ('PROVIDER', 'MERCHANT'))")
                .contains("CONSTRAINT ck_content_article_sponsored CHECK (author_kind <> 'MERCHANT' OR sponsored)")
                .contains("CONSTRAINT ck_content_article_city CHECK (kind = 'KNOWLEDGE' OR city_code IS NOT NULL)")
                .contains("CONSTRAINT ck_content_article_published CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)")
                .contains("UNIQUE (article_id, sort_order)")
                .contains("CONSTRAINT ck_content_article_block_text CHECK ((block_type IN ('FACTS', 'STEPS', 'LIST')) = (text IS NULL))")
                .contains("PRIMARY KEY (block_id, sort_order)")
                .contains("UNIQUE (article_id, ref_type, ref_id)")
                .contains("CONSTRAINT ck_content_topic_publisher CHECK ((publisher_kind = 'PLATFORM') = (publisher_org_id IS NULL))")
                .contains("CONSTRAINT ck_content_topic_dates CHECK (ends_on >= starts_on)")
                .contains("UNIQUE (topic_id, item_type, item_id)");
        // 提到的对象和专题挂的活动、场馆不引用别的模块的表；迁移不带数据。
        assertThat(migration).doesNotContain("REFERENCES activity").doesNotContain("REFERENCES venue")
                .doesNotContain("REFERENCES mall_").doesNotContain("REFERENCES pro_service")
                .doesNotContain("REFERENCES inspection_").doesNotContain("INSERT INTO");
    }

    @Test
    void migrationCodesAreTheServiceEnums() throws Exception {
        String migration = normalized(migration());

        assertThat(migration)
                .contains("kind IN (" + codes("ContentArticleKind") + ")")
                .contains("author_kind IN (" + codes("ContentAuthorKind") + ")")
                .contains("publisher_kind IN (" + codes("ContentAuthorKind") + ")")
                .contains("block_type IN (" + codes("ContentBlockType") + ")")
                .contains("ref_type IN (" + codes("ContentRefType") + ")")
                .contains("item_type IN ('ACTIVITY', 'VENUE', 'ARTICLE')");
    }

    // 这些枚举在应用层包内可见，这里按名字取。
    private static String codes(String enumName) throws ClassNotFoundException {
        Object[] values = Class.forName("com.lowaltitude.inspection.content.application." + enumName).getEnumConstants();
        return Arrays.stream(values).map(value -> "'" + ((Enum<?>) value).name() + "'").collect(Collectors.joining(", "));
    }

    private static List<String> aliases(String select) {
        String columns = normalized(select);
        columns = columns.substring(columns.indexOf("SELECT ") + "SELECT ".length(), columns.indexOf(" FROM "));
        return Arrays.stream(columns.split(","))
                .map(String::trim)
                .map(column -> column.contains(" ")
                        ? column.substring(column.lastIndexOf(' ') + 1)
                        : column.substring(column.indexOf('.') + 1))
                .map(ContentMapperContractTest::camel)
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

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : ContentMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select != null) {
                statements.put(method.getName(), String.join("\n", select.value()));
            }
        }
        return statements;
    }

    private static String sql(String methodName) {
        String sql = statements().get(methodName);
        assertThat(sql).as(methodName).isNotNull();
        return sql;
    }

    private String migration() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/content/V001__content.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 去掉注释、把连续空白并成一个空格，换行和缩进的调整不影响断言。 */
    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }
}
