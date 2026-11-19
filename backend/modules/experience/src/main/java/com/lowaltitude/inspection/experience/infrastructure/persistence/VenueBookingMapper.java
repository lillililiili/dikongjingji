package com.lowaltitude.inspection.experience.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 到馆预约的读写（E10）。全部用绑定参数；预约的读写都以账号为条件，核销以场馆运营机构为条件，
 * 付款结果以预约和它的交易订单两个 ID 一起为条件。“当前时间”由调用方传入。
 * 加锁顺序：交易订单（交易模块）→ 场次（改期时两个场次按 ID 从小到大）→ 预约单。
 */
public interface VenueBookingMapper {

    // ---- 场次（加锁） ----

    String SESSION_COLUMNS = """
            SELECT s.id session_id,
                   s.project_id,
                   p.venue_id,
                   v.operator_id,
                   v.status venue_status,
                   p.status project_status,
                   s.status session_status,
                   s.starts_at,
                   s.ends_at,
                   s.capacity,
                   s.booked_count,
                   p.min_age,
                   p.price_amount_minor,
                   v.name venue_name,
                   p.name project_name,
                   v.city_code,
                   v.cover_url
            FROM venue_session s
            JOIN venue_project p ON p.id = s.project_id
            JOIN venue v ON v.id = p.venue_id
            WHERE s.id = #{sessionId}
            """;

    /** 预约、改期、取消、付款结果都先锁住场次这一行，名额增减在锁内完成。 */
    @Select(SESSION_COLUMNS + """
            FOR UPDATE OF s
            """)
    SessionRow lockSession(@Param("sessionId") UUID sessionId);

    /** 不加锁读同样的内容，用于给参与人算能不能约。 */
    @Select(SESSION_COLUMNS)
    SessionRow findSession(@Param("sessionId") UUID sessionId);

    @Update("""
            UPDATE venue_session
            SET booked_count = booked_count + #{delta},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{sessionId}
            """)
    int addBooked(@Param("sessionId") UUID sessionId, @Param("delta") int delta, @Param("at") OffsetDateTime at);

    // ---- 预约单 ----

    @Select("""
            SELECT id, request_hash
            FROM venue_booking
            WHERE account_id = #{accountId}
              AND idempotency_key = #{idempotencyKey}
            """)
    KeyRow findBookingByKey(@Param("accountId") UUID accountId, @Param("idempotencyKey") UUID idempotencyKey);

