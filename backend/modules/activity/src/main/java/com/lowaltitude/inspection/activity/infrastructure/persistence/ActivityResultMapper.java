package com.lowaltitude.inspection.activity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 机构工作台的“活动结果”（S5-3，迁移 V006）：主办或承办机构给已签到的凭证录入完成情况、成绩和证书说明。
 * 每条语句都以机构为条件（主办或承办）；别的机构的场次和凭证当作不存在。全部用绑定参数。
 */
public interface ActivityResultMapper {

    String ORGANIZED_BY = "(a.organizer_id = #{organizationId} OR a.co_organizer_id = #{organizationId})";

    /** 已经开始的场次，最近的在前；带报名、签到、已录结果的人数。 */
    @Select("""
            SELECT s.id session_id,
                   a.id activity_id,
                   a.title,
                   s.label,
                   s.starts_at,
                   s.ends_at,
                   count(t.id) FILTER (WHERE t.status IN ('ACTIVE', 'CHECKED_IN')) enrolled,
                   count(t.id) FILTER (WHERE t.status = 'CHECKED_IN') checked_in,
                   count(r.ticket_id) recorded
            FROM activity_session s
            JOIN activity a ON a.id = s.activity_id
            LEFT JOIN activity_enrollment_ticket t ON t.session_id = s.id
            LEFT JOIN activity_ticket_result r ON r.ticket_id = t.id
            WHERE """ + ORGANIZED_BY + """
              AND s.starts_at <= #{now}
              AND s.starts_at > #{since}
            GROUP BY s.id, a.id
            ORDER BY s.starts_at DESC, s.id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SessionRow> findSessions(
            @Param("organizationId") UUID organizationId,
            @Param("now") OffsetDateTime now,
            @Param("since") OffsetDateTime since,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT count(*)
            FROM activity_session s
            JOIN activity a ON a.id = s.activity_id
            WHERE """ + ORGANIZED_BY + """
              AND s.starts_at <= #{now}
              AND s.starts_at > #{since}
            """)
    long countSessions(
            @Param("organizationId") UUID organizationId,
            @Param("now") OffsetDateTime now,
            @Param("since") OffsetDateTime since
    );

    @Select("""
            SELECT s.id session_id,
                   a.id activity_id,
                   a.title,
                   s.label,
                   s.starts_at,
                   s.ends_at,
                   0::bigint enrolled,
                   0::bigint checked_in,
                   0::bigint recorded
            FROM activity_session s
            JOIN activity a ON a.id = s.activity_id
            WHERE s.id = #{sessionId}
              AND """ + ORGANIZED_BY)
    SessionRow findSession(@Param("organizationId") UUID organizationId, @Param("sessionId") UUID sessionId);

    /** 这一场还有效的凭证（没签到的也列出来，只是不能录结果），团队报名的带团队名称。 */
    @Select("""
            SELECT t.id ticket_id,
                   t.participant_name,
                   t.participant_age,
                   t.ticket_code,
                   t.status,
                   t.checked_in_at,
                   team.team_name,
                   r.completion,
                   r.score_text,
                   r.certificate_note,
                   r.recorded_at,
                   r.version result_version
            FROM activity_enrollment_ticket t
            JOIN activity_session s ON s.id = t.session_id
            JOIN activity a ON a.id = s.activity_id
            LEFT JOIN activity_enrollment_team team ON team.enrollment_id = t.enrollment_id
            LEFT JOIN activity_ticket_result r ON r.ticket_id = t.id
            WHERE t.session_id = #{sessionId}
              AND t.status IN ('ACTIVE', 'CHECKED_IN')
              AND """ + ORGANIZED_BY + """
            ORDER BY (t.status = 'CHECKED_IN') DESC, t.checked_in_at ASC NULLS LAST, t.ticket_code ASC
            """)
    List<RosterRow> findRoster(@Param("organizationId") UUID organizationId, @Param("sessionId") UUID sessionId);

    /** 录入前锁住凭证；只找得到本机构主办或承办的活动的凭证。 */
    @Select("""
            SELECT t.id ticket_id,
                   t.enrollment_id,
                   t.session_id,
                   e.account_id,
                   t.status,
                   s.starts_at,
                   a.title,
                   r.version result_version
            FROM activity_enrollment_ticket t
            JOIN activity_enrollment e ON e.id = t.enrollment_id
            JOIN activity_session s ON s.id = t.session_id
            JOIN activity a ON a.id = s.activity_id
            LEFT JOIN activity_ticket_result r ON r.ticket_id = t.id
            WHERE t.id = #{ticketId}
              AND """ + ORGANIZED_BY + """
            FOR UPDATE OF t
            """)
    TicketLockRow lockTicket(@Param("organizationId") UUID organizationId, @Param("ticketId") UUID ticketId);

    /** 第一次录入；已经有了时不插入，返回空。 */
    @Select("""
            INSERT INTO activity_ticket_result (
                ticket_id, organization_id, completion, score_text, certificate_note,
                recorded_by, recorded_at, created_at, updated_at
            )
            VALUES (
                #{ticketId}, #{organizationId}, #{completion}, #{scoreText}, #{certificateNote},
                #{operatorId}, #{at}, #{at}, #{at}
            )
            ON CONFLICT (ticket_id) DO NOTHING
            RETURNING version
            """)
    Integer insertResult(
            @Param("ticketId") UUID ticketId,
            @Param("organizationId") UUID organizationId,
            @Param("completion") String completion,
            @Param("scoreText") String scoreText,
            @Param("certificateNote") String certificateNote,
            @Param("operatorId") UUID operatorId,
            @Param("at") OffsetDateTime at
    );

    /** 修改：版本号对得上才改。 */
    @Update("""
            UPDATE activity_ticket_result
            SET completion = #{completion},
                score_text = #{scoreText},
                certificate_note = #{certificateNote},
                recorded_by = #{operatorId},
                recorded_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE ticket_id = #{ticketId}
              AND organization_id = #{organizationId}
              AND version = #{expectedVersion}
            """)
    int updateResult(
            @Param("ticketId") UUID ticketId,
            @Param("organizationId") UUID organizationId,
            @Param("completion") String completion,
            @Param("scoreText") String scoreText,
            @Param("certificateNote") String certificateNote,
            @Param("operatorId") UUID operatorId,
            @Param("expectedVersion") int expectedVersion,
            @Param("at") OffsetDateTime at
    );

    record SessionRow(
            UUID sessionId,
            UUID activityId,
            String title,
            String label,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            long enrolled,
            long checkedIn,
            long recorded
    ) {
    }

    record RosterRow(
            UUID ticketId,
            String participantName,
            Integer participantAge,
            String ticketCode,
            String status,
            OffsetDateTime checkedInAt,
            String teamName,
            String completion,
            String scoreText,
            String certificateNote,
            OffsetDateTime recordedAt,
            Integer resultVersion
    ) {
    }

    record TicketLockRow(
            UUID ticketId,
            UUID enrollmentId,
            UUID sessionId,
            UUID accountId,
            String status,
            OffsetDateTime startsAt,
            String title,
            Integer resultVersion
    ) {
    }
}
