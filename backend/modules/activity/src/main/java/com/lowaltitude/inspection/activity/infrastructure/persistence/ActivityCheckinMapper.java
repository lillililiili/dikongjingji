package com.lowaltitude.inspection.activity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 核销工作台（S3-3，迁移 V005）的读写。全部用绑定参数；场次只读本机构主办或承办的活动，
 * 工作台记录的每条语句都以操作员本人和他的机构为条件。“当前时间”由调用方传入。
 */
public interface ActivityCheckinMapper {

    /** 这个机构有没有主办或承办过活动（有才给它的操作员显示“工作模式”）。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM activity
                WHERE organizer_id = #{organizationId} OR co_organizer_id = #{organizationId}
            )
            """)
    boolean hostsActivities(@Param("organizationId") UUID organizationId);

    /** 本机构在 [from, to) 内开始的场次，带应到（有效和已签到的凭证）和已签到人数。 */
    @Select("""
            SELECT s.id session_id,
                   a.id activity_id,
                   a.title,
                   a.venue_name place_name,
                   s.label,
                   s.starts_at,
                   s.ends_at,
                   s.check_in_opens_at,
                   (SELECT count(*)::int FROM activity_enrollment_ticket t
                     WHERE t.session_id = s.id AND t.status IN ('ACTIVE', 'CHECKED_IN')) expected_people,
                   (SELECT count(*)::int FROM activity_enrollment_ticket t
                     WHERE t.session_id = s.id AND t.status = 'CHECKED_IN') checked_in_people
            FROM activity_session s
            JOIN activity a ON a.id = s.activity_id
            WHERE (a.organizer_id = #{organizationId} OR a.co_organizer_id = #{organizationId})
              AND s.status = 'ACTIVE'
              AND s.starts_at >= #{from}
              AND s.starts_at < #{to}
            ORDER BY s.starts_at, s.id
            """)
    List<DeskSessionRow> findSessions(
            @Param("organizationId") UUID organizationId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to
    );

    /** 本机构主办或承办的一个场次；不是本机构的为空。 */
    @Select("""
            SELECT s.id session_id,
                   a.id activity_id,
                   a.title,
                   a.venue_name place_name,
                   s.label,
                   s.starts_at,
                   s.ends_at,
                   s.check_in_opens_at,
                   0 expected_people,
                   0 checked_in_people
            FROM activity_session s
            JOIN activity a ON a.id = s.activity_id
            WHERE s.id = #{sessionId}
              AND (a.organizer_id = #{organizationId} OR a.co_organizer_id = #{organizationId})
            """)
    DeskSessionRow findOwnSession(
            @Param("organizationId") UUID organizationId,
            @Param("sessionId") UUID sessionId
    );

    /** 按凭证号锁住凭证，不分机构：是不是本机构的由调用方判断，好给出“非本机构”。 */
    @Select("""
            SELECT t.id ticket_id,
                   t.enrollment_id,
                   t.session_id,
                   t.participant_id,
                   t.ticket_code,
                   t.participant_name,
                   t.status,
                   t.checked_in_at,
                   a.id activity_id,
                   a.organizer_id,
                   a.co_organizer_id,
                   s.starts_at,
                   s.ends_at,
                   s.check_in_opens_at,
                   e.unit_amount_minor
            FROM activity_enrollment_ticket t
            JOIN activity_enrollment e ON e.id = t.enrollment_id
            JOIN activity_session s ON s.id = t.session_id
            JOIN activity a ON a.id = s.activity_id
            WHERE t.ticket_code = #{ticketCode}
            FOR UPDATE OF t
            """)
    TicketRow lockTicketByCode(@Param("ticketCode") String ticketCode);

    @Select("""
            SELECT t.id ticket_id,
                   t.enrollment_id,
                   t.session_id,
                   t.participant_id,
                   t.ticket_code,
                   t.participant_name,
                   t.status,
                   t.checked_in_at,
                   a.id activity_id,
                   a.organizer_id,
                   a.co_organizer_id,
                   s.starts_at,
                   s.ends_at,
                   s.check_in_opens_at,
                   e.unit_amount_minor
            FROM activity_enrollment_ticket t
            JOIN activity_enrollment e ON e.id = t.enrollment_id
            JOIN activity_session s ON s.id = t.session_id
            JOIN activity a ON a.id = s.activity_id
            WHERE t.id = #{ticketId}
            FOR UPDATE OF t
            """)
    TicketRow lockTicket(@Param("ticketId") UUID ticketId);

    /** 登记放行前先锁场次（按 ID 从小到大两个都锁），名额增减在锁内完成。 */
    @Select("""
            SELECT s.id session_id,
                   s.activity_id,
                   s.status,
                   s.starts_at,
                   s.ends_at,
                   s.check_in_opens_at,
                   s.capacity,
                   s.enrolled_count
            FROM activity_session s
            WHERE s.id = #{sessionId}
            FOR UPDATE OF s
            """)
    SessionLockRow lockSession(@Param("sessionId") UUID sessionId);