    /**
     * 这个账号的这些参与人里，在这一场已有有效（待付款也算）预约的。
     */
    @Select("""
            <script>
            SELECT bp.participant_id
            FROM venue_booking_participant bp
            JOIN venue_booking b ON b.id = bp.booking_id
            WHERE bp.session_id = #{sessionId}
              AND bp.active
              AND b.account_id = #{accountId}
              AND bp.participant_id IN
            <foreach collection="participantIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<UUID> findBookedParticipantIds(
            @Param("sessionId") UUID sessionId,
            @Param("accountId") UUID accountId,
            @Param("participantIds") List<UUID> participantIds
    );

    /** ID 由服务先定好：要付款的预约，交易订单的业务单 ID 就是它（交易订单在同一个事务里先建）。 */
    @Insert("""
            INSERT INTO venue_booking (
                id, account_id, venue_id, project_id, session_id, status, code, people,
                unit_amount_minor, amount_minor, trade_order_id, pay_before,
                idempotency_key, request_hash, notice_agreed_at, confirmed_at, created_at, updated_at
            )
            VALUES (
                #{bookingId}, #{accountId}, #{venueId}, #{projectId}, #{sessionId}, #{status}, #{code}, #{people},
                #{unitAmountMinor}, #{amountMinor}, #{tradeOrderId}, #{payBefore},
                #{idempotencyKey}, #{requestHash}, #{at}, #{confirmedAt}, #{at}, #{at}
            )
            """)
    int insertBooking(
            @Param("bookingId") UUID bookingId,
            @Param("accountId") UUID accountId,
            @Param("venueId") UUID venueId,
            @Param("projectId") UUID projectId,
            @Param("sessionId") UUID sessionId,
            @Param("status") String status,
            @Param("code") String code,
            @Param("people") int people,
            @Param("unitAmountMinor") long unitAmountMinor,
            @Param("amountMinor") long amountMinor,
            @Param("tradeOrderId") UUID tradeOrderId,
            @Param("payBefore") OffsetDateTime payBefore,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("confirmedAt") OffsetDateTime confirmedAt,
            @Param("at") OffsetDateTime at
    );

    @Insert("""
            INSERT INTO venue_booking_participant (
                booking_id, session_id, participant_id, participant_name, participant_relation, participant_age,
                created_at, updated_at
            )
            VALUES (
                #{bookingId}, #{sessionId}, #{participantId}, #{name}, #{relation}, #{age}, #{at}, #{at}
            )
            """)
    int insertParticipant(
            @Param("bookingId") UUID bookingId,
            @Param("sessionId") UUID sessionId,
            @Param("participantId") UUID participantId,
            @Param("name") String name,
            @Param("relation") String relation,
            @Param("age") Integer age,
            @Param("at") OffsetDateTime at
    );

    @Insert("""
            INSERT INTO venue_booking_event (
                booking_id, event_type, actor_id, from_session_id, to_session_id, occurred_at
            )
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

    // ---- 读预约单 ----

    String BOOKING_COLUMNS = """
            SELECT b.id booking_id,
                   b.status,
                   b.code,
                   b.people,
                   b.unit_amount_minor,
                   b.amount_minor,
                   b.trade_order_id,
                   b.pay_before,
                   b.refund_id,
                   b.checked_in_at,
                   b.cancelled_at,
                   b.created_at,
                   b.version,
                   v.id venue_id,
                   v.name venue_name,
                   v.address venue_address,
                   v.phone venue_phone,
                   p.id project_id,
                   p.name project_name,
                   p.duration_minutes,
                   s.id session_id,
                   s.starts_at,
                   s.ends_at
            FROM venue_booking b
            JOIN venue v ON v.id = b.venue_id
            JOIN venue_project p ON p.id = b.project_id
            JOIN venue_session s ON s.id = b.session_id
            WHERE b.account_id = #{accountId}
            """;

    /**
     * 待付款和待到馆：待付款的，以及没核销、场次还没结束的；已完成：已核销的，以及场次结束了还没核销的（已过期）；
     * 已取消：付款后取消的、没付款就关闭的。
     */
    String BOOKING_BUCKET = """
              AND (
                  (#{bucket} = 'UPCOMING' AND (b.status = 'PENDING_PAYMENT'
                      OR (b.status = 'CONFIRMED' AND s.ends_at > #{now})))
                  OR (#{bucket} = 'FINISHED' AND (b.status = 'CHECKED_IN'
                      OR (b.status = 'CONFIRMED' AND s.ends_at <= #{now})))
                  OR (#{bucket} = 'CANCELLED' AND b.status IN ('CANCELLED', 'CLOSED'))
              )
            """;

    @Select(BOOKING_COLUMNS + """
              AND b.id = #{bookingId}
            """)
    BookingRow findBooking(@Param("accountId") UUID accountId, @Param("bookingId") UUID bookingId);

    @Select("""
            SELECT count(*)
            FROM venue_booking b
            JOIN venue_session s ON s.id = b.session_id
            WHERE b.account_id = #{accountId}
            """ + BOOKING_BUCKET)
    long countBookings(
            @Param("accountId") UUID accountId,
            @Param("bucket") String bucket,
            @Param("now") OffsetDateTime now
    );

    /** 待付款和待到馆按场次开始时间从近到远，其余两类最近的在前。 */
    @Select(BOOKING_COLUMNS + BOOKING_BUCKET + """
            ORDER BY CASE WHEN #{bucket} = 'UPCOMING' THEN s.starts_at END ASC,
                     CASE WHEN #{bucket} <> 'UPCOMING' THEN s.starts_at END DESC,
                     b.created_at DESC,
                     b.id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<BookingRow> findBookingPage(
            @Param("accountId") UUID accountId,
            @Param("bucket") String bucket,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 一张预约里的人按“本人在前、其余按姓名”排，页面上的顺序才稳定。 */
    @Select("""
            <script>
            SELECT bp.booking_id,
                   bp.participant_id,
                   bp.participant_name,
                   bp.participant_relation,
                   bp.participant_age
            FROM venue_booking_participant bp
            WHERE bp.booking_id IN
            <foreach collection="bookingIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY (bp.participant_relation = 'SELF') DESC, bp.participant_name ASC, bp.id ASC
            </script>
            """)
    List<ParticipantRow> findParticipants(@Param("bookingIds") List<UUID> bookingIds);

    // ---- 预约单的状态（加锁） ----

    String STATE_COLUMNS = """
            SELECT b.id booking_id,
                   b.account_id,
                   b.project_id,
                   b.session_id,
                   b.status,
                   b.people,
                   b.amount_minor,
                   b.trade_order_id,
                   b.version
            FROM venue_booking b
            """;

    @Select(STATE_COLUMNS + """
            WHERE b.id = #{bookingId}
              AND b.account_id = #{accountId}
            FOR UPDATE
            """)
    StateRow lockBooking(@Param("accountId") UUID accountId, @Param("bookingId") UUID bookingId);

    /** 付款结果：预约和它的交易订单两个 ID 都对得上才找得到。先不加锁读，拿到场次后再锁。 */
    @Select(STATE_COLUMNS + """
            WHERE b.id = #{bookingId}
              AND b.trade_order_id = #{tradeOrderId}
            """)
    StateRow findPaidBooking(@Param("bookingId") UUID bookingId, @Param("tradeOrderId") UUID tradeOrderId);

    @Select(STATE_COLUMNS + """
            WHERE b.id = #{bookingId}
              AND b.trade_order_id = #{tradeOrderId}
            FOR UPDATE
            """)
    StateRow lockPaidBooking(@Param("bookingId") UUID bookingId, @Param("tradeOrderId") UUID tradeOrderId);

    /** 付款到账：待付款 → 待到馆，生成核销码。 */
    @Update("""
            UPDATE venue_booking
            SET status = 'CONFIRMED',
                code = #{code},
                confirmed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{bookingId}
              AND status = 'PENDING_PAYMENT'
            """)
    int confirmPayment(@Param("bookingId") UUID bookingId, @Param("code") String code, @Param("at") OffsetDateTime at);

    /** 订单关闭（超时或取消）：待付款 → 已关闭。 */
    @Update("""
            UPDATE venue_booking
            SET status = 'CLOSED',
                closed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{bookingId}
              AND status = 'PENDING_PAYMENT'
            """)
    int closeUnpaid(@Param("bookingId") UUID bookingId, @Param("at") OffsetDateTime at);

    /** 关闭之后才到账、可以恢复：已关闭 → 待到馆，生成核销码。 */
    @Update("""
            UPDATE venue_booking
            SET status = 'CONFIRMED',
                code = #{code},
                confirmed_at = #{at},
                closed_at = NULL,
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{bookingId}
              AND status = 'CLOSED'
            """)
    int restoreClosed(@Param("bookingId") UUID bookingId, @Param("code") String code, @Param("at") OffsetDateTime at);

    /** 取消：待到馆 → 已取消（核销码留着，已经作废）。 */
    @Update("""
            UPDATE venue_booking
            SET status = 'CANCELLED',
                cancelled_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{bookingId}
              AND status = 'CONFIRMED'
            """)
    int cancelConfirmed(@Param("bookingId") UUID bookingId, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE venue_booking
            SET refund_id = #{refundId},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{bookingId}
              AND status = 'CANCELLED'
              AND refund_id IS NULL
            """)
    int setRefund(@Param("bookingId") UUID bookingId, @Param("refundId") UUID refundId, @Param("at") OffsetDateTime at);

    /** 改期：只改仍待到馆、版本也对得上的那一张；换场次、换新码。 */
    @Update("""
            UPDATE venue_booking
            SET session_id = #{sessionId},
                code = #{code},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{bookingId}
              AND status = 'CONFIRMED'
              AND version = #{version}
            """)
    int moveBooking(
            @Param("bookingId") UUID bookingId,
            @Param("sessionId") UUID sessionId,
            @Param("code") String code,
            @Param("version") int version,
            @Param("at") OffsetDateTime at
    );

    // ---- 参与人占位 ----

    @Update("""
            UPDATE venue_booking_participant
            SET active = false,
                version = version + 1,
                updated_at = #{at}
            WHERE booking_id = #{bookingId}
              AND active
            """)
    int releaseParticipants(@Param("bookingId") UUID bookingId, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE venue_booking_participant
            SET active = true,
                version = version + 1,
                updated_at = #{at}
            WHERE booking_id = #{bookingId}
              AND NOT active
            """)
    int holdParticipants(@Param("bookingId") UUID bookingId, @Param("at") OffsetDateTime at);

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

    /**
     * 这张预约的人在 sessionId 这一场另有有效预约的个数（不算这张预约自己）。
     * 关闭后才到账要恢复、改期到另一场之前都要先看它：大于 0 就不行（同一个人同一场只能有一张）。
     */
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

    // ---- 核销 ----

    /** 只找得到本机构运营的场馆的核销码；别的机构的码当作不存在。 */
    @Select("""
            SELECT b.id booking_id,
                   b.code,
                   b.status,
                   b.people,
                   b.checked_in_at,
                   v.name venue_name,
                   p.name project_name,
                   s.starts_at,
                   s.ends_at
            FROM venue_booking b
            JOIN venue v ON v.id = b.venue_id
            JOIN venue_project p ON p.id = b.project_id
            JOIN venue_session s ON s.id = b.session_id
            WHERE b.code = #{code}
              AND v.operator_id = #{organizationId}
            FOR UPDATE OF b
            """)
    CheckinRow lockBookingByCode(@Param("code") String code, @Param("organizationId") UUID organizationId);

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

    // ---- 行 ----

    record SessionRow(
            UUID sessionId,
            UUID projectId,
            UUID venueId,
            UUID operatorId,
            String venueStatus,
            String projectStatus,
            String sessionStatus,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            int capacity,
            int bookedCount,
            Integer minAge,
            long priceAmountMinor,
            String venueName,
            String projectName,
            String cityCode,
            String coverUrl
    ) {
    }

    record KeyRow(UUID id, String requestHash) {
    }

    record BookingRow(
            UUID bookingId,
            String status,
            String code,
            int people,
            long unitAmountMinor,
            long amountMinor,
            UUID tradeOrderId,
            OffsetDateTime payBefore,
            UUID refundId,
            OffsetDateTime checkedInAt,
            OffsetDateTime cancelledAt,
            OffsetDateTime createdAt,
            int version,
            UUID venueId,
            String venueName,
            String venueAddress,
            String venuePhone,
            UUID projectId,
            String projectName,
            int durationMinutes,
            UUID sessionId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt
    ) {
    }

    record ParticipantRow(
            UUID bookingId,
            UUID participantId,
            String participantName,
            String participantRelation,
            Integer participantAge
    ) {
    }

    record StateRow(
            UUID bookingId,
            UUID accountId,
            UUID projectId,
            UUID sessionId,
            String status,
            int people,
            long amountMinor,
            UUID tradeOrderId,
            int version
    ) {
    }

    record CheckinRow(
            UUID bookingId,
            String code,
            String status,
            int people,
            OffsetDateTime checkedInAt,
            String venueName,
            String projectName,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt
    ) {
    }
}
