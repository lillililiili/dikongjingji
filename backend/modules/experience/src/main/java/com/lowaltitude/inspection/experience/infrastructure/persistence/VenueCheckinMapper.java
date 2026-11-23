package com.lowaltitude.inspection.experience.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 核销工作台（S3-3，迁移 V006）的读写。全部用绑定参数；场次只读本机构运营的场馆，
 * 工作台记录的每条语句都以操作员本人和他的机构为条件。“当前时间”由调用方传入。
 */
public interface VenueCheckinMapper {

    /** 这个机构有没有运营场馆（有才给它的操作员显示“工作模式”）。 */
    @Select("""
            SELECT EXISTS (SELECT 1 FROM venue WHERE operator_id = #{organizationId})
            """)
    boolean operatesVenues(@Param("organizationId") UUID organizationId);

    /** 本机构场馆在 [from, to) 内开始的场次，带应到（待到馆和已核销的预约人数）和已核销人数。 */
    @Select("""
            SELECT s.id session_id,
                   p.id project_id,
                   p.name title,
                   v.name place_name,
                   s.starts_at,
                   s.ends_at,
                   (SELECT COALESCE(sum(b.people), 0)::int FROM venue_booking b
                     WHERE b.session_id = s.id AND b.status IN ('CONFIRMED', 'CHECKED_IN')) expected_people,
                   (SELECT COALESCE(sum(b.people), 0)::int FROM venue_booking b
                     WHERE b.session_id = s.id AND b.status = 'CHECKED_IN') checked_in_people
            FROM venue_session s
            JOIN venue_project p ON p.id = s.project_id
            JOIN venue v ON v.id = p.venue_id
            WHERE v.operator_id = #{organizationId}
              AND s.status = 'ACTIVE'
              AND s.starts_at >= #{from}
              AND s.starts_at < #{to}
            ORDER BY s.starts_at, v.id, p.sort_order, s.id
            """)
    List<DeskSessionRow> findSessions(
            @Param("organizationId") UUID organizationId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to
    );

    /** 本机构场馆的一个场次；不是本机构的为空。 */
    @Select("""
            SELECT s.id session_id,
                   p.id project_id,
                   p.name title,
                   v.name place_name,
                   s.starts_at,
                   s.ends_at,
                   0 expected_people,
                   0 checked_in_people
            FROM venue_session s
            JOIN venue_project p ON p.id = s.project_id
            JOIN venue v ON v.id = p.venue_id
            WHERE s.id = #{sessionId}
              AND v.operator_id = #{organizationId}
            """)
    DeskSessionRow findOwnSession(
            @Param("organizationId") UUID organizationId,
            @Param("sessionId") UUID sessionId
    );

    String BOOKING_COLUMNS = """
            SELECT b.id booking_id,
                   b.session_id,
                   b.project_id,
                   b.code,
                   b.status,
                   b.people,
                   b.amount_minor,
                   b.checked_in_at,
                   v.operator_id,
                   s.starts_at,
                   s.ends_at
            FROM venue_booking b
            JOIN venue v ON v.id = b.venue_id
            JOIN venue_session s ON s.id = b.session_id
            """;

    /** 按核销码锁住预约，不分机构：是不是本机构的由调用方判断，好给出“非本机构”。改期作废的旧码查不到。 */
    @Select(BOOKING_COLUMNS + """
            WHERE b.code = #{code}
            FOR UPDATE OF b
            """)
    BookingRow lockBookingByCode(@Param("code") String code);

    @Select(BOOKING_COLUMNS + """
            WHERE b.id = #{bookingId}
            FOR UPDATE OF b
            """)
    BookingRow lockBooking(@Param("bookingId") UUID bookingId);