    @Update("""
            UPDATE activity_session
            SET enrolled_count = enrolled_count + #{delta},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{sessionId}
            """)
    int addEnrolled(
            @Param("sessionId") UUID sessionId,
            @Param("delta") int delta,
            @Param("at") OffsetDateTime at
    );

    /** 同一个参与人在 sessionId 这一场已有的有效凭证（不算 ticketId 这一张）。 */
    @Select("""
            SELECT count(*)
            FROM activity_enrollment_ticket
            WHERE session_id = #{sessionId}
              AND participant_id = #{participantId}
              AND status <> 'CANCELLED'
              AND id <> #{ticketId}
            """)
    int countOtherTickets(
            @Param("sessionId") UUID sessionId,
            @Param("participantId") UUID participantId,
            @Param("ticketId") UUID ticketId
    );

    @Update("""
            UPDATE activity_enrollment_ticket
            SET status = 'CHECKED_IN',
                checked_in_at = #{at},
                checked_in_by = #{operatorId},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{ticketId}
              AND status = 'ACTIVE'
            """)
    int checkIn(
            @Param("ticketId") UUID ticketId,
            @Param("operatorId") UUID operatorId,
            @Param("at") OffsetDateTime at
    );

    /** 登记放行：凭证改到当前场次并签到，一条语句完成。 */
    @Update("""
            UPDATE activity_enrollment_ticket
            SET session_id = #{sessionId},
                status = 'CHECKED_IN',
                checked_in_at = #{at},
                checked_in_by = #{operatorId},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{ticketId}
              AND status = 'ACTIVE'
            """)
    int moveAndCheckIn(
            @Param("ticketId") UUID ticketId,
            @Param("sessionId") UUID sessionId,
            @Param("operatorId") UUID operatorId,
            @Param("at") OffsetDateTime at
    );

    @Insert("""
            INSERT INTO activity_enrollment_event (
                enrollment_id, ticket_id, event_type, actor_id, occurred_at
            )
            VALUES (#{enrollmentId}, #{ticketId}, 'TICKET_CHECKED_IN', #{operatorId}, #{at})
            """)
    int insertCheckedInEvent(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("ticketId") UUID ticketId,
            @Param("operatorId") UUID operatorId,
            @Param("at") OffsetDateTime at
    );

    // ---- 工作台记录 ----

    @Select("""
            INSERT INTO activity_checkin_record (
                organization_id, operator_id, ticket_id, ticket_session_id, code_tail, method,
                current_session_kind, current_session_id, outcome, amount_minor, first_checked_in_at, occurred_at
            )
            VALUES (
                #{organizationId}, #{operatorId}, #{ticketId}, #{ticketSessionId}, #{codeTail}, #{method},
                #{currentKind}, #{currentSessionId}, #{outcome}, #{amountMinor}, #{firstCheckedInAt}, #{at}
            )
            RETURNING id
            """)
    UUID insertRecord(
            @Param("organizationId") UUID organizationId,
            @Param("operatorId") UUID operatorId,
            @Param("ticketId") UUID ticketId,
            @Param("ticketSessionId") UUID ticketSessionId,
            @Param("codeTail") String codeTail,
            @Param("method") String method,
            @Param("currentKind") String currentKind,
            @Param("currentSessionId") UUID currentSessionId,
            @Param("outcome") String outcome,
            @Param("amountMinor") Long amountMinor,
            @Param("firstCheckedInAt") OffsetDateTime firstCheckedInAt,
            @Param("at") OffsetDateTime at
    );

    String RECORD_COLUMNS = """
            SELECT r.id record_id,
                   r.ticket_id,
                   r.outcome,
                   r.method,
                   r.occurred_at,
                   r.code_tail,
                   CASE WHEN r.outcome = 'OTHER_ORGANIZATION' THEN NULL ELSE t.participant_name END holder_name,
                   r.people,
                   r.amount_minor,
                   r.ticket_session_id,
                   ts.activity_id ticket_activity_id,
                   ta.title ticket_title,
                   ts.label ticket_label,
                   ts.starts_at ticket_starts_at,
                   r.current_session_kind,
                   r.current_session_id,
                   cs.activity_id current_activity_id,
                   ca.title current_title,
                   cs.label current_label,
                   cs.starts_at current_starts_at,
                   r.first_checked_in_at,
                   r.resolution,
                   r.identity_checked,
                   r.resolution_note,
                   r.resolved_at,
                   r.admitted_session_id,
                   aa.title admitted_title,
                   ads.label admitted_label,
                   ads.starts_at admitted_starts_at
            FROM activity_checkin_record r
            JOIN activity_enrollment_ticket t ON t.id = r.ticket_id
            LEFT JOIN activity_session ts ON ts.id = r.ticket_session_id
            LEFT JOIN activity ta ON ta.id = ts.activity_id
            LEFT JOIN activity_session cs ON r.current_session_kind = 'ACTIVITY' AND cs.id = r.current_session_id
            LEFT JOIN activity ca ON ca.id = cs.activity_id
            LEFT JOIN activity_session ads ON ads.id = r.admitted_session_id
            LEFT JOIN activity aa ON aa.id = ads.activity_id
            """;

