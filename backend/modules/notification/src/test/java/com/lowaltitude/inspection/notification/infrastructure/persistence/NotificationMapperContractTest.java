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

        assertThat(statements).hasSize(10);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    // 收件人条件是这张表的权限边界：读、数、标已读都必须带上。
    @Test
    void everyReadAndUpdateIsScopedToTheRecipient() throws Exception {
        for (String name : List.of("count", "countUnread", "findPage", "countOwned", "markRead", "markAllRead",
                "refreshUnread", "markTargetRead")) {
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
                .map(Enum::name)).startsWith("SERVICE_PROGRESS", "DEMAND_QUOTE");
    }

    // G1：分类加客服消息，跳转目标加会话；同一会话最多一条未读，由部分唯一索引兜底。
    @Test
    void theV003MigrationAddsCustomerServiceAndOneUnreadPerConversation() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/notification/V003__notification_customer_service.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }

        assertThat(migration)
                .contains("CHECK (category IN ('SERVICE_PROGRESS', 'DEMAND_QUOTE', 'CUSTOMER_SERVICE'))")
                .contains("CHECK (target_type IN ('INSPECTION_PLAN', 'INSPECTION_FULFILLMENT', 'INSPECTION_DELIVERY',"
                        + " 'DEMAND', 'CONVERSATION'))")
                .contains("CREATE UNIQUE INDEX uq_notification_message_unread_conversation ON notification_message"
                        + " (recipient_id, target_type, target_id) WHERE category = 'CUSTOMER_SERVICE'"
                        + " AND status = 'ACTIVE' AND read_at IS NULL;")
                .doesNotContain("UPDATE ")
                .doesNotContain("DELETE ");
        assertThat(java.util.Arrays.stream(com.lowaltitude.inspection.notification.application.NotificationCategory.values())
                .map(Enum::name)).containsExactly("SERVICE_PROGRESS", "DEMAND_QUOTE", "CUSTOMER_SERVICE");
        assertThat(java.util.Arrays.stream(com.lowaltitude.inspection.notification.application.NotificationTargetType.values())
                .map(Enum::name)).containsExactly("INSPECTION_PLAN", "INSPECTION_FULFILLMENT", "INSPECTION_DELIVERY",
                        "DEMAND", "CONVERSATION");
    }

    @Test
    void oneUnreadPerTargetIsCheckedInTheInsertAndGuardedByTheIndex() throws Exception {
        String insert = sql(method("insertUnlessUnread")).replaceAll("\\s+", " ").trim();
        assertThat(insert)
                .contains("WHERE NOT EXISTS ( SELECT 1 FROM notification_message unread"
                        + " WHERE unread.recipient_id = #{recipientId} AND unread.category = #{category}"
                        + " AND unread.target_type = #{targetType} AND unread.target_id = #{targetId}"
                        + " AND unread.status = 'ACTIVE' AND unread.read_at IS NULL )")
                .endsWith("ON CONFLICT DO NOTHING");
        String refresh = sql(method("refreshUnread")).replaceAll("\\s+", " ");
        assertThat(refresh)
                .contains("occurred_at = GREATEST(occurred_at, #{occurredAt})")
                .contains("AND category = #{category} AND target_type = #{targetType} AND target_id = #{targetId}")
                .contains("read_at IS NULL");
        assertThat(sql(method("markTargetRead")).replaceAll("\\s+", " "))
                .contains("SET read_at = #{at}")
                .contains("AND category = #{category} AND target_type = #{targetType} AND target_id = #{targetId}");
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
