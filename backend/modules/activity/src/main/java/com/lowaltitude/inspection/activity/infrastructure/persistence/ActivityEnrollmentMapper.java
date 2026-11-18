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
 * 参与人、报名单和凭证的读写。全部用绑定参数；参与人和报名单的每条语句都以所属账号为条件，
 * 核销以主办或承办机构为条件。“当前时间”由调用方传入。
 */
public interface ActivityEnrollmentMapper {

    // ---- 参与人 ----

    @Insert("""
            INSERT INTO activity_participant (owner_id, relation, name)
            VALUES (#{ownerId}, 'SELF', #{name})
            ON CONFLICT (owner_id) WHERE relation = 'SELF' AND status = 'ACTIVE' DO NOTHING
            """)
    int ensureSelf(@Param("ownerId") UUID ownerId, @Param("name") String name);

    String PARTICIPANT_COLUMNS = """
            SELECT p.id,
                   p.relation,
                   p.name,
                   p.birth_month,
                   p.photo_use_authorized
            FROM activity_participant p
            WHERE p.owner_id = #{ownerId}
              AND p.status = 'ACTIVE'
            """;

    @Select(PARTICIPANT_COLUMNS + """
            ORDER BY (p.relation = 'SELF') DESC, p.created_at ASC, p.id ASC
            """)
    List<ParticipantRow> findParticipants(@Param("ownerId") UUID ownerId);

    @Select(PARTICIPANT_COLUMNS + """
              AND p.id = #{participantId}
            """)
    ParticipantRow findParticipant(
            @Param("ownerId") UUID ownerId,
            @Param("participantId") UUID participantId
    );

    @Select("""
            SELECT count(*)
            FROM activity_participant
            WHERE owner_id = #{ownerId}
              AND relation = 'CHILD'
              AND status = 'ACTIVE'
            """)
    int countChildren(@Param("ownerId") UUID ownerId);

    @Select("""
            SELECT count(*)
            FROM activity_participant
            WHERE owner_id = #{ownerId}
              AND relation = 'CHILD'
              AND status = 'ACTIVE'
              AND name = #{name}
              AND birth_month = #{birthMonth}
              AND id <> #{exceptId}
            """)
    int countSameChild(
            @Param("ownerId") UUID ownerId,
            @Param("name") String name,
            @Param("birthMonth") LocalDate birthMonth,
            @Param("exceptId") UUID exceptId
    );

    /** 重复添加时不插入，返回空。 */
    @Select("""
            INSERT INTO activity_participant (
                owner_id, relation, name, birth_month, guardian_confirmed_at
            )
            VALUES (#{ownerId}, 'CHILD', #{name}, #{birthMonth}, #{at})
            ON CONFLICT (owner_id, name, birth_month)
                WHERE relation = 'CHILD' AND status = 'ACTIVE' DO NOTHING
            RETURNING id
            """)
    UUID insertChild(
            @Param("ownerId") UUID ownerId,
            @Param("name") String name,
            @Param("birthMonth") LocalDate birthMonth,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE activity_participant
            SET name = #{name},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{participantId}
              AND owner_id = #{ownerId}
              AND status = 'ACTIVE'
            """)
    int renameParticipant(
            @Param("ownerId") UUID ownerId,
            @Param("participantId") UUID participantId,
            @Param("name") String name,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE activity_participant
            SET photo_use_authorized = #{authorized},
                photo_use_changed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{participantId}
              AND owner_id = #{ownerId}
              AND relation = 'CHILD'
              AND status = 'ACTIVE'
            """)
    int setPhotoUse(
            @Param("ownerId") UUID ownerId,
            @Param("participantId") UUID participantId,
            @Param("authorized") boolean authorized,
            @Param("at") OffsetDateTime at
    );

    /** “本人”不能删；删除后已有凭证保留报名时的快照。 */
    @Update("""
            UPDATE activity_participant
            SET status = 'DELETED',
                photo_use_authorized = false,
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{participantId}
              AND owner_id = #{ownerId}
              AND relation = 'CHILD'
              AND status = 'ACTIVE'
            """)
    int deleteChild(
            @Param("ownerId") UUID ownerId,
            @Param("participantId") UUID participantId,
            @Param("at") OffsetDateTime at
    );

    // ---- 场次（加锁） ----

    String SESSION_COLUMNS = """
            SELECT s.id session_id,
                   a.id activity_id,
                   a.status activity_status,
                   a.price_mode,
                   a.min_age,
                   a.max_age,
                   a.waitlist_allowed,
                   s.status session_status,
                   s.starts_at,
                   s.ends_at,
                   s.enrollment_closes_at,
                   s.capacity,
                   s.enrolled_count
            FROM activity_session s
            JOIN activity a ON a.id = s.activity_id
            WHERE s.id = #{sessionId}
            """;

