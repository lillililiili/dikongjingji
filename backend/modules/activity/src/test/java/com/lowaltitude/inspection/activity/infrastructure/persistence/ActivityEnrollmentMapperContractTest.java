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
        // V002 的 25 条，加上 E09 收费报名的 11 条。
        assertThat(statements).hasSize(36);
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
        // E09：待付款的算“待参加”（或场次已结束时“已结束”），没付款就关闭的算“已取消”。
        assertThat(ActivityEnrollmentMapper.ENROLLMENT_BUCKET)
                .contains("#{bucket} = 'CANCELLED' AND e.status IN ('CANCELLED', 'CLOSED')")
                .contains("#{bucket} = 'UPCOMING' AND e.status IN ('CONFIRMED', 'PENDING_PAYMENT')")
                .contains("COALESCE(s.ends_at, s.starts_at) > #{now}")
                .contains("#{bucket} = 'FINISHED' AND e.status IN ('CONFIRMED', 'PENDING_PAYMENT')")
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

    // ---- E09 收费报名 ----

    // 收费报名是待付款的，带单价、金额、交易订单和付款截止；凭证的状态由服务给出（收费时待付款）。
    @Test
    void aPaidEnrollmentIsInsertedPendingWithItsPaymentAndTicketsTakeTheirStatus() {
        assertThat(normalized(sql("insertPaidEnrollment")))
                .contains("id, account_id, activity_id, session_id, idempotency_key, request_hash,"
                        + " status, unit_amount_minor, amount_minor, trade_order_id, pay_before,")
                .contains("#{enrollmentId}, #{accountId}, #{activityId}, #{sessionId}, #{idempotencyKey},"
                        + " #{requestHash}, 'PENDING_PAYMENT', #{unitAmountMinor}, #{amountMinor}, #{tradeOrderId},"
                        + " #{payBefore},");
        assertThat(normalized(sql("insertTicket"))).contains("#{ticketCode}, #{status}, #{at}, #{at}");
        // 报名人取消的凭证记下原因。
        assertThat(sql("cancelTicket")).contains("cancel_cause = 'BUYER'");
        // 场次一并读出单价、主办机构、城市、标题、封面和场次名，建交易订单要用。
        assertThat(ActivityEnrollmentMapper.SESSION_COLUMNS)
                .contains("a.price_amount_minor")
                .contains("a.organizer_id")
                .contains("a.city_code")
                .contains("a.title")
                .contains("a.cover_url")
                .contains("s.label");
        assertThat(ActivityEnrollmentMapper.ENROLLMENT_COLUMNS)
                .contains("e.unit_amount_minor")
                .contains("e.amount_minor")
                .contains("e.trade_order_id")
                .contains("e.pay_before");
        assertThat(sql("findTickets")).contains("t.cancel_cause").contains("t.refund_id");
    }

    // 付款结果只认报名单和它的交易订单两个 ID 一起；锁报名单在锁场次之后（服务里保证）。
    @Test
    void paymentResultsFindTheEnrollmentByItsTradeOrderToo() {
        assertThat(normalized(sql("findPaidEnrollment")))
                .endsWith("WHERE e.id = #{enrollmentId} AND e.trade_order_id = #{tradeOrderId}");
        assertThat(normalized(sql("lockPaidEnrollment")))
                .endsWith("WHERE e.id = #{enrollmentId} AND e.trade_order_id = #{tradeOrderId} FOR UPDATE");
        assertThat(sql("findPaidEnrollment")).isEqualTo(ActivityEnrollmentMapper.PAID_ENROLLMENT);
    }

    // 每一步只改处在前一个状态的行：到账只改待付款的；关闭只改待付款的；恢复只改因关闭作废的。
    @Test
    void everyPaymentStepOnlyMovesRowsInTheStateItExpects() {
        assertThat(normalized(sql("confirmPayment")))
                .contains("SET status = 'CONFIRMED'")
                .endsWith("WHERE id = #{enrollmentId} AND status = 'PENDING_PAYMENT'");
        assertThat(normalized(sql("activatePendingTickets")))
                .contains("SET status = 'ACTIVE'")
                .endsWith("WHERE enrollment_id = #{enrollmentId} AND status = 'PENDING_PAYMENT'");
        assertThat(normalized(sql("closeUnpaid")))
                .contains("SET status = 'CLOSED', closed_at = #{at}")
                .endsWith("WHERE id = #{enrollmentId} AND status = 'PENDING_PAYMENT'");
        assertThat(normalized(sql("cancelPendingTickets")))
                .contains("SET status = 'CANCELLED', cancelled_at = #{at}, cancel_cause = 'PAYMENT_CLOSED'")
                .endsWith("WHERE enrollment_id = #{enrollmentId} AND status = 'PENDING_PAYMENT'");
        assertThat(normalized(sql("restoreClosedTickets")))
                .contains("SET status = 'ACTIVE', cancelled_at = NULL, cancel_cause = NULL")
                .endsWith("WHERE enrollment_id = #{enrollmentId} AND status = 'CANCELLED'"
                        + " AND cancel_cause = 'PAYMENT_CLOSED'");
        assertThat(normalized(sql("restoreClosed")))
                .contains("SET status = 'CONFIRMED', closed_at = NULL")
                .endsWith("WHERE id = #{enrollmentId} AND status = 'CLOSED'");
        // 恢复前看这些人在这一场有没有另报（别的报名单里没取消的凭证）。
        assertThat(normalized(sql("countOtherActiveTickets")))
                .contains("WHERE other.session_id = #{sessionId} AND other.enrollment_id <> #{enrollmentId}"
                        + " AND other.status <> 'CANCELLED'")
                .contains("t.enrollment_id = #{enrollmentId} AND t.status = 'CANCELLED'"
                        + " AND t.cancel_cause = 'PAYMENT_CLOSED'");
        // 退款只记在报名人刚取消、还没记过退款的凭证上。
        assertThat(normalized(sql("setTicketRefund")))
                .startsWith("<script>")
                .contains("SET refund_id = #{refundId}")
                .contains("WHERE enrollment_id = #{enrollmentId} AND status = 'CANCELLED' AND cancel_cause = 'BUYER'"
                        + " AND refund_id IS NULL AND id IN <foreach collection=\"ticketIds\"");
    }

    @Test
    void theV003MigrationAddsThePaymentStatesAndKeepsExistingRowsValid() throws Exception {
        String migration = normalized(migration("/db/migration/activity/V003__activity_paid_enrollment.sql"));

        assertThat(migration)
                .contains("CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'CLOSED'))")
                .contains("ALTER TABLE activity_enrollment ADD COLUMN trade_order_id uuid;")
                .contains("ADD CONSTRAINT uq_activity_enrollment_trade_order UNIQUE (trade_order_id);")
                .contains("CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))")
                .contains("CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'CHECKED_IN', 'CANCELLED'))")
                .contains("CHECK (cancel_cause IN ('BUYER', 'PAYMENT_CLOSED'))")
                .contains("UPDATE activity_enrollment_ticket SET cancel_cause = 'BUYER'"
                        + " WHERE status = 'CANCELLED' AND cancel_cause IS NULL;")
                .contains("CHECK ((status = 'CANCELLED') = (cancel_cause IS NOT NULL))")
                .contains("'PAYMENT_CONFIRMED', 'PAYMENT_CLOSED', 'PAYMENT_RESTORED', 'REFUND_REQUESTED'")
                .contains("ALTER TABLE activity_enrollment_event ALTER COLUMN actor_id DROP NOT NULL;")
                .contains("CHECK (actor_id IS NOT NULL OR event_type IN ('PAYMENT_CONFIRMED', 'PAYMENT_CLOSED',"
                        + " 'PAYMENT_RESTORED'))");
        // 免费报名（已有的数据）没有交易订单和金额，只能是已报名或已取消；收费报名的四项都有，金额是单价的整数倍。
        assertThat(migration)
                .contains("(trade_order_id IS NULL AND unit_amount_minor IS NULL AND amount_minor IS NULL"
                        + " AND pay_before IS NULL AND status IN ('CONFIRMED', 'CANCELLED'))")
                .contains("amount_minor % unit_amount_minor = 0");
        // 交易表属于别的模块：不加外键。待付款的凭证仍占着“一人一场一张”的唯一索引（V002 的条件不改）。
        assertThat(migration).doesNotContain("REFERENCES").doesNotContain("uq_activity_ticket_session_participant ON");
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
        return migration("/db/migration/activity/V002__activity_enrollment.sql");
    }

    private String migration(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
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
