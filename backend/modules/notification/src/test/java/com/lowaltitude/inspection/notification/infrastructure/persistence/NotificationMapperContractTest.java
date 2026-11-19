package com.lowaltitude.inspection.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class NotificationMapperContractTest {

    @Test
    void everyStatementUsesBoundParametersOnly() {
        List<String> statements = Arrays.stream(NotificationMapper.class.getDeclaredMethods())
                .map(NotificationMapperContractTest::sql)
                .toList();

        assertThat(statements).hasSize(7);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    // 收件人条件是这张表的权限边界：读、数、标已读都必须带上。
    @Test
    void everyReadAndUpdateIsScopedToTheRecipient() throws Exception {
        for (String name : List.of("count", "countUnread", "findPage", "countOwned", "markRead", "markAllRead")) {
            assertThat(sql(method(name)))
                    .contains("recipient_id = #{recipientId}")
                    .contains("status = 'ACTIVE'");
        }
        assertThat(sql(method("markRead"))).contains("id = #{id}").contains("read_at IS NULL");
        assertThat(sql(method("markAllRead"))).contains("read_at IS NULL");
        assertThat(sql(method("findPage")))
                // 同一时刻的几条按写入先后排，写入顺序就是事件顺序。
                .contains("ORDER BY occurred_at DESC, created_at DESC, id DESC")
                .contains("LIMIT #{limit} OFFSET #{offset}")
                .doesNotContain("recipient_id,");
    }

    @Test
    void insertIsIdempotentOnTheSourceKey() throws Exception {
        assertThat(sql(method("insert")))
                .contains("INSERT INTO notification_message")
                .contains("ON CONFLICT (source_key) DO NOTHING");
    }

    @Test
    void migrationCreatesTheInboxTableWithOwnershipIdempotencyAndNoData() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/notification/V001__notification_message.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains("CREATE TABLE notification_message")
                .contains("recipient_id uuid NOT NULL REFERENCES identity_user(id)")
                .contains("source_key varchar(120) NOT NULL UNIQUE")
                .contains("category IN ('SERVICE_PROGRESS')")
                .contains("target_type IN ('INSPECTION_PLAN', 'INSPECTION_FULFILLMENT', 'INSPECTION_DELIVERY')")
                .contains("version integer NOT NULL DEFAULT 0")
                .contains("created_at timestamptz NOT NULL DEFAULT now()")
                .contains("updated_at timestamptz NOT NULL DEFAULT now()")
                .contains("idx_notification_message_inbox")
                .contains("idx_notification_message_unread")
                .doesNotContain("INSERT INTO");
    }

    // F1：分类加需求与报价，跳转目标加需求；已有的巡检通知仍然满足约束。
    @Test
    void theV002MigrationAddsTheDemandCategoryAndTarget() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/notification/V002__notification_demand.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains("CHECK (category IN ('SERVICE_PROGRESS', 'DEMAND_QUOTE'))")
                .contains("CHECK (target_type IN ('INSPECTION_PLAN', 'INSPECTION_FULFILLMENT', 'INSPECTION_DELIVERY',"
                        + " 'DEMAND'))")
                .doesNotContain("UPDATE ")
                .doesNotContain("DELETE ");
        assertThat(java.util.Arrays.stream(com.lowaltitude.inspection.notification.application.NotificationCategory.values())
                .map(Enum::name)).containsExactly("SERVICE_PROGRESS", "DEMAND_QUOTE");
    }

    private static Method method(String name) {
        return Arrays.stream(NotificationMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String sql(Method method) {
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
        throw new IllegalStateException(method.getName() + " has no SQL annotation");
    }
}
