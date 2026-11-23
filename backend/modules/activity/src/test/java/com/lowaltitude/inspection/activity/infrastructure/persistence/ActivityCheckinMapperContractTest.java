package com.lowaltitude.inspection.activity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查核销工作台（V005）的 SQL 文本：只用绑定参数；场次只读本机构主办或承办的活动；
 * 工作台记录的每条读写都以操作员本人（和他的机构）为条件；凭证只在“有效”时签到或改场次。再检查 V005 的约束。
 */
class ActivityCheckinMapperContractTest {

    @Test
    void everyStatementBindsItsParametersAndNeverInterpolatesText() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(ActivityCheckinMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void sessionsAreTheOrganizationsOwn() {
        for (String method : List.of("findSessions", "findOwnSession")) {
            assertThat(sql(method)).as(method)
                    .contains("(a.organizer_id = #{organizationId} OR a.co_organizer_id = #{organizationId})");
        }
        assertThat(sql("hostsActivities"))
                .contains("organizer_id = #{organizationId} OR co_organizer_id = #{organizationId}");
        assertThat(sql("findSessions"))
                .contains("s.starts_at >= #{from}")
                .contains("s.starts_at < #{to}")
                .contains("t.status IN ('ACTIVE', 'CHECKED_IN')")
                .contains("t.status = 'CHECKED_IN'");
    }

    @Test
    void recordsAreReadAndChangedOnlyByTheirOperator() {
        for (String method : List.of("findRecords", "findRecord", "lockRecord", "findRecentRejection")) {
            assertThat(sql(method)).as(method)
                    .contains("r.operator_id = #{operatorId}")
                    .contains("r.organization_id = #{organizationId}");
        }
        assertThat(sql("resolveRecord"))
                .contains("operator_id = #{operatorId}")
                .contains("resolution IS NULL")
                .contains("outcome <> 'CHECKED_IN'");
        assertThat(sql("lockRecord")).contains("FOR UPDATE OF r");
        // 非本机构的凭证不给持有人。
        assertThat(sql("findRecords")).contains("CASE WHEN r.outcome = 'OTHER_ORGANIZATION' THEN NULL ELSE t.participant_name END");
        assertThat(sql("findRecords")).contains("LIMIT 500").contains("ORDER BY r.occurred_at DESC");
    }

    @Test
    void ticketsAreLockedAndOnlyAnActiveOneIsCheckedInOrMoved() {
        assertThat(sql("lockTicketByCode")).contains("t.ticket_code = #{ticketCode}").contains("FOR UPDATE OF t");
        assertThat(sql("lockTicket")).contains("t.id = #{ticketId}").contains("FOR UPDATE OF t");
        assertThat(sql("lockSession")).contains("FOR UPDATE OF s");
        assertThat(sql("checkIn")).contains("AND status = 'ACTIVE'").contains("checked_in_by = #{operatorId}");
        assertThat(sql("moveAndCheckIn"))
                .contains("SET session_id = #{sessionId}")
                .contains("status = 'CHECKED_IN'")
                .contains("AND status = 'ACTIVE'");
        assertThat(sql("countOtherTickets"))
                .contains("participant_id = #{participantId}")
                .contains("status <> 'CANCELLED'")
                .contains("id <> #{ticketId}");
        assertThat(sql("insertCheckedInEvent")).contains("'TICKET_CHECKED_IN'");
    }

    @Test
    void migrationKeepsTheRecordConsistent() throws Exception {
        String migration = migration("/db/migration/activity/V005__activity_checkin_record.sql");

        assertThat(migration)
                .contains("CREATE TABLE activity_checkin_record")
                .contains("method IN ('SCAN', 'MANUAL')")
                .contains("'CHECKED_IN', 'REPEATED', 'EXPIRED', 'OTHER_ORGANIZATION', 'SESSION_MISMATCH', 'CANCELLED', 'UNPAID'")
                .contains("resolution IN ('RELEASE', 'REJECT', 'BLOCK')")
                .contains("CHECK ((outcome = 'OTHER_ORGANIZATION') = (ticket_session_id IS NULL))")
                .contains("CHECK ((outcome = 'REPEATED') = (first_checked_in_at IS NOT NULL))")
                .contains("CHECK (outcome <> 'CHECKED_IN' OR resolution IS NULL)")
                .contains("CHECK ((resolution IS NOT DISTINCT FROM 'RELEASE') = (admitted_session_id IS NOT NULL))")
                .contains("CHECK (resolution IS DISTINCT FROM 'RELEASE' OR identity_checked)")
                .contains("code_tail char(4) NOT NULL")
                .contains("resolution_note varchar(200)");
        // 凭证号只存尾号，不存整码和姓名。
        assertThat(migration).doesNotContain("ticket_code").doesNotContain("participant_name");
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : ActivityCheckinMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            Insert insert = method.getAnnotation(Insert.class);
            Update update = method.getAnnotation(Update.class);
            String[] text = select != null
                    ? select.value()
                    : insert != null ? insert.value() : update != null ? update.value() : null;
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

    private String migration(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
