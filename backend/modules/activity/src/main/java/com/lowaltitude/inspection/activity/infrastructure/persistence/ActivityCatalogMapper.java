package com.lowaltitude.inspection.activity.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 活动目录只读查询。所有条件都用绑定参数；“当前时间”由调用方传入，便于测试，也保证同一次请求里列表和计数用同一时刻。
 */
public interface ActivityCatalogMapper {

    /** 列表和计数共用的条件：已发布、属于该城市、至少还有一场未开始。 */
    String LISTED_ACTIVITY_FILTER = """
            WHERE a.status = 'PUBLISHED'
              AND a.city_code = #{cityCode}
              AND (#{type} = 'ALL' OR a.type = #{type})
              AND (#{freeOnly} = false OR a.price_mode = 'FREE')
              AND EXISTS (
                  SELECT 1
                  FROM activity_session upcoming
                  WHERE upcoming.activity_id = a.id
                    AND upcoming.status = 'ACTIVE'
                    AND upcoming.starts_at > #{now}
              )
              AND (#{enrollingOnly} = false OR EXISTS (
                  SELECT 1
                  FROM activity_session open_session
                  WHERE open_session.activity_id = a.id
                    AND open_session.status = 'ACTIVE'
                    AND open_session.enrollment_closes_at > #{now}
                    AND open_session.enrolled_count < open_session.capacity
              ))
              AND (#{filterByDate} = false OR EXISTS (
                  SELECT 1
                  FROM activity_session on_date
                  WHERE on_date.activity_id = a.id
                    AND on_date.status = 'ACTIVE'
                    AND on_date.starts_at > #{now}
                    AND on_date.starts_at >= #{dateStart}
                    AND on_date.starts_at < #{dateEnd}
              ))
            """;

    @Select("""
            SELECT count(*)
            FROM activity a
            """ + LISTED_ACTIVITY_FILTER)
    long countListed(
            @Param("cityCode") String cityCode,
            @Param("type") String type,
            @Param("freeOnly") boolean freeOnly,
            @Param("enrollingOnly") boolean enrollingOnly,
            @Param("filterByDate") boolean filterByDate,
            @Param("dateStart") OffsetDateTime dateStart,
            @Param("dateEnd") OffsetDateTime dateEnd,
            @Param("now") OffsetDateTime now
    );

    @Select("""
            SELECT a.id activity_id,
                   a.type,
                   a.title,
                   a.cover_url,
                   a.venue_name,
                   a.schedule_note,
                   a.price_mode,
                   a.price_amount_minor,
                   a.currency,
                   a.min_age,
                   a.max_age,
                   a.waitlist_allowed,
                   next_session.id next_session_id,
                   next_session.starts_at next_starts_at,
                   next_session.capacity next_capacity,
                   next_session.enrolled_count next_enrolled_count,
                   EXISTS (
                       SELECT 1
                       FROM activity_session open_session
                       WHERE open_session.activity_id = a.id
                         AND open_session.status = 'ACTIVE'
                         AND open_session.enrollment_closes_at > #{now}
                         AND open_session.enrolled_count < open_session.capacity
                   ) has_open_session,
                   EXISTS (
                       SELECT 1
                       FROM activity_session full_session
                       WHERE full_session.activity_id = a.id
                         AND full_session.status = 'ACTIVE'
                         AND full_session.enrollment_closes_at > #{now}
                         AND full_session.enrolled_count >= full_session.capacity
                   ) has_full_session,
                   a.organizer_id
            FROM activity a
            JOIN LATERAL (
                SELECT s.id, s.starts_at, s.capacity, s.enrolled_count
                FROM activity_session s
                WHERE s.activity_id = a.id
                  AND s.status = 'ACTIVE'
                  AND s.starts_at > #{now}
                -- 卡片展示的那一场：优先取还能报名的最近一场，都报不了时才取最近一场
                ORDER BY (s.enrollment_closes_at > #{now} AND s.enrolled_count < s.capacity) DESC,
                         s.starts_at ASC,
                         s.id ASC
                LIMIT 1
            ) next_session ON true
            """ + LISTED_ACTIVITY_FILTER + """
            ORDER BY next_session.starts_at ASC, a.id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ActivityCardRow> findListedPage(
            @Param("cityCode") String cityCode,
            @Param("type") String type,
            @Param("freeOnly") boolean freeOnly,
            @Param("enrollingOnly") boolean enrollingOnly,
            @Param("filterByDate") boolean filterByDate,
            @Param("dateStart") OffsetDateTime dateStart,
            @Param("dateEnd") OffsetDateTime dateEnd,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT DISTINCT (s.starts_at AT TIME ZONE #{zoneId})::date session_date
            FROM activity_session s
            JOIN activity a ON a.id = s.activity_id
            WHERE a.status = 'PUBLISHED'
              AND a.city_code = #{cityCode}
              AND (#{type} = 'ALL' OR a.type = #{type})
              AND s.status = 'ACTIVE'
              AND s.starts_at > #{now}
              AND s.starts_at >= #{rangeStart}
              AND s.starts_at < #{rangeEnd}
            ORDER BY session_date ASC
            """)
    List<LocalDate> findSessionDates(
            @Param("cityCode") String cityCode,
            @Param("type") String type,
            @Param("zoneId") String zoneId,
            @Param("rangeStart") OffsetDateTime rangeStart,
            @Param("rangeEnd") OffsetDateTime rangeEnd,
            @Param("now") OffsetDateTime now
    );

