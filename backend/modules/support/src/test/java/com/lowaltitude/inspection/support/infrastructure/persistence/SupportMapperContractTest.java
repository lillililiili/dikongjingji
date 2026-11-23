package com.lowaltitude.inspection.support.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本（G1 客服会话）：只用绑定参数；客户的语句以客户账号为条件，
 * 回复方的语句以 DESK 为条件；序号只能接着往后写；读出的列和行记录一一对应。再检查 V001 迁移脚本的约束。
 * 真实执行由一次性数据库上的迁移检查和 infra/scripts/trade-smoke/support.cjs 覆盖。
 */
class SupportMapperContractTest {

    @Test
    void everyStatementBindsItsParametersAndNeverInterpolatesText() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(SupportMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(30);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        // 没有删除和改消息内容的语句：消息只追加。
        assertThat(statements.values()).noneMatch(sql -> sql.contains("DELETE"))
                .noneMatch(sql -> sql.contains("UPDATE support_message"));
    }

    @Test
    void customerStatementsAreLimitedToTheCustomer() {
        for (String method : List.of("findParty", "findCustomerConversation", "lockCustomerConversation",
                "countCustomerConversations", "sumCustomerUnread", "findCustomerPage", "markCustomerRead")) {
            assertThat(normalized(sql(method))).as(method).contains("customer_id = #{customerId}");
        }
        assertThat(normalized(sql("lockCustomerConversation")))
                .endsWith("WHERE c.id = #{conversationId} AND c.customer_id = #{customerId} FOR UPDATE OF c");
        assertThat(normalized(sql("findParty")))
                .contains("c.counterpart_org_id IS NOT DISTINCT FROM CAST(#{counterpartOrgId} AS uuid)");
        // 客户和对方的列表只列发过消息的会话，按最后一条消息排。
        for (String method : List.of("findCustomerPage", "findDeskPage")) {
            assertThat(normalized(sql(method))).as(method)
                    .contains("c.message_count > 0")
                    .endsWith("ORDER BY c.last_message_at DESC, c.id DESC LIMIT #{limit} OFFSET #{offset}");
        }
        // 运营端“客服会话”（1.1.0 sort=UNREAD_FIRST）：同样只列发过消息的，有未读的在前，再按最后一条消息。
        assertThat(normalized(sql("findDeskPageUnreadFirst")))
                .contains("c.message_count > 0")
                .endsWith("ORDER BY (c.counterpart_unread > 0) DESC, c.last_message_at DESC, c.id DESC"
                        + " LIMIT #{limit} OFFSET #{offset}");
    }

    @Test
    void deskStatementsOnlyReachThePlatformOrTheOwnOrganization() {
        String desk = "((#{platform} AND c.counterpart_kind = 'PLATFORM') OR (NOT #{platform}"
                + " AND c.counterpart_kind IN ('MERCHANT', 'PROVIDER') AND c.counterpart_org_id = CAST(#{organizationId} AS uuid)))";
        assertThat(normalized(SupportMapper.DESK)).isEqualTo(desk);
        for (String method : List.of("findDeskConversation", "lockDeskConversation", "countDeskConversations",
                "sumDeskUnread", "findDeskPage", "findDeskPageUnreadFirst", "markDeskRead")) {
            assertThat(normalized(sql(method))).as(method).contains(desk);
        }
        assertThat(normalized(sql("lockDeskConversation"))).endsWith("FOR UPDATE OF c");
    }

    @Test
    void sequenceNumbersOnlyGrowAndTheUnreadCountersMoveTogether() {
        assertThat(normalized(sql("recordCustomerMessage")))
                .contains("SET message_count = #{seq}, last_message_at = #{at}, counterpart_unread = counterpart_unread + 1,"
                        + " customer_unread = 0, customer_read_at = #{at}, customer_name = #{customerName}")
                .endsWith("WHERE id = #{conversationId} AND message_count = #{seq} - 1");
        assertThat(normalized(sql("recordDeskMessage")))
                .contains("SET message_count = #{seq}, last_message_at = #{at}, customer_unread = customer_unread + 1,"
                        + " counterpart_unread = 0, counterpart_read_at = #{at}")
                .endsWith("WHERE id = #{conversationId} AND message_count = #{seq} - 1");
        assertThat(normalized(sql("markCustomerRead"))).contains("SET customer_unread = 0");
        assertThat(normalized(sql("markDeskRead"))).contains("SET counterpart_unread = 0");
        for (String method : List.of("recordCustomerMessage", "recordDeskMessage", "markCustomerRead")) {
            assertThat(normalized(sql(method))).as(method).contains("version = version + 1");
        }
        assertThat(normalized(sql("markDeskRead"))).contains("version = c.version + 1");
        assertThat(normalized(sql("findMessages")))
                .contains("WHERE m.conversation_id = #{conversationId}"
                        + " AND (CAST(#{before} AS integer) IS NULL OR m.seq < CAST(#{before} AS integer))")
                .endsWith("ORDER BY m.seq DESC LIMIT #{limit}");
    }