    /** 报名、取消都先锁住场次这一行，名额增减在锁内完成。 */
    @Select(SESSION_COLUMNS + """
            FOR UPDATE OF s
            """)
    SessionRow lockSession(@Param("sessionId") UUID sessionId);

    /** 不加锁读同样的内容，用于给参与人算能不能报。 */
    @Select(SESSION_COLUMNS)
    SessionRow findSession(@Param("sessionId") UUID sessionId);

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

    // ---- 报名单 ----

    @Select("""
            SELECT id, request_hash
            FROM activity_enrollment
            WHERE account_id = #{accountId}
              AND idempotency_key = #{idempotencyKey}
            """)
    EnrollmentKeyRow findEnrollmentByKey(
            @Param("accountId") UUID accountId,
            @Param("idempotencyKey") UUID idempotencyKey
    );

    /** 该账号名下、在这个场次已有有效凭证的参与人。 */
    @Select("""
            SELECT t.participant_id
            FROM activity_enrollment_ticket t
            JOIN activity_participant p ON p.id = t.participant_id
            WHERE t.session_id = #{sessionId}
              AND t.status <> 'CANCELLED'
              AND p.owner_id = #{ownerId}
            """)
    List<UUID> findEnrolledParticipantIds(
            @Param("sessionId") UUID sessionId,
            @Param("ownerId") UUID ownerId
    );

