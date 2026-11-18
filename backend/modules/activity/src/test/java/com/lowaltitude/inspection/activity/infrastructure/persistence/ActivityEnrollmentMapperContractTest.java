package com.lowaltitude.inspection.activity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本：只用绑定参数；参与人和报名单的每条语句都以所属账号为条件；
 * 名额在锁住场次之后增减；核销只认主办或承办机构。再检查 V002 迁移脚本的约束。
 * 真实执行由 dev 环境启动和接口联调覆盖。
 */
class ActivityEnrollmentMapperContractTest {

    @Test
    void everyStatementBindsItsParametersAndNeverInterpolatesText() {
        Map<String, String> statements = statements();

        // 接口里每个方法都带着一条语句，没有漏掉注解的。
        assertThat(statements).hasSize(ActivityEnrollmentMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(25);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void everyParticipantStatementIsLimitedToTheOwner() {
        for (String method : List.of(
                "findParticipants",
                "findParticipant",
                "countChildren",
                "countSameChild",
                "renameParticipant",
                "setPhotoUse",
                "deleteChild")) {
            assertThat(sql(method))
                    .as(method)
                    .contains("owner_id = #{ownerId}")
                    .contains("status = 'ACTIVE'");
        }
        // 两条插入语句的所属账号也只来自参数，重复时不插入。
        assertThat(sql("ensureSelf"))
                .contains("VALUES (#{ownerId}, 'SELF', #{name})")
                .contains("ON CONFLICT (owner_id) WHERE relation = 'SELF' AND status = 'ACTIVE' DO NOTHING");
        assertThat(sql("insertChild"))
                .contains("VALUES (#{ownerId}, 'CHILD', #{name}, #{birthMonth}, #{at})")
                .contains("ON CONFLICT (owner_id, name, birth_month)")
                .contains("WHERE relation = 'CHILD' AND status = 'ACTIVE' DO NOTHING")
                .contains("RETURNING id");
        assertThat(sql("findEnrolledParticipantIds"))
                .contains("p.owner_id = #{ownerId}")
                .contains("t.session_id = #{sessionId}")
                .contains("t.status <> 'CANCELLED'");
    }

    @Test
    void selfCanNeitherBeDeletedNorGivenPhotoUse() {
        assertThat(sql("deleteChild"))
                .contains("relation = 'CHILD'")
                .contains("SET status = 'DELETED'")
                .contains("photo_use_authorized = false");
        assertThat(sql("setPhotoUse")).contains("relation = 'CHILD'");
        assertThat(sql("countChildren")).contains("relation = 'CHILD'");
        assertThat(sql("countSameChild"))
                .contains("name = #{name}")
                .contains("birth_month = #{birthMonth}")
                .contains("id <> #{exceptId}");
        assertThat(sql("findParticipants"))
                .contains("ORDER BY (p.relation = 'SELF') DESC, p.created_at ASC, p.id ASC");
    }

    @Test
    void everyEnrollmentReadIsLimitedToTheAccount() {
        for (String method : List.of("findEnrollment", "countEnrollments", "findEnrollmentPage")) {
            assertThat(sql(method)).as(method).contains("e.account_id = #{accountId}");
        }
        assertThat(sql("findEnrollment")).contains("e.id = #{enrollmentId}");
        assertThat(sql("findEnrollmentByKey"))
                .contains("account_id = #{accountId}")
                .contains("idempotency_key = #{idempotencyKey}");
    }

    @Test
    void listAndCountShareOneBucketFilter() {
        String count = sql("countEnrollments");
        String page = sql("findEnrollmentPage");

        assertThat(count).contains(ActivityEnrollmentMapper.ENROLLMENT_BUCKET);
        assertThat(page).contains(ActivityEnrollmentMapper.ENROLLMENT_BUCKET);
        assertThat(ActivityEnrollmentMapper.ENROLLMENT_BUCKET)
                .contains("#{bucket} = 'CANCELLED' AND e.status = 'CANCELLED'")
                .contains("#{bucket} = 'UPCOMING' AND e.status = 'CONFIRMED'")
                .contains("COALESCE(s.ends_at, s.starts_at) > #{now}")
                .contains("#{bucket} = 'FINISHED' AND e.status = 'CONFIRMED'")
                .contains("COALESCE(s.ends_at, s.starts_at) <= #{now}");
        assertThat(page)
                .contains("CASE WHEN #{bucket} = 'UPCOMING' THEN s.starts_at END ASC")
                .contains("CASE WHEN #{bucket} <> 'UPCOMING' THEN s.starts_at END DESC")
                .contains("LIMIT #{limit} OFFSET #{offset}");
    }

    @Test
    void seatsOnlyMoveAfterTheSessionRowIsLocked() {
        String lock = sql("lockSession");

        assertThat(lock.strip()).endsWith("FOR UPDATE OF s");
        assertThat(lock).contains("WHERE s.id = #{sessionId}");
        // 给参与人算能不能报时读同样的内容，但不加锁。
        assertThat(sql("findSession"))
                .isEqualTo(ActivityEnrollmentMapper.SESSION_COLUMNS)
                .doesNotContain("FOR UPDATE");
        assertThat(lock).startsWith(ActivityEnrollmentMapper.SESSION_COLUMNS);
        assertThat(sql("addEnrolled"))
                .contains("enrolled_count = enrolled_count + #{delta}")
                .contains("WHERE id = #{sessionId}");
    }

    @Test
    void checkInOnlyFindsTicketsOfTheOrganizerOrCoOrganizerAndLocksTheTicket() {
        String lock = sql("lockTicketForOrganizer");

        assertThat(lock)
                .contains("t.ticket_code = #{ticketCode}")
                .contains("(a.organizer_id = #{organizationId} OR a.co_organizer_id = #{organizationId})");
        assertThat(lock.strip()).endsWith("FOR UPDATE OF t");
    }

    // 一张报名单里的凭证是同一时刻生成的，按生成时间或随机 ID 排，页面上人的先后会乱。
    @Test
    void ticketsComeInAStableOrderWithSelfFirst() {
        assertThat(sql("findTickets")).contains(
                "ORDER BY (t.participant_relation = 'SELF') DESC, t.participant_name ASC, t.id ASC");
    }

    @Test
    void cancelAndCheckInOnlyTouchActiveTickets() {
        assertThat(sql("cancelTicket"))
                .contains("SET status = 'CANCELLED'")
                .contains("cancelled_at = #{at}")
                .contains("id = #{ticketId}")
                .contains("enrollment_id = #{enrollmentId}")
                .contains("AND status = 'ACTIVE'");
        assertThat(sql("checkIn"))
                .contains("SET status = 'CHECKED_IN'")
                .contains("checked_in_at = #{at}")
                .contains("checked_in_by = #{operatorId}")
                .contains("WHERE id = #{ticketId}")
                .contains("AND status = 'ACTIVE'");
        // 报名单只在名下没有未取消的凭证时才变成已取消。
        assertThat(sql("cancelEnrollmentIfEmpty"))
                .contains("e.id = #{enrollmentId}")
                .contains("e.status = 'CONFIRMED'")
                .contains("NOT EXISTS")
                .contains("t.status <> 'CANCELLED'");
    }

    @Test
    void migrationKeepsOneSelfOneCopyOfAChildAndOneLiveTicketPerSession() throws Exception {
        String migration = normalized(migration());

        assertThat(migration)
                .contains("CREATE UNIQUE INDEX uq_activity_participant_self"
                        + " ON activity_participant (owner_id)"
                        + " WHERE relation = 'SELF' AND status = 'ACTIVE';")
                .contains("CREATE UNIQUE INDEX uq_activity_participant_child"
                        + " ON activity_participant (owner_id, name, birth_month)"
                        + " WHERE relation = 'CHILD' AND status = 'ACTIVE';")
                .contains("CREATE UNIQUE INDEX uq_activity_ticket_session_participant"
                        + " ON activity_enrollment_ticket (session_id, participant_id)"
                        + " WHERE status <> 'CANCELLED';")
                .contains("UNIQUE (account_id, idempotency_key)")
                .contains("ticket_code varchar(24) NOT NULL UNIQUE");
        assertThat(migration.split("CREATE UNIQUE INDEX", -1)).hasSize(4);
    }

    @Test
    void migrationTiesEveryStatusToItsTimestamps() throws Exception {
        String migration = normalized(migration());

        assertThat(migration)
                // 子女必须有出生年月和监护人确认；“本人”没有出生年月，也没有照片授权。
                .contains("CHECK ((relation = 'CHILD') = (birth_month IS NOT NULL))")
                .contains("CHECK (relation <> 'CHILD' OR guardian_confirmed_at IS NOT NULL)")
                .contains("CHECK (relation = 'CHILD' OR photo_use_authorized = false)")
                .contains("CHECK (birth_month IS NULL OR extract(day FROM birth_month) = 1)")
                .contains("CHECK ((status = 'CHECKED_IN') = (checked_in_at IS NOT NULL))")
                .contains("CHECK ((status = 'CHECKED_IN') = (checked_in_by IS NOT NULL))")
                .contains("status varchar(16) NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED', 'CANCELLED'))")
                .contains("CHECK (status IN ('ACTIVE', 'CHECKED_IN', 'CANCELLED'))")
                .contains("CHECK (event_type IN ('ENROLLED', 'TICKET_CANCELLED',"
                        + " 'ENROLLMENT_CANCELLED', 'TICKET_CHECKED_IN'))");
        // 报名单和凭证各有一条“已取消”与取消时间绑定的约束。
        assertThat(migration.split("CHECK \\(\\(status = 'CANCELLED'\\) = \\(cancelled_at IS NOT NULL\\)\\)", -1))
                .hasSize(3);
    }

    @Test
    void migrationCreatesFourTablesOwnedByAnAccountWithNoPhoneOrIdDocumentAndNoData() throws Exception {
        String migration = migration();
        // 注释里会提到“不存证件号码和手机号”，只看真正的语句。
        String statements = Arrays.stream(migration.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining("\n"))
                .toLowerCase();

        assertThat(migration)
                .contains("CREATE TABLE activity_participant (")
                .contains("CREATE TABLE activity_enrollment (")
                .contains("CREATE TABLE activity_enrollment_ticket (")
                .contains("CREATE TABLE activity_enrollment_event (")
                .contains("owner_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT")
                .contains("account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT")
                .contains("actor_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT")
                .contains("request_hash char(64) NOT NULL")
                .contains("notice_agreed_at timestamptz NOT NULL");
        assertThat(statements)
                .doesNotContain("phone")
                .doesNotContain("mobile")
                .doesNotContain("tel")
                .doesNotContain("id_card")
                .doesNotContain("id_number")
                .doesNotContain("id_no")
                .doesNotContain("identity_number")
                .doesNotContain("document")
                .doesNotContain("certificate")
                .doesNotContain("passport")
                .doesNotContain("birth_date")
                .doesNotContain("birthday")
                .doesNotContain("address")
                .doesNotContain("insert into");
        // 三张有状态的表都有乐观锁和创建/更新时间（AGENTS.md §12）；记录表只追加，只有创建时间。
        assertThat(migration.split("version integer NOT NULL DEFAULT 0", -1)).hasSize(4);
        assertThat(migration.split("updated_at timestamptz NOT NULL DEFAULT now\\(\\)", -1)).hasSize(4);
        assertThat(migration.split("created_at timestamptz NOT NULL DEFAULT now\\(\\)", -1)).hasSize(5);
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : ActivityEnrollmentMapper.class.getDeclaredMethods()) {
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

    private String migration() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/activity/V002__activity_enrollment.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 去掉注释、把连续空白并成一个空格，换行和缩进的调整不影响断言。 */
    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ");
    }
}
