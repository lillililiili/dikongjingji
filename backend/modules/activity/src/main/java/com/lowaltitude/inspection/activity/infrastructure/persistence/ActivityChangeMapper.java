package com.lowaltitude.inspection.activity.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 团队报名、改期、退改记录和报名单上的结果记录（S5-3，迁移 V006）。全部用绑定参数；
 * 参与人、团队、退改记录的读都以所属账号为条件（或者调用方已经按账号读到了这张报名单）。名额的增减仍用
 * {@link ActivityEnrollmentMapper#addEnrolled}，在锁住场次之后。“当前时间”由调用方传入。
 */
public interface ActivityChangeMapper {

    // ---- 团队队员 ----

    /** 领队名下同名同出生年月的子女或队员（子女在前），团队报名时直接用它，不另建。 */
    @Select("""
            SELECT p.id
            FROM activity_participant p
            WHERE p.owner_id = #{ownerId}
              AND p.status = 'ACTIVE'
              AND p.relation IN ('CHILD', 'MEMBER')
              AND p.name = #{name}
              AND p.birth_month = #{birthMonth}
            ORDER BY (p.relation = 'CHILD') DESC, p.created_at ASC, p.id ASC
            LIMIT 1
            """)
    UUID findParticipantByIdentity(
            @Param("ownerId") UUID ownerId,
            @Param("name") String name,
            @Param("birthMonth") LocalDate birthMonth
    );

    /** 重复时不插入，返回空（调用方再按姓名和出生年月读一次）。 */
    @Select("""
            INSERT INTO activity_participant (
                owner_id, relation, name, birth_month, guardian_confirmed_at, created_at, updated_at
            )
            VALUES (#{ownerId}, 'MEMBER', #{name}, #{birthMonth}, #{at}, #{at}, #{at})
            ON CONFLICT (owner_id, name, birth_month)
                WHERE relation = 'MEMBER' AND status = 'ACTIVE' DO NOTHING
            RETURNING id
            """)
    UUID insertMember(
            @Param("ownerId") UUID ownerId,
            @Param("name") String name,
            @Param("birthMonth") LocalDate birthMonth,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE activity_enrollment
            SET kind = 'TEAM'
            WHERE id = #{enrollmentId}
              AND account_id = #{accountId}
            """)
    int markTeam(@Param("enrollmentId") UUID enrollmentId, @Param("accountId") UUID accountId);

    @Insert("""
            INSERT INTO activity_enrollment_team (
                enrollment_id, team_name, leader_name, leader_phone, guardian_consent_at, created_at
            )
            VALUES (#{enrollmentId}, #{teamName}, #{leaderName}, #{leaderPhone}, #{at}, #{at})
            """)
    int insertTeam(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("teamName") String teamName,
            @Param("leaderName") String leaderName,
            @Param("leaderPhone") String leaderPhone,
            @Param("at") OffsetDateTime at
    );

    // ---- 报名单上的附加信息 ----

    /** 团队资料和最近一次改期前的场次；只读本账号的报名单。 */
    @Select("""
            <script>
            SELECT e.id enrollment_id,
                   e.kind,
                   t.team_name,
                   t.leader_name,
                   t.leader_phone,
                   e.rescheduled_at,
                   ps.id previous_session_id,
                   ps.label previous_session_label,
                   ps.starts_at previous_starts_at,
                   ps.ends_at previous_ends_at
            FROM activity_enrollment e
            LEFT JOIN activity_enrollment_team t ON t.enrollment_id = e.id
            LEFT JOIN activity_session ps ON ps.id = e.previous_session_id
            WHERE e.account_id = #{accountId}
              AND e.id IN
            <foreach collection="enrollmentIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<EnrollmentExtrasRow> findExtras(
            @Param("accountId") UUID accountId,
            @Param("enrollmentIds") List<UUID> enrollmentIds
    );

    @Select("""
            <script>
            SELECT ticket_id, completion, score_text, certificate_note, recorded_at
            FROM activity_ticket_result
            WHERE ticket_id IN
            <foreach collection="ticketIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<ResultRow> findResults(@Param("ticketIds") List<UUID> ticketIds);

    String CHANGE_COLUMNS = """
            SELECT c.id change_id,
                   c.enrollment_id,
                   c.kind,
                   c.reason,
                   c.people,
                   c.from_session_id,
                   fs.label from_session_label,
                   fs.starts_at from_starts_at,
                   c.to_session_id,
                   ts.label to_session_label,
                   ts.starts_at to_starts_at,
                   c.refund_id,
                   c.refund_amount_minor,
                   c.created_at
            FROM activity_enrollment_change c
            JOIN activity_session fs ON fs.id = c.from_session_id
            LEFT JOIN activity_session ts ON ts.id = c.to_session_id
            WHERE c.account_id = #{accountId}
            """;

    /** 最近的在前。 */
    @Select("""
            <script>
            """ + CHANGE_COLUMNS + """
              AND c.enrollment_id IN
            <foreach collection="enrollmentIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY c.created_at DESC, c.id ASC
            </script>
            """)
    List<ChangeRow> findChanges(
            @Param("accountId") UUID accountId,
            @Param("enrollmentIds") List<UUID> enrollmentIds
    );

    @Select(CHANGE_COLUMNS + """
              AND c.enrollment_id = #{enrollmentId}
              AND c.id = #{changeId}
            """)
    ChangeRow findChange(
            @Param("accountId") UUID accountId,
            @Param("enrollmentId") UUID enrollmentId,
            @Param("changeId") UUID changeId
    );

    /** 这一次取消掉的、发出来的凭证（调用方已按账号读到这一次）。 */
    @Select("""
            SELECT t.id ticket_id,
                   t.participant_name,
                   t.participant_relation,
                   t.participant_age,
                   CASE WHEN t.issued_by_change_id = #{changeId} THEN true ELSE false END issued,
                   t.ticket_code,
                   t.status,
                   t.session_id
            FROM activity_enrollment_ticket t
            WHERE t.cancelled_by_change_id = #{changeId}
               OR t.issued_by_change_id = #{changeId}
            ORDER BY (t.participant_relation = 'SELF') DESC, t.participant_name ASC, t.id ASC
            """)
    List<ChangeTicketRow> findChangeTickets(@Param("changeId") UUID changeId);

    // ---- 记一次退改 ----

    @Select("""
            INSERT INTO activity_enrollment_change (
                enrollment_id, account_id, kind, reason, people, from_session_id, to_session_id,
                refund_id, refund_amount_minor, created_at
            )
            VALUES (
                #{enrollmentId}, #{accountId}, #{kind}, #{reason}, #{people}, #{fromSessionId}, #{toSessionId},
                #{refundId}, #{refundAmountMinor}, #{at}
            )
            RETURNING id
            """)
    UUID insertChange(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("accountId") UUID accountId,
            @Param("kind") String kind,
            @Param("reason") String reason,
            @Param("people") int people,
            @Param("fromSessionId") UUID fromSessionId,
            @Param("toSessionId") UUID toSessionId,
            @Param("refundId") UUID refundId,
            @Param("refundAmountMinor") Long refundAmountMinor,
            @Param("at") OffsetDateTime at
    );

    /** 取消（报名人取消）后，记下是哪一次取消的。 */
    @Update("""
            <script>
            UPDATE activity_enrollment_ticket
            SET cancelled_by_change_id = #{changeId},
                version = version + 1,
                updated_at = #{at}
            WHERE enrollment_id = #{enrollmentId}
              AND status = 'CANCELLED'
              AND cancelled_by_change_id IS NULL
              AND id IN
            <foreach collection="ticketIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markCancelledBy(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("ticketIds") List<UUID> ticketIds,
            @Param("changeId") UUID changeId,
            @Param("at") OffsetDateTime at
    );

    // ---- 改期（调用方已先锁交易订单、再按 ID 顺序锁住两个场次） ----

    /** 原凭证作废：只动还没用的。 */
    @Update("""
            UPDATE activity_enrollment_ticket
            SET status = 'CANCELLED',
                cancelled_at = #{at},
                cancel_cause = 'RESCHEDULED',
                cancelled_by_change_id = #{changeId},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{ticketId}
              AND enrollment_id = #{enrollmentId}
              AND status = 'ACTIVE'
            """)
    int cancelForReschedule(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("ticketId") UUID ticketId,
            @Param("changeId") UUID changeId,
            @Param("at") OffsetDateTime at
    );

    /** 新场次的凭证：凭证号新生成，参与人快照照旧，年龄按新场次当天算。 */
    @Insert("""
            INSERT INTO activity_enrollment_ticket (
                enrollment_id, session_id, participant_id,
                participant_name, participant_relation, participant_age,
                ticket_code, status, issued_by_change_id, created_at, updated_at
            )
            VALUES (
                #{enrollmentId}, #{sessionId}, #{participantId},
                #{name}, #{relation}, #{age},
                #{ticketCode}, 'ACTIVE', #{changeId}, #{at}, #{at}
            )
            """)
    int insertRescheduledTicket(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("sessionId") UUID sessionId,
            @Param("participantId") UUID participantId,
            @Param("name") String name,
            @Param("relation") String relation,
            @Param("age") Integer age,
            @Param("ticketCode") String ticketCode,
            @Param("changeId") UUID changeId,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE activity_enrollment
            SET session_id = #{toSessionId},
                previous_session_id = #{fromSessionId},
                rescheduled_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{enrollmentId}
              AND account_id = #{accountId}
              AND session_id = #{fromSessionId}
              AND status = 'CONFIRMED'
            """)
    int moveEnrollment(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("accountId") UUID accountId,
            @Param("fromSessionId") UUID fromSessionId,
            @Param("toSessionId") UUID toSessionId,
            @Param("at") OffsetDateTime at
    );

    record EnrollmentExtrasRow(
            UUID enrollmentId,
            String kind,
            String teamName,
            String leaderName,
            String leaderPhone,
            OffsetDateTime rescheduledAt,
            UUID previousSessionId,
            String previousSessionLabel,
            OffsetDateTime previousStartsAt,
            OffsetDateTime previousEndsAt
    ) {
    }

    record ResultRow(
            UUID ticketId,
            String completion,
            String scoreText,
            String certificateNote,
            OffsetDateTime recordedAt
    ) {
    }

    record ChangeRow(
            UUID changeId,
            UUID enrollmentId,
            String kind,
            String reason,
            int people,
            UUID fromSessionId,
            String fromSessionLabel,
            OffsetDateTime fromStartsAt,
            UUID toSessionId,
            String toSessionLabel,
            OffsetDateTime toStartsAt,
            UUID refundId,
            Long refundAmountMinor,
            OffsetDateTime createdAt
    ) {
    }

    record ChangeTicketRow(
            UUID ticketId,
            String participantName,
            String participantRelation,
            Integer participantAge,
            /** true：这一次发出来的新凭证；false：这一次取消掉的凭证。 */
            boolean issued,
            String ticketCode,
            String status,
            UUID sessionId
    ) {
    }
}