    @Test
    void writesAreIdempotentPerSenderAndOpeningIsCreateOrGet() {
        assertThat(normalized(sql("lockSender")))
                .contains("pg_advisory_xact_lock(hashtextextended('support-sender:' || CAST(#{accountId} AS text), 0))");
        assertThat(normalized(sql("findMessageByKey")))
                .endsWith("WHERE sender_id = #{senderId} AND idempotency_key = #{idempotencyKey}");
        assertThat(normalized(sql("findFeedbackByKey")))
                .endsWith("WHERE account_id = #{accountId} AND idempotency_key = #{idempotencyKey}");
        assertThat(normalized(sql("insertConversation"))).endsWith("ON CONFLICT DO NOTHING");
        for (String method : List.of("insertMessage", "insertFeedback", "insertReportFeedback")) {
            assertThat(normalized(sql(method))).as(method).contains("RETURNING id");
            Options options = method(method).getAnnotation(Options.class);
            assertThat(options).as(method).isNotNull();
            assertThat(options.flushCache()).isEqualTo(Options.FlushCachePolicy.TRUE);
        }
    }

    // 对方看到的客户只有显示名：会话的列里没有客户的手机号、地址。
    @Test
    void nothingReadsContactDetails() {
        assertThat(statements().values()).allSatisfy(sql -> assertThat(sql)
                .doesNotContain("mobile")
                .doesNotContain("phone")
                .doesNotContain("address")
                .doesNotContain("identity_"));
        assertThat(SupportMapper.CONVERSATION_COLUMNS).doesNotContain("sender_id");
        assertThat(SupportMapper.MESSAGE_COLUMNS).doesNotContain("sender_id");
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
        assertThat(aliases(SupportMapper.CONVERSATION_COLUMNS))
                .containsExactlyElementsOf(fields(SupportMapper.ConversationRow.class));
        assertThat(aliases(SupportMapper.MESSAGE_COLUMNS)).containsExactlyElementsOf(fields(SupportMapper.MessageRow.class));
        assertThat(aliases(sql("findMessageByKey"))).containsExactlyElementsOf(fields(SupportMapper.KeyRow.class));
        assertThat(aliases(sql("findFeedbackByKey"))).containsExactlyElementsOf(fields(SupportMapper.FeedbackRow.class));
        String returning = normalized(sql("insertFeedback"));
        assertThat(returning.substring(returning.indexOf("RETURNING ") + "RETURNING ".length()))
                .isEqualTo("id, account_id, feedback_type, body, request_hash, created_at");
    }

    // S3-1：运营端处理反馈只按处理状态和来源筛，只处理一次（还是待处理、版本号对得上才改）；举报记来源和对象。
    @Test
    void opsFeedbackStatementsFilterOnlyByStatusAndSourceAndHandleOnce() {
        String filter = "WHERE (CAST(#{handleStatus} AS varchar) IS NULL OR f.handle_status = #{handleStatus})"
                + " AND (CAST(#{source} AS varchar) IS NULL OR f.source = #{source})";
        assertThat(normalized(SupportMapper.OPS_FEEDBACK_FILTER)).isEqualTo(filter);
        for (String method : List.of("countOpsFeedback", "findOpsFeedback")) {
            assertThat(normalized(sql(method))).as(method).contains(filter);
        }
        assertThat(normalized(sql("findOpsFeedback"))).endsWith("LIMIT #{limit} OFFSET #{offset}");
        assertThat(normalized(sql("handleFeedback")))
                .contains("SET handle_status = 'HANDLED', handle_note = #{note}, handled_at = #{at},"
                        + " handled_by = #{handlerId}, handled_by_name = #{handlerName}, version = version + 1")
                .endsWith("WHERE id = #{feedbackId} AND handle_status = 'PENDING' AND version = #{version}");
        assertThat(normalized(sql("insertReportFeedback")))
                .contains("#{source}, #{targetId}, #{targetParentId}");
        assertThat(aliases(SupportMapper.OPS_FEEDBACK_COLUMNS))
                .containsExactlyElementsOf(fields(SupportMapper.OpsFeedbackRow.class));
        String returning = normalized(sql("insertReportFeedback"));
        assertThat(returning.substring(returning.indexOf("RETURNING ") + "RETURNING ".length()))
                .isEqualTo("id, account_id, feedback_type, body, request_hash, created_at");
    }

