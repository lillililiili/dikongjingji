package com.lowaltitude.inspection.activity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 候补（S5-3，迁移 V006）。全部用绑定参数；客户读写的每条语句都以所属账号为条件。
 * 转正、超时、结束都在调用方锁住场次那一行之后执行，名额的增减也在锁内。“当前时间”由调用方传入。
 */
public interface ActivityWaitlistMapper {

    // ---- 名额（调用方已锁住场次） ----

    @Select("""
            SELECT s.id session_id,
                   s.capacity,
                   s.enrolled_count,
                   s.starts_at,
                   a.status = 'PUBLISHED' AND s.status = 'ACTIVE' AND s.sale_status <> 'STOPPED'
                       AND NOT EXISTS (
                           SELECT 1
                           FROM identity_organization suspended
                           WHERE suspended.id = a.organizer_id
                             AND suspended.listing_suspended
                       ) open_for_offers,
                   a.title,
                   a.price_mode,
                   a.price_amount_minor
            FROM activity_session s
            JOIN activity a ON a.id = s.activity_id
            WHERE s.id = #{sessionId}
            """)
    SessionSeatsRow findSessionSeats(@Param("sessionId") UUID sessionId);

    @Update("""
            UPDATE activity_session
            SET enrolled_count = enrolled_count + #{delta},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{sessionId}
            """)
    int addEnrolled(@Param("sessionId") UUID sessionId, @Param("delta") int delta, @Param("at") OffsetDateTime at);

    /** 这一场还在排队的，按加入先后。 */
    @Select("""
            SELECT id, account_id, people, seq
            FROM activity_waitlist
            WHERE session_id = #{sessionId}
              AND status = 'WAITING'
            ORDER BY seq ASC
            """)
    List<QueueRow> findQueue(@Param("sessionId") UUID sessionId);

    @Update("""
            UPDATE activity_waitlist
            SET status = 'OFFERED',
                offered_at = #{at},
                offer_expires_at = #{expiresAt},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{waitlistId}
              AND status = 'WAITING'
            """)
    int offer(
            @Param("waitlistId") UUID waitlistId,
            @Param("at") OffsetDateTime at,
            @Param("expiresAt") OffsetDateTime expiresAt
    );

    // ---- 加入 ----

    @Select("""
            SELECT id, request_hash
            FROM activity_waitlist
            WHERE account_id = #{accountId}
              AND idempotency_key = #{idempotencyKey}
            """)
    ActivityEnrollmentMapper.EnrollmentKeyRow findByKey(
            @Param("accountId") UUID accountId,
            @Param("idempotencyKey") UUID idempotencyKey
    );

    /** 该账号名下、在这一场还在排队或已转正（还没建报名单）的参与人。 */
    @Select("""
            SELECT m.participant_id
            FROM activity_waitlist_member m
            JOIN activity_waitlist w ON w.id = m.waitlist_id
            WHERE w.session_id = #{sessionId}
              AND w.account_id = #{accountId}
              AND w.status IN ('WAITING', 'OFFERED')
            """)
    List<UUID> findWaitingParticipantIds(@Param("sessionId") UUID sessionId, @Param("accountId") UUID accountId);

    @Select("""
            INSERT INTO activity_waitlist (
                account_id, activity_id, session_id, idempotency_key, request_hash, people,
                unit_amount_minor, notice_agreed_at, created_at, updated_at
            )
            VALUES (
                #{accountId}, #{activityId}, #{sessionId}, #{idempotencyKey}, #{requestHash}, #{people},
                #{unitAmountMinor}, #{at}, #{at}, #{at}
            )
            RETURNING id
            """)
    UUID insertWaitlist(
            @Param("accountId") UUID accountId,
            @Param("activityId") UUID activityId,
            @Param("sessionId") UUID sessionId,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("people") int people,
            @Param("unitAmountMinor") Long unitAmountMinor,
            @Param("at") OffsetDateTime at
    );

