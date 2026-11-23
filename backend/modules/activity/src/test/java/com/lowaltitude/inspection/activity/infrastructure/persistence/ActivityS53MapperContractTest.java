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
 * S5-3 的三个 mapper（候补、团队与改期、工作台结果）：没有测试数据库，按模块现有做法检查 SQL 文本——
 * 只用绑定参数；客户读写以账号为条件；工作台以主办或承办机构为条件；名额只在锁住场次后动；再检查 V006 迁移的约束。
 * 真实执行由 infra/scripts/trade-smoke/activity-waitlist.cjs（临时数据库）覆盖。
 */
class ActivityS53MapperContractTest {

    @Test
    void everyStatementBindsItsParameters() {
        for (Class<?> mapper : List.of(ActivityWaitlistMapper.class, ActivityChangeMapper.class, ActivityResultMapper.class)) {
            Map<String, String> statements = statements(mapper);
            assertThat(statements).as(mapper.getSimpleName()).hasSize(mapper.getDeclaredMethods().length);
            assertThat(statements.values()).as(mapper.getSimpleName()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        }
    }

    @Test
    void customerReadsOfWaitlistsAreLimitedToTheAccount() {
        assertThat(ActivityWaitlistMapper.WAITLIST_COLUMNS).contains("WHERE w.account_id = #{accountId}");
        assertThat(sql(ActivityWaitlistMapper.class, "findWaitlist")).startsWith(ActivityWaitlistMapper.WAITLIST_COLUMNS);
        assertThat(sql(ActivityWaitlistMapper.class, "findWaitlistPage")).startsWith(ActivityWaitlistMapper.WAITLIST_COLUMNS);
        assertThat(sql(ActivityWaitlistMapper.class, "countWaitlist")).contains("w.account_id = #{accountId}");
        assertThat(sql(ActivityWaitlistMapper.class, "findOwnWaitlist")).contains("account_id = #{accountId}");
        assertThat(sql(ActivityWaitlistMapper.class, "findByKey"))
                .contains("account_id = #{accountId}")
                .contains("idempotency_key = #{idempotencyKey}");
        assertThat(sql(ActivityWaitlistMapper.class, "findWaitingParticipantIds"))
                .contains("w.account_id = #{accountId}")
                .contains("w.status IN ('WAITING', 'OFFERED')");
    }

    @Test
    void offersAndExpiryOnlyMoveFromTheStateTheyExpect() {
        assertThat(sql(ActivityWaitlistMapper.class, "offer")).contains("AND status = 'WAITING'").contains("status = 'OFFERED'");
        assertThat(sql(ActivityWaitlistMapper.class, "markEnrolled")).contains("AND status = 'OFFERED'");
        assertThat(sql(ActivityWaitlistMapper.class, "expireOffer"))
                .contains("AND status = 'OFFERED'")
                .contains("AND offer_expires_at <= #{at}");
        assertThat(sql(ActivityWaitlistMapper.class, "abandon")).contains("status IN ('WAITING', 'OFFERED', 'ENROLLED')");
        assertThat(sql(ActivityWaitlistMapper.class, "endStartedWaiting"))
                .contains("w.status = 'WAITING'")
                .contains("s.starts_at <= #{now}");
        // 转正按加入先后。
        assertThat(sql(ActivityWaitlistMapper.class, "findQueue")).contains("ORDER BY seq ASC").contains("status = 'WAITING'");
        assertThat(sql(ActivityWaitlistMapper.class, "lockWaitlist").strip()).endsWith("FOR UPDATE");
        assertThat(sql(ActivityWaitlistMapper.class, "addEnrolled"))
                .contains("enrolled_count = enrolled_count + #{delta}")
                .contains("WHERE id = #{sessionId}");
    }

    @Test
    void teamAndChangeStatementsAreLimitedToTheAccount() {
        assertThat(sql(ActivityChangeMapper.class, "findParticipantByIdentity"))
                .contains("p.owner_id = #{ownerId}")
                .contains("p.relation IN ('CHILD', 'MEMBER')")
                .contains("p.status = 'ACTIVE'");
        assertThat(sql(ActivityChangeMapper.class, "insertMember"))
                .contains("VALUES (#{ownerId}, 'MEMBER'")
                .contains("WHERE relation = 'MEMBER' AND status = 'ACTIVE' DO NOTHING");
        assertThat(sql(ActivityChangeMapper.class, "markTeam")).contains("account_id = #{accountId}");
        assertThat(sql(ActivityChangeMapper.class, "findExtras")).contains("e.account_id = #{accountId}");
        assertThat(ActivityChangeMapper.CHANGE_COLUMNS).contains("WHERE c.account_id = #{accountId}");
        assertThat(sql(ActivityChangeMapper.class, "findChange"))
                .contains("c.enrollment_id = #{enrollmentId}")
                .contains("c.id = #{changeId}");
        assertThat(sql(ActivityChangeMapper.class, "moveEnrollment"))
                .contains("account_id = #{accountId}")
                .contains("session_id = #{fromSessionId}")
                .contains("status = 'CONFIRMED'");
        assertThat(sql(ActivityChangeMapper.class, "cancelForReschedule"))
                .contains("cancel_cause = 'RESCHEDULED'")
                .contains("AND status = 'ACTIVE'");
        assertThat(sql(ActivityChangeMapper.class, "insertRescheduledTicket")).contains("'ACTIVE', #{changeId}");
    }

    @Test
    void theWorkbenchOnlySeesItsOwnActivities() {
        String organized = "(a.organizer_id = #{organizationId} OR a.co_organizer_id = #{organizationId})";
        for (String method : List.of("findSessions", "countSessions", "findSession", "findRoster", "lockTicket")) {
            assertThat(sql(ActivityResultMapper.class, method)).as(method).contains(organized);
        }
        assertThat(sql(ActivityResultMapper.class, "lockTicket").strip()).endsWith("FOR UPDATE OF t");
        assertThat(sql(ActivityResultMapper.class, "updateResult"))
                .contains("organization_id = #{organizationId}")
                .contains("version = #{expectedVersion}");
        assertThat(sql(ActivityResultMapper.class, "insertResult")).contains("ON CONFLICT (ticket_id) DO NOTHING");
        assertThat(sql(ActivityResultMapper.class, "findSessions"))
                .contains("s.starts_at <= #{now}")
                .contains("LIMIT #{limit} OFFSET #{offset}");
    }

    @Test
    void theMigrationReplacesTheAnonymousParticipantChecksAndKeepsTheNewTablesConsistent() throws Exception {
        String migration = migration("/db/migration/activity/V006__activity_waitlist_team_reschedule_result.sql");
        assertThat(migration)
                .contains("CHECK (relation IN ('SELF', 'CHILD', 'MEMBER'))")
                .contains("DROP CONSTRAINT IF EXISTS activity_participant_check;")
                .contains("DROP CONSTRAINT IF EXISTS activity_participant_check1;")
                .contains("DROP CONSTRAINT IF EXISTS activity_participant_check2;")
                .contains("CHECK ((relation IN ('CHILD', 'MEMBER')) = (birth_month IS NOT NULL))")
                .contains("CHECK (cancel_cause IN ('BUYER', 'PAYMENT_CLOSED', 'RESCHEDULED'))")
                .contains("CHECK (leader_phone ~ '^1[0-9]{10}$')")
                .contains("people smallint NOT NULL CHECK (people BETWEEN 1 AND 10)")
                .contains("CREATE INDEX idx_activity_waitlist_queue ON activity_waitlist (session_id, seq) WHERE status = 'WAITING'")
                .contains("completion varchar(16) NOT NULL CHECK (completion IN ('COMPLETED', 'INCOMPLETE'))");
    }

    private static Map<String, String> statements(Class<?> mapper) {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : mapper.getDeclaredMethods()) {
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

    private static String sql(Class<?> mapper, String methodName) {
        String sql = statements(mapper).get(methodName);
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
