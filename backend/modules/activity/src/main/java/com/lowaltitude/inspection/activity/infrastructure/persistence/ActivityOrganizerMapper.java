package com.lowaltitude.inspection.activity.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 某个主办机构在某城市、还有场次没开始的已上线活动（S5-2 商家主页的“活动”页签；主办机构被挂起上架的不算）。只读，按最近一场的开始时间排。
 */
public interface ActivityOrganizerMapper {

    String UPCOMING = """
            FROM activity a
            JOIN LATERAL (
                SELECT min(s.starts_at) starts_at
                FROM activity_session s
                WHERE s.activity_id = a.id AND s.status = 'ACTIVE' AND s.starts_at > #{now}
            ) next ON next.starts_at IS NOT NULL
            WHERE a.status = 'PUBLISHED'
              AND NOT EXISTS (
                  SELECT 1
                  FROM identity_organization suspended
                  WHERE suspended.id = a.organizer_id
                    AND suspended.listing_suspended
              )
              AND a.city_code = #{cityCode}
              AND (a.organizer_id = #{organizerId} OR a.co_organizer_id = #{organizerId})
            """;

    @Select("SELECT count(*) " + UPCOMING)
    long countUpcoming(@Param("organizerId") UUID organizerId, @Param("cityCode") String cityCode,
                       @Param("now") Instant now);

    @Select("""
            SELECT a.id activity_id, a.title, a.cover_url, a.venue_name, next.starts_at,
                   a.price_mode, a.price_amount_minor
            """ + UPCOMING + """
            ORDER BY next.starts_at ASC, a.id ASC
            LIMIT #{limit}
            """)
    List<UpcomingRow> findUpcoming(@Param("organizerId") UUID organizerId, @Param("cityCode") String cityCode,
                                   @Param("now") Instant now, @Param("limit") int limit);

    record UpcomingRow(UUID activityId, String title, String coverUrl, String venueName, Instant startsAt,
                       String priceMode, Long priceAmountMinor) {
    }
}
