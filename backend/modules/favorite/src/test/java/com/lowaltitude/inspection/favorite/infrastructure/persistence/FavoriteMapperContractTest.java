package com.lowaltitude.inspection.favorite.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.favorite.application.FavoriteTargetType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本（计划 08 S5-6 收藏）：只用绑定参数；每条语句都以账号为条件；
 * 没有删除；读出的列和行记录一一对应。再检查 V001 迁移脚本的约束。
 */
class FavoriteMapperContractTest {

    @Test
    void everyStatementBindsItsParametersAndIsLimitedToTheAccount() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(FavoriteMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(9);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        assertThat(statements).allSatisfy((name, sql) -> assertThat(normalized(sql)).as(name).contains("#{accountId}"));
        assertThat(statements.values()).noneMatch(sql -> sql.contains("DELETE"));
        for (String method : List.of("findItem", "refavorite", "restore", "remove")) {
            assertThat(normalized(sql(method)).replace("f.", "")).as(method)
                    .contains("WHERE account_id = #{accountId} AND target_type = #{targetType} AND target_id = #{targetId}");
        }
    }

    @Test
    void writesOnlyTouchTheStateTheyAreFor() {
        assertThat(normalized(sql("insertItem"))).endsWith("ON CONFLICT (account_id, target_type, target_id) DO NOTHING");
        // 重新收藏和撤销只改取消了的；取消只改还在收藏的。
        assertThat(normalized(sql("refavorite"))).endsWith("AND removed_at IS NOT NULL")
                .contains("title = #{title}, favorited_at = #{at}");
        assertThat(normalized(sql("restore"))).endsWith("AND removed_at IS NOT NULL")
                .doesNotContain("favorited_at").doesNotContain("title");
        assertThat(normalized(sql("remove"))).endsWith("AND removed_at IS NULL").contains("SET removed_at = #{at}");
        assertThat(normalized(sql("countActive"))).contains("removed_at IS NULL");
        assertThat(normalized(sql("countByType"))).contains("removed_at IS NULL").endsWith("GROUP BY target_type");
        assertThat(normalized(sql("findPage")))
                .contains("f.removed_at IS NULL AND f.target_type IN (#{firstType}, #{secondType})")
                .endsWith("ORDER BY f.favorited_at DESC, f.id DESC LIMIT #{limit} OFFSET #{offset}");
        assertThat(normalized(sql("lockAccount"))).contains("pg_advisory_xact_lock");
    }

    @Test
    void selectedColumnsAreTheRowFields() {
        assertThat(aliases(FavoriteMapper.ITEM_COLUMNS)).containsExactlyElementsOf(fields(FavoriteMapper.ItemRow.class));
        assertThat(aliases(sql("countByType"))).containsExactlyElementsOf(fields(FavoriteMapper.TypeCountRow.class));
    }

    @Test
    void migrationKeepsTheRulesInTheDatabase() throws Exception {
        String migration = normalized(migration());
        String types = Arrays.stream(FavoriteTargetType.values()).map(type -> "'" + type.name() + "'")
                .collect(Collectors.joining(", "));

        assertThat(migration)
                .contains("account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT")
                .contains("target_type varchar(24) NOT NULL CHECK (target_type IN (" + types + "))")
                .contains("title varchar(160) NOT NULL")
                .contains("CONSTRAINT uq_favorite_item_target UNIQUE (account_id, target_type, target_id)")
                .contains("WHERE removed_at IS NULL;");
        // 对象只存 ID，不引用别的模块的表；迁移也不带数据。
        assertThat(migration.split("REFERENCES")).hasSize(2);
        assertThat(migration).doesNotContain("INSERT INTO");
    }

    private static List<String> aliases(String select) {
        String columns = normalized(select);
        columns = columns.substring(columns.indexOf("SELECT ") + "SELECT ".length(), columns.indexOf(" FROM "));
        return Arrays.stream(columns.split(","))
                .map(String::trim)
                .map(column -> column.contains(" ")
                        ? column.substring(column.lastIndexOf(' ') + 1)
                        : column.substring(column.indexOf('.') + 1))
                .map(FavoriteMapperContractTest::camel)
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
        for (Method method : FavoriteMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            Update update = method.getAnnotation(Update.class);
            Insert insert = method.getAnnotation(Insert.class);
            String[] text = select != null
                    ? select.value()
                    : update != null ? update.value() : insert != null ? insert.value() : null;
            if (text != null) {
                statements.put(method.getName(), String.join("\n", text));
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
        try (var stream = getClass().getResourceAsStream("/db/migration/favorite/V001__favorite.sql")) {
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