    @Test
    void handlingMigrationKeepsTheRulesInTheDatabase() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/support/V002__support_feedback_handling.sql")) {
            assertThat(stream).isNotNull();
            migration = normalized(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(migration)
                .contains("source IN (" + codes("SupportFeedbackSource") + ")")
                .contains("handle_status IN (" + codes("SupportFeedbackHandleStatus") + ")")
                .contains("handle_note varchar(200)")
                .contains("CHECK ((source = 'USER') = (target_id IS NULL) AND (target_id IS NULL) = (target_parent_id IS NULL))")
                .doesNotContain("INSERT INTO").doesNotContain("UPDATE ").doesNotContain("DROP ");
    }

    @Test
    void migrationKeepsTheRulesInTheDatabase() throws Exception {
        String migration = normalized(migration());

        assertThat(migration)
                .contains("customer_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT")
                .contains("counterpart_kind varchar(16) NOT NULL CHECK (counterpart_kind IN ('PLATFORM', 'MERCHANT', 'PROVIDER'))")
                .contains("counterpart_org_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT")
                .contains("CONSTRAINT ck_support_conversation_counterpart CHECK ((counterpart_kind = 'PLATFORM')"
                        + " = (counterpart_org_id IS NULL))")
                .contains("CONSTRAINT ck_support_conversation_last CHECK ((message_count = 0) = (last_message_at IS NULL))")
                .contains("customer_unread integer NOT NULL DEFAULT 0 CHECK (customer_unread >= 0)")
                .contains("counterpart_unread integer NOT NULL DEFAULT 0 CHECK (counterpart_unread >= 0)")
                .contains("CREATE UNIQUE INDEX uq_support_conversation_platform ON support_conversation (customer_id)"
                        + " WHERE counterpart_kind = 'PLATFORM';")
                .contains("CREATE UNIQUE INDEX uq_support_conversation_party ON support_conversation"
                        + " (customer_id, counterpart_kind, counterpart_org_id) WHERE counterpart_kind <> 'PLATFORM';")
                .contains("text_body varchar(500)")
                .contains("card_title varchar(60)")
                .contains("card_subtitle varchar(60)")
                .contains("UNIQUE (conversation_id, seq)")
                .contains("UNIQUE (sender_id, idempotency_key)")
                .contains("(kind = 'TEXT' AND text_body IS NOT NULL AND card_type IS NULL AND card_ref_id IS NULL"
                        + " AND card_title IS NULL AND card_subtitle IS NULL)")
                .contains("(kind = 'CARD' AND text_body IS NULL AND card_type IS NOT NULL AND card_ref_id IS NOT NULL"
                        + " AND card_title IS NOT NULL)")
                .contains("body varchar(200) NOT NULL")
                .contains("UNIQUE (account_id, idempotency_key)");
        // 不存图片、手机号和地址，迁移也不带数据。
        assertThat(migration).doesNotContain("image").doesNotContain("phone").doesNotContain("mobile")
                .doesNotContain("INSERT INTO");
    }

    @Test
    void migrationCodesAreTheServiceEnums() throws Exception {
        String migration = normalized(migration());

        assertThat(migration)
                .contains("counterpart_kind IN (" + codes("SupportCounterpartKind") + ")")
                .contains("sender_side IN (" + codes("SupportSenderSide") + ")")
                .contains("kind IN (" + codes("SupportMessageKind") + ")")
                .contains("card_type IN (" + codes("SupportCardType") + ")")
                .contains("feedback_type IN (" + codes("SupportFeedbackType") + ")");
    }

    // 这些枚举在应用层包内可见，这里按名字取。
    private static String codes(String enumName) throws ClassNotFoundException {
        Object[] values = Class.forName("com.lowaltitude.inspection.support.application." + enumName).getEnumConstants();
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
                .map(SupportMapperContractTest::camel)
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

    private static Method method(String name) {
        return Arrays.stream(SupportMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : SupportMapper.class.getDeclaredMethods()) {
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
        try (var stream = getClass().getResourceAsStream("/db/migration/support/V001__support.sql")) {
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