    @Insert("""
            INSERT INTO activity_waitlist_member (
                waitlist_id, participant_id, participant_name, participant_relation, participant_age
            )
            VALUES (#{waitlistId}, #{participantId}, #{name}, #{relation}, #{age})
            """)
    int insertMember(
            @Param("waitlistId") UUID waitlistId,
            @Param("participantId") UUID participantId,
            @Param("name") String name,
            @Param("relation") String relation,
            @Param("age") Integer age
    );

    // ---- 读 ----

    String WAITLIST_COLUMNS = """
            SELECT w.id waitlist_id,
                   w.account_id,
                   w.status,
                   w.seq,
                   w.people,
                   w.unit_amount_minor,
                   w.offered_at,
                   w.offer_expires_at,
                   w.enrollment_id,
                   w.ended_at,
                   w.created_at,
                   a.id activity_id,
                   a.type activity_type,
                   a.title,
                   a.cover_url,
                   a.city_code,
                   a.price_mode,
                   a.price_amount_minor,
                   organizer.organization_name organizer_name,
                   a.venue_name,
                   s.id session_id,
                   s.label session_label,
                   s.starts_at,
                   s.ends_at,
                   e.status enrollment_status,
                   e.trade_order_id,
                   e.amount_minor,
                   e.pay_before,
                   (SELECT count(*)
                    FROM activity_waitlist ahead
                    WHERE ahead.session_id = w.session_id
                      AND ahead.status = 'WAITING'
                      AND ahead.seq < w.seq) ahead
            FROM activity_waitlist w
            JOIN activity a ON a.id = w.activity_id
            JOIN activity_session s ON s.id = w.session_id
            JOIN identity_organization organizer ON organizer.id = a.organizer_id
            LEFT JOIN activity_enrollment e ON e.id = w.enrollment_id
            WHERE w.account_id = #{accountId}
            """;

    @Select(WAITLIST_COLUMNS + """
              AND w.id = #{waitlistId}
            """)
    WaitlistRow findWaitlist(@Param("accountId") UUID accountId, @Param("waitlistId") UUID waitlistId);

    /**
     * active：还在排队、已转正待确认 / 待付款的（“我的报名”待参加页签上方的候补卡）；否则全部，最近加入的在前。
     */
    @Select(WAITLIST_COLUMNS + """
              AND (#{active} = false
                   OR w.status IN ('WAITING', 'OFFERED')
                   OR (w.status = 'ENROLLED' AND e.status = 'PENDING_PAYMENT'))
            ORDER BY w.created_at DESC, w.id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<WaitlistRow> findWaitlistPage(
            @Param("accountId") UUID accountId,
            @Param("active") boolean active,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT count(*)
            FROM activity_waitlist w
            LEFT JOIN activity_enrollment e ON e.id = w.enrollment_id
            WHERE w.account_id = #{accountId}
              AND (#{active} = false
                   OR w.status IN ('WAITING', 'OFFERED')
                   OR (w.status = 'ENROLLED' AND e.status = 'PENDING_PAYMENT'))
            """)
    long countWaitlist(@Param("accountId") UUID accountId, @Param("active") boolean active);

    @Select("""
            <script>
            SELECT waitlist_id, participant_id, participant_name, participant_relation, participant_age
            FROM activity_waitlist_member
            WHERE waitlist_id IN
            <foreach collection="waitlistIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY (participant_relation = 'SELF') DESC, participant_name ASC, participant_id ASC
            </script>
            """)
    List<MemberRow> findMembers(@Param("waitlistIds") List<UUID> waitlistIds);

    // ---- 改状态（调用方已锁住场次） ----

    @Select("""
            SELECT id waitlist_id, account_id, session_id, status, people, offer_expires_at, enrollment_id
            FROM activity_waitlist
            WHERE id = #{waitlistId}
            FOR UPDATE
            """)
    WaitlistLockRow lockWaitlist(@Param("waitlistId") UUID waitlistId);