    @Select("""
            SELECT a.id activity_id,
                   a.type,
                   a.title,
                   a.subtitle,
                   a.cover_url,
                   a.price_mode,
                   a.price_amount_minor,
                   a.currency,
                   a.min_age,
                   a.max_age,
                   organizer.id organizer_id,
                   organizer.organization_name organizer_name,
                   co_organizer.id co_organizer_id,
                   co_organizer.organization_name co_organizer_name,
                   a.venue_name,
                   a.venue_address,
                   ST_Y(a.venue_location::geometry) latitude,
                   ST_X(a.venue_location::geometry) longitude,
                   a.schedule_note,
                   a.eligibility_note,
                   a.seat_note,
                   a.refund_note,
                   a.waitlist_allowed,
                   a.team_enrollment_allowed
            FROM activity a
            JOIN identity_organization organizer ON organizer.id = a.organizer_id
            LEFT JOIN identity_organization co_organizer ON co_organizer.id = a.co_organizer_id
            WHERE a.id = #{activityId}
              AND a.city_code = #{cityCode}
              AND a.status = 'PUBLISHED'
            """)
    Optional<ActivityDetailRow> findPublishedDetail(
            @Param("activityId") UUID activityId,
            @Param("cityCode") String cityCode
    );

    @Select("""
            SELECT s.id session_id,
                   s.label,
                   s.starts_at,
                   s.ends_at,
                   s.check_in_opens_at,
                   s.enrollment_closes_at,
                   s.capacity,
                   s.enrolled_count
            FROM activity_session s
            WHERE s.activity_id = #{activityId}
              AND s.status = 'ACTIVE'
            ORDER BY s.starts_at ASC, s.id ASC
            """)
    List<ActivitySessionRow> findActiveSessions(@Param("activityId") UUID activityId);

    record ActivityCardRow(
            UUID activityId,
            String type,
            String title,
            String coverUrl,
            String venueName,
            String scheduleNote,
            String priceMode,
            Long priceAmountMinor,
            String currency,
            Integer minAge,
            Integer maxAge,
            boolean waitlistAllowed,
            UUID nextSessionId,
            OffsetDateTime nextStartsAt,
            int nextCapacity,
            int nextEnrolledCount,
            boolean hasOpenSession,
            boolean hasFullSession,
            /** 主办机构：卡片上的 price.onlinePayment 要看它能不能在线收款（E09）。 */
            UUID organizerId
    ) {
    }

    record ActivityDetailRow(
            UUID activityId,
            String type,
            String title,
            String subtitle,
            String coverUrl,
            String priceMode,
            Long priceAmountMinor,
            String currency,
            Integer minAge,
            Integer maxAge,
            UUID organizerId,
            String organizerName,
            UUID coOrganizerId,
            String coOrganizerName,
            String venueName,
            String venueAddress,
            Double latitude,
            Double longitude,
            String scheduleNote,
            String eligibilityNote,
            String seatNote,
            String refundNote,
            boolean waitlistAllowed,
            boolean teamEnrollmentAllowed
    ) {
    }

    record ActivitySessionRow(
            UUID sessionId,
            String label,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt,
            OffsetDateTime enrollmentClosesAt,
            int capacity,
            int enrolledCount
    ) {
    }
}