    /** 操作员本人在本机构、[from, to) 内的记录，新的在前；一天最多 500 条。 */
    @Select(RECORD_COLUMNS + """
            WHERE r.operator_id = #{operatorId}
              AND r.organization_id = #{organizationId}
              AND r.occurred_at >= #{from}
              AND r.occurred_at < #{to}
            ORDER BY r.occurred_at DESC, r.id DESC
            LIMIT 500
            """)
    List<RecordRow> findRecords(
            @Param("operatorId") UUID operatorId,
            @Param("organizationId") UUID organizationId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to
    );

    @Select(RECORD_COLUMNS + """
            WHERE r.id = #{recordId}
              AND r.operator_id = #{operatorId}
              AND r.organization_id = #{organizationId}
            """)
    RecordRow findRecord(
            @Param("operatorId") UUID operatorId,
            @Param("organizationId") UUID organizationId,
            @Param("recordId") UUID recordId
    );

    @Select(RECORD_COLUMNS + """
            WHERE r.id = #{recordId}
              AND r.operator_id = #{operatorId}
              AND r.organization_id = #{organizationId}
            FOR UPDATE OF r
            """)
    RecordRow lockRecord(
            @Param("operatorId") UUID operatorId,
            @Param("organizationId") UUID organizationId,
            @Param("recordId") UUID recordId
    );

    /**
     * 同一个操作员刚才（since 之后）对同一张凭证、同一个当前场次被拦下的同一种结果，还没处理的那一行：
     * 连扫两次不多记一行。
     */
    @Select(RECORD_COLUMNS + """
            WHERE r.operator_id = #{operatorId}
              AND r.organization_id = #{organizationId}
              AND r.ticket_id = #{ticketId}
              AND r.outcome = #{outcome}
              AND r.resolution IS NULL
              AND r.current_session_id IS NOT DISTINCT FROM #{currentSessionId}
              AND r.occurred_at >= #{since}
            ORDER BY r.occurred_at DESC, r.id DESC
            LIMIT 1
            """)
    RecordRow findRecentRejection(
            @Param("operatorId") UUID operatorId,
            @Param("organizationId") UUID organizationId,
            @Param("ticketId") UUID ticketId,
            @Param("outcome") String outcome,
            @Param("currentSessionId") UUID currentSessionId,
            @Param("since") OffsetDateTime since
    );

    @Update("""
            UPDATE activity_checkin_record
            SET resolution = #{resolution},
                identity_checked = #{identityChecked},
                resolution_note = #{note},
                resolved_at = #{at},
                admitted_session_id = #{admittedSessionId},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{recordId}
              AND operator_id = #{operatorId}
              AND resolution IS NULL
              AND outcome <> 'CHECKED_IN'
            """)
    int resolveRecord(
            @Param("operatorId") UUID operatorId,
            @Param("recordId") UUID recordId,
            @Param("resolution") String resolution,
            @Param("identityChecked") boolean identityChecked,
            @Param("note") String note,
            @Param("admittedSessionId") UUID admittedSessionId,
            @Param("at") OffsetDateTime at
    );

    // ---- 行 ----

    record DeskSessionRow(
            UUID sessionId,
            UUID activityId,
            String title,
            String placeName,
            String label,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt,
            int expectedPeople,
            int checkedInPeople
    ) {
    }

    record TicketRow(
            UUID ticketId,
            UUID enrollmentId,
            UUID sessionId,
            UUID participantId,
            String ticketCode,
            String participantName,
            String status,
            OffsetDateTime checkedInAt,
            UUID activityId,
            UUID organizerId,
            UUID coOrganizerId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt,
            /** 收费报名的单价（分）；免费报名为空。 */
            Long unitAmountMinor
    ) {
    }

    record SessionLockRow(
            UUID sessionId,
            UUID activityId,
            String status,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt,
            int capacity,
            int enrolledCount
    ) {
    }

    record RecordRow(
            UUID recordId,
            UUID ticketId,
            String outcome,
            String method,
            OffsetDateTime occurredAt,
            String codeTail,
            String holderName,
            int people,
            Long amountMinor,
            UUID ticketSessionId,
            UUID ticketActivityId,
            String ticketTitle,
            String ticketLabel,
            OffsetDateTime ticketStartsAt,
            String currentSessionKind,
            UUID currentSessionId,
            UUID currentActivityId,
            String currentTitle,
            String currentLabel,
            OffsetDateTime currentStartsAt,
            OffsetDateTime firstCheckedInAt,
            String resolution,
            boolean identityChecked,
            String resolutionNote,
            OffsetDateTime resolvedAt,
            UUID admittedSessionId,
            String admittedTitle,
            String admittedLabel,
            OffsetDateTime admittedStartsAt
    ) {
    }
}