    /** 不加锁，用于先找到场次再按“先锁场次、再锁候补”的顺序加锁。 */
    @Select("""
            SELECT id waitlist_id, account_id, session_id, status, people, offer_expires_at, enrollment_id
            FROM activity_waitlist
            WHERE id = #{waitlistId}
              AND account_id = #{accountId}
            """)
    WaitlistLockRow findOwnWaitlist(@Param("accountId") UUID accountId, @Param("waitlistId") UUID waitlistId);

    /** 放弃：排队中、已转正的直接放弃；已经建了待付款报名单的，由调用方先关掉报名单再放弃。 */
    @Update("""
            UPDATE activity_waitlist
            SET status = 'ABANDONED',
                ended_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{waitlistId}
              AND status IN ('WAITING', 'OFFERED', 'ENROLLED')
            """)
    int abandon(@Param("waitlistId") UUID waitlistId, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE activity_waitlist
            SET status = 'ENROLLED',
                enrollment_id = #{enrollmentId},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{waitlistId}
              AND status = 'OFFERED'
            """)
    int markEnrolled(
            @Param("waitlistId") UUID waitlistId,
            @Param("enrollmentId") UUID enrollmentId,
            @Param("at") OffsetDateTime at
    );

    // ---- 超时与结束（定时任务） ----

    /** 过了保留时间还没确认 / 去付款的转正，每次一批。 */
    @Select("""
            SELECT id waitlist_id, account_id, session_id, status, people, offer_expires_at, enrollment_id
            FROM activity_waitlist
            WHERE status = 'OFFERED'
              AND offer_expires_at <= #{now}
            ORDER BY offer_expires_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<WaitlistLockRow> findExpiredOffers(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE activity_waitlist
            SET status = 'EXPIRED',
                ended_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{waitlistId}
              AND status = 'OFFERED'
              AND offer_expires_at <= #{at}
            """)
    int expireOffer(@Param("waitlistId") UUID waitlistId, @Param("at") OffsetDateTime at);

    /** 场次已经开始还在排队的候补结束。不动名额（排队的不占名额）。 */
    @Update("""
            UPDATE activity_waitlist w
            SET status = 'ENDED',
                ended_at = #{now},
                version = w.version + 1,
                updated_at = #{now}
            FROM activity_session s
            WHERE s.id = w.session_id
              AND w.status = 'WAITING'
              AND s.starts_at <= #{now}
            """)
    int endStartedWaiting(@Param("now") OffsetDateTime now);

    record SessionSeatsRow(
            UUID sessionId,
            int capacity,
            int enrolledCount,
            OffsetDateTime startsAt,
            /** 活动在架、场次启用、没停售。 */
            boolean openForOffers,
            String title,
            String priceMode,
            Long priceAmountMinor
    ) {
    }

    record QueueRow(UUID id, UUID accountId, int people, long seq) {
    }

    record WaitlistLockRow(
            UUID waitlistId,
            UUID accountId,
            UUID sessionId,
            String status,
            int people,
            OffsetDateTime offerExpiresAt,
            UUID enrollmentId
    ) {
    }

    record MemberRow(
            UUID waitlistId,
            UUID participantId,
            String participantName,
            String participantRelation,
            Integer participantAge
    ) {
    }

    record WaitlistRow(
            UUID waitlistId,
            UUID accountId,
            String status,
            long seq,
            int people,
            Long unitAmountMinor,
            OffsetDateTime offeredAt,
            OffsetDateTime offerExpiresAt,
            UUID enrollmentId,
            OffsetDateTime endedAt,
            OffsetDateTime createdAt,
            UUID activityId,
            String activityType,
            String title,
            String coverUrl,
            String cityCode,
            String priceMode,
            Long priceAmountMinor,
            String organizerName,
            String venueName,
            UUID sessionId,
            String sessionLabel,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            /** 以下四项只有建了报名单之后才有。 */
            String enrollmentStatus,
            UUID tradeOrderId,
            Long amountMinor,
            OffsetDateTime payBefore,
            /** 同一场排在前面、还在排队的条数。 */
            long ahead
    ) {
    }
}