    @Select("""
            INSERT INTO activity_enrollment (
                account_id, activity_id, session_id, idempotency_key, request_hash,
                notice_agreed_at, created_at, updated_at
            )
            VALUES (
                #{accountId}, #{activityId}, #{sessionId}, #{idempotencyKey}, #{requestHash},
                #{at}, #{at}, #{at}
            )
            RETURNING id
            """)
    UUID insertEnrollment(
            @Param("accountId") UUID accountId,
            @Param("activityId") UUID activityId,
            @Param("sessionId") UUID sessionId,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            INSERT INTO activity_enrollment_ticket (
                enrollment_id, session_id, participant_id,
                participant_name, participant_relation, participant_age,
                ticket_code, created_at, updated_at
            )
            VALUES (
                #{enrollmentId}, #{sessionId}, #{participantId},
                #{name}, #{relation}, #{age},
                #{ticketCode}, #{at}, #{at}
            )
            RETURNING id
            """)
    UUID insertTicket(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("sessionId") UUID sessionId,
            @Param("participantId") UUID participantId,
            @Param("name") String name,
            @Param("relation") String relation,
            @Param("age") Integer age,
            @Param("ticketCode") String ticketCode,
            @Param("at") OffsetDateTime at
    );

    @Insert("""
            INSERT INTO activity_enrollment_event (
                enrollment_id, ticket_id, event_type, actor_id, occurred_at
            )
            VALUES (#{enrollmentId}, #{ticketId}, #{eventType}, #{actorId}, #{at})
            """)
    int insertEvent(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("ticketId") UUID ticketId,
            @Param("eventType") String eventType,
            @Param("actorId") UUID actorId,
            @Param("at") OffsetDateTime at
    );

    String ENROLLMENT_COLUMNS = """
            SELECT e.id enrollment_id,
                   e.status,
                   e.created_at,
                   a.id activity_id,
                   a.type activity_type,
                   a.title,
                   a.cover_url,
                   a.city_code,
                   organizer.organization_name organizer_name,
                   a.venue_name,
                   a.venue_address,
                   ST_Y(a.venue_location::geometry) latitude,
                   ST_X(a.venue_location::geometry) longitude,
                   s.id session_id,
                   s.label session_label,
                   s.starts_at,
                   s.ends_at,
                   s.check_in_opens_at
            FROM activity_enrollment e
            JOIN activity a ON a.id = e.activity_id
            JOIN activity_session s ON s.id = e.session_id
            JOIN identity_organization organizer ON organizer.id = a.organizer_id
            WHERE e.account_id = #{accountId}
            """;

    /** 待参加：没取消、场次还没结束；已结束：没取消、场次已结束；已取消：全部凭证都取消了。 */
    String ENROLLMENT_BUCKET = """
              AND (
                  (#{bucket} = 'CANCELLED' AND e.status = 'CANCELLED')
                  OR (#{bucket} = 'UPCOMING' AND e.status = 'CONFIRMED'
                      AND COALESCE(s.ends_at, s.starts_at) > #{now})
                  OR (#{bucket} = 'FINISHED' AND e.status = 'CONFIRMED'
                      AND COALESCE(s.ends_at, s.starts_at) <= #{now})
              )
            """;

    @Select(ENROLLMENT_COLUMNS + """
              AND e.id = #{enrollmentId}
            """)
    EnrollmentRow findEnrollment(
            @Param("accountId") UUID accountId,
            @Param("enrollmentId") UUID enrollmentId
    );

    @Select("""
            SELECT count(*)
            FROM activity_enrollment e
            JOIN activity_session s ON s.id = e.session_id
            WHERE e.account_id = #{accountId}
            """ + ENROLLMENT_BUCKET)
    long countEnrollments(
            @Param("accountId") UUID accountId,
            @Param("bucket") String bucket,
            @Param("now") OffsetDateTime now
    );

    /** 待参加按开始时间从近到远，其余两类最近的在前。 */
    @Select(ENROLLMENT_COLUMNS + ENROLLMENT_BUCKET + """
            ORDER BY CASE WHEN #{bucket} = 'UPCOMING' THEN s.starts_at END ASC,
                     CASE WHEN #{bucket} <> 'UPCOMING' THEN s.starts_at END DESC,
                     e.created_at DESC,
                     e.id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<EnrollmentRow> findEnrollmentPage(
            @Param("accountId") UUID accountId,
            @Param("bucket") String bucket,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 一张报名单里的凭证同时生成，按“本人在前、其余按姓名”排，页面上的顺序才稳定。 */
    @Select("""
            <script>
            SELECT t.id ticket_id,
                   t.enrollment_id,
                   t.participant_id,
                   t.participant_name,
                   t.participant_relation,
                   t.participant_age,
                   t.ticket_code,
                   t.status,
                   t.checked_in_at,
                   t.cancelled_at
            FROM activity_enrollment_ticket t
            WHERE t.enrollment_id IN
            <foreach collection="enrollmentIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY (t.participant_relation = 'SELF') DESC, t.participant_name ASC, t.id ASC
            </script>
            """)
    List<TicketRow> findTickets(@Param("enrollmentIds") List<UUID> enrollmentIds);

    @Update("""
            UPDATE activity_enrollment_ticket
            SET status = 'CANCELLED',
                cancelled_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{ticketId}
              AND enrollment_id = #{enrollmentId}
              AND status = 'ACTIVE'
            """)
    int cancelTicket(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("ticketId") UUID ticketId,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE activity_enrollment e
            SET status = 'CANCELLED',
                cancelled_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE e.id = #{enrollmentId}
              AND e.status = 'CONFIRMED'
              AND NOT EXISTS (
                  SELECT 1
                  FROM activity_enrollment_ticket t
                  WHERE t.enrollment_id = e.id
                    AND t.status <> 'CANCELLED'
              )
            """)
    int cancelEnrollmentIfEmpty(
            @Param("enrollmentId") UUID enrollmentId,
            @Param("at") OffsetDateTime at
    );

    // ---- 核销 ----

    /** 只找得到本机构主办或承办的活动的凭证；别的机构的凭证当作不存在。 */
    @Select("""
            SELECT t.id ticket_id,
                   t.enrollment_id,
                   t.ticket_code,
                   t.participant_name,
                   t.status,
                   t.checked_in_at,
                   a.title activity_title,
                   s.label session_label,
                   s.starts_at,
                   s.ends_at,
                   s.check_in_opens_at
            FROM activity_enrollment_ticket t
            JOIN activity_session s ON s.id = t.session_id
            JOIN activity a ON a.id = s.activity_id
            WHERE t.ticket_code = #{ticketCode}
              AND (a.organizer_id = #{organizationId} OR a.co_organizer_id = #{organizationId})
            FOR UPDATE OF t
            """)
    CheckinRow lockTicketForOrganizer(
            @Param("ticketCode") String ticketCode,
            @Param("organizationId") UUID organizationId
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

    record ParticipantRow(
            UUID id,
            String relation,
            String name,
            LocalDate birthMonth,
            boolean photoUseAuthorized
    ) {
    }

    record SessionRow(
            UUID sessionId,
            UUID activityId,
            String activityStatus,
            String priceMode,
            Integer minAge,
            Integer maxAge,
            boolean waitlistAllowed,
            String sessionStatus,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime enrollmentClosesAt,
            int capacity,
            int enrolledCount
    ) {
    }

    record EnrollmentKeyRow(UUID id, String requestHash) {
    }

    record EnrollmentRow(
            UUID enrollmentId,
            String status,
            OffsetDateTime createdAt,
            UUID activityId,
            String activityType,
            String title,
            String coverUrl,
            String cityCode,
            String organizerName,
            String venueName,
            String venueAddress,
            Double latitude,
            Double longitude,
            UUID sessionId,
            String sessionLabel,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt
    ) {
    }

    record TicketRow(
            UUID ticketId,
            UUID enrollmentId,
            UUID participantId,
            String participantName,
            String participantRelation,
            Integer participantAge,
            String ticketCode,
            String status,
            OffsetDateTime checkedInAt,
            OffsetDateTime cancelledAt
    ) {
    }

    record CheckinRow(
            UUID ticketId,
            UUID enrollmentId,
            String ticketCode,
            String participantName,
            String status,
            OffsetDateTime checkedInAt,
            String activityTitle,
            String sessionLabel,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt
    ) {
    }
}