    /** 登记放行前先锁场次（按 ID 从小到大两个都锁），名额增减在锁内完成。 */
    @Select("""
            SELECT s.id session_id,
                   s.project_id,
                   s.status,
                   s.starts_at,
                   s.ends_at,
                   s.capacity,
                   s.booked_count
            FROM venue_session s
            WHERE s.id = #{sessionId}
            FOR UPDATE OF s
            """)
    SessionLockRow lockSession(@Param("sessionId") UUID sessionId);

    @Update("""
            UPDATE venue_session
            SET booked_count = booked_count + #{delta},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{sessionId}
            """)
    int addBooked(@Param("sessionId") UUID sessionId, @Param("delta") int delta, @Param("at") OffsetDateTime at);

    /** 这张预约的人在 sessionId 这一场另有有效预约的个数（不算这张预约自己）。 */
    @Select("""
            SELECT count(*)
            FROM venue_booking_participant other
            WHERE other.session_id = #{sessionId}
              AND other.active
              AND other.booking_id <> #{bookingId}
              AND other.participant_id IN (
                  SELECT mine.participant_id
                  FROM venue_booking_participant mine
                  WHERE mine.booking_id = #{bookingId}
              )
            """)
    int countOtherActiveParticipants(@Param("bookingId") UUID bookingId, @Param("sessionId") UUID sessionId);

    @Update("""
            UPDATE venue_booking
            SET status = 'CHECKED_IN',
                checked_in_at = #{at},
                checked_in_by = #{operatorId},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{bookingId}
              AND status = 'CONFIRMED'
            """)
    int checkIn(@Param("bookingId") UUID bookingId, @Param("operatorId") UUID operatorId, @Param("at") OffsetDateTime at);

    /** 登记放行：预约改到当前场次并核销，核销码不换（核销后不再用）。 */
    @Update("""
            UPDATE venue_booking
            SET session_id = #{sessionId},
                status = 'CHECKED_IN',
                checked_in_at = #{at},
                checked_in_by = #{operatorId},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{bookingId}
              AND status = 'CONFIRMED'
            """)
    int moveAndCheckIn(
            @Param("bookingId") UUID bookingId,
            @Param("sessionId") UUID sessionId,
            @Param("operatorId") UUID operatorId,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE venue_booking_participant
            SET session_id = #{sessionId},
                version = version + 1,
                updated_at = #{at}
            WHERE booking_id = #{bookingId}
            """)
    int moveParticipants(
            @Param("bookingId") UUID bookingId,
            @Param("sessionId") UUID sessionId,
            @Param("at") OffsetDateTime at
    );

    @Insert("""
            INSERT INTO venue_booking_event (booking_id, event_type, actor_id, from_session_id, to_session_id, occurred_at)
            VALUES (#{bookingId}, #{eventType}, #{actorId}, #{fromSessionId}, #{toSessionId}, #{at})
            """)
    int insertEvent(
            @Param("bookingId") UUID bookingId,
            @Param("eventType") String eventType,
            @Param("actorId") UUID actorId,
            @Param("fromSessionId") UUID fromSessionId,
            @Param("toSessionId") UUID toSessionId,
            @Param("at") OffsetDateTime at
    );

    // ---- 工作台记录 ----

    @Select("""
            INSERT INTO venue_checkin_record (
                organization_id, operator_id, booking_id, booking_session_id, code_tail, method,
                current_session_kind, current_session_id, outcome, people, amount_minor, first_checked_in_at, occurred_at
            )
            VALUES (
                #{organizationId}, #{operatorId}, #{bookingId}, #{bookingSessionId}, #{codeTail}, #{method},
                #{currentKind}, #{currentSessionId}, #{outcome}, #{people}, #{amountMinor}, #{firstCheckedInAt}, #{at}
            )
            RETURNING id
            """)
    UUID insertRecord(
            @Param("organizationId") UUID organizationId,
            @Param("operatorId") UUID operatorId,
            @Param("bookingId") UUID bookingId,
            @Param("bookingSessionId") UUID bookingSessionId,
            @Param("codeTail") String codeTail,
            @Param("method") String method,
            @Param("currentKind") String currentKind,
            @Param("currentSessionId") UUID currentSessionId,
            @Param("outcome") String outcome,
            @Param("people") int people,
            @Param("amountMinor") Long amountMinor,
            @Param("firstCheckedInAt") OffsetDateTime firstCheckedInAt,
            @Param("at") OffsetDateTime at
    );

    String RECORD_COLUMNS = """
            SELECT r.id record_id,
                   r.booking_id,
                   r.outcome,
                   r.method,
                   r.occurred_at,
                   r.code_tail,
                   CASE WHEN r.outcome = 'OTHER_ORGANIZATION' THEN NULL ELSE (
                       SELECT bp.participant_name FROM venue_booking_participant bp
                       WHERE bp.booking_id = r.booking_id
                       ORDER BY bp.created_at, bp.id
                       LIMIT 1
                   ) END holder_name,
                   r.people,
                   r.amount_minor,
                   r.booking_session_id,
                   bs.project_id booking_project_id,
                   bpj.name booking_title,
                   bs.starts_at booking_starts_at,
                   r.current_session_kind,
                   r.current_session_id,
                   cs.project_id current_project_id,
                   cpj.name current_title,
                   cs.starts_at current_starts_at,
                   r.first_checked_in_at,
                   r.resolution,
                   r.identity_checked,
                   r.resolution_note,
                   r.resolved_at,
                   r.admitted_session_id,
                   apj.name admitted_title,
                   ads.starts_at admitted_starts_at
            FROM venue_checkin_record r
            LEFT JOIN venue_session bs ON bs.id = r.booking_session_id
            LEFT JOIN venue_project bpj ON bpj.id = bs.project_id
            LEFT JOIN venue_session cs ON r.current_session_kind = 'VENUE' AND cs.id = r.current_session_id
            LEFT JOIN venue_project cpj ON cpj.id = cs.project_id
            LEFT JOIN venue_session ads ON ads.id = r.admitted_session_id
            LEFT JOIN venue_project apj ON apj.id = ads.project_id
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

    /** 同一个操作员刚才对同一张预约、同一个当前场次被拦下的同一种结果，还没处理的那一行。 */
    @Select(RECORD_COLUMNS + """
            WHERE r.operator_id = #{operatorId}
              AND r.organization_id = #{organizationId}
              AND r.booking_id = #{bookingId}
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
            @Param("bookingId") UUID bookingId,
            @Param("outcome") String outcome,
            @Param("currentSessionId") UUID currentSessionId,
            @Param("since") OffsetDateTime since
    );

    @Update("""
            UPDATE venue_checkin_record
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
            UUID projectId,
            String title,
            String placeName,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            int expectedPeople,
            int checkedInPeople
    ) {
    }

    record BookingRow(
            UUID bookingId,
            UUID sessionId,
            UUID projectId,
            String code,
            String status,
            int people,
            long amountMinor,
            OffsetDateTime checkedInAt,
            UUID operatorId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt
    ) {
    }

    record SessionLockRow(
            UUID sessionId,
            UUID projectId,
            String status,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            int capacity,
            int bookedCount
    ) {
    }

    record RecordRow(
            UUID recordId,
            UUID bookingId,
            String outcome,
            String method,
            OffsetDateTime occurredAt,
            String codeTail,
            String holderName,
            int people,
            Long amountMinor,
            UUID bookingSessionId,
            UUID bookingProjectId,
            String bookingTitle,
            OffsetDateTime bookingStartsAt,
            String currentSessionKind,
            UUID currentSessionId,
            UUID currentProjectId,
            String currentTitle,
            OffsetDateTime currentStartsAt,
            OffsetDateTime firstCheckedInAt,
            String resolution,
            boolean identityChecked,
            String resolutionNote,
            OffsetDateTime resolvedAt,
            UUID admittedSessionId,
            String admittedTitle,
            OffsetDateTime admittedStartsAt
    ) {
    }
}
