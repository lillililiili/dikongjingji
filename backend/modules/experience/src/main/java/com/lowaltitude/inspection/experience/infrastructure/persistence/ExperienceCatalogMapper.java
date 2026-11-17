package com.lowaltitude.inspection.experience.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ExperienceRows.ExperienceItemRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueCardRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueDeviceRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueInfoRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueProjectRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueSessionRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 体验项目与场馆的只读查询。所有条件都用绑定参数；“当前时间”由调用方传入。 */
public interface ExperienceCatalogMapper {

    @Select("""
            SELECT count(*)
            FROM experience_item
            WHERE status = 'PUBLISHED'
              AND (#{type} = 'ALL' OR type = #{type})
            """)
    long countExperienceItems(@Param("type") String type);

    @Select("""
            SELECT id experience_id,
                   type,
                   title,
                   summary,
                   cover_url,
                   login_required
            FROM experience_item
            WHERE status = 'PUBLISHED'
              AND (#{type} = 'ALL' OR type = #{type})
            ORDER BY sort_order ASC, id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ExperienceItemRow> findExperienceItems(
            @Param("type") String type,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT count(*)
            FROM venue
            WHERE status = 'PUBLISHED'
              AND city_code = #{cityCode}
            """)
    long countVenues(@Param("cityCode") String cityCode);

    @Select("""
            SELECT v.id venue_id,
                   v.name,
                   v.cover_url,
                   v.opening_note,
                   v.address,
                   (
                       SELECT count(*)
                       FROM venue_session s
                       JOIN venue_project p ON p.id = s.project_id
                       WHERE p.venue_id = v.id
                         AND p.status = 'ACTIVE'
                         AND s.status = 'ACTIVE'
                         AND s.starts_at > #{now}
                         AND s.starts_at < #{endOfToday}
                         AND s.booked_count < s.capacity
                   ) open_sessions_today
            FROM venue v
            WHERE v.status = 'PUBLISHED'
              AND v.city_code = #{cityCode}
            ORDER BY v.sort_order ASC, v.id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<VenueCardRow> findVenues(
            @Param("cityCode") String cityCode,
            @Param("now") OffsetDateTime now,
            @Param("endOfToday") OffsetDateTime endOfToday,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT v.id venue_id,
                   v.name,
                   v.cover_url,
                   v.opening_note,
                   v.address,
                   ST_Y(v.location::geometry) latitude,
                   ST_X(v.location::geometry) longitude,
                   v.phone,
                   operator.id operator_id,
                   operator.organization_name operator_name,
                   (
                       SELECT count(*)
                       FROM venue_device d
                       WHERE d.venue_id = v.id
                         AND d.status = 'ACTIVE'
                   ) device_count
            FROM venue v
            JOIN identity_organization operator ON operator.id = v.operator_id
            WHERE v.id = #{venueId}
              AND v.city_code = #{cityCode}
              AND v.status = 'PUBLISHED'
            """)
    Optional<VenueRow> findVenue(
            @Param("venueId") UUID venueId,
            @Param("cityCode") String cityCode
    );

    @Select("""
            SELECT label, content
            FROM venue_info_item
            WHERE venue_id = #{venueId}
              AND status = 'ACTIVE'
            ORDER BY sort_order ASC, id ASC
            """)
    List<VenueInfoRow> findInfoItems(@Param("venueId") UUID venueId);

    @Select("""
            SELECT id project_id,
                   name,
                   duration_minutes,
                   min_age,
                   capacity,
                   limit_note,
                   price_amount_minor,
                   currency
            FROM venue_project
            WHERE venue_id = #{venueId}
              AND status = 'ACTIVE'
            ORDER BY sort_order ASC, id ASC
            """)
    List<VenueProjectRow> findProjects(@Param("venueId") UUID venueId);

    @Select("""
            SELECT count(*)
            FROM venue_project
            WHERE id = #{projectId}
              AND venue_id = #{venueId}
              AND status = 'ACTIVE'
            """)
    long countActiveProject(
            @Param("venueId") UUID venueId,
            @Param("projectId") UUID projectId
    );

    @Select("""
            SELECT id session_id,
                   starts_at,
                   ends_at,
                   capacity,
                   booked_count
            FROM venue_session
            WHERE project_id = #{projectId}
              AND status = 'ACTIVE'
              AND starts_at >= #{dayStart}
              AND starts_at < #{dayEnd}
            ORDER BY starts_at ASC, id ASC
            """)
    List<VenueSessionRow> findSessions(
            @Param("projectId") UUID projectId,
            @Param("dayStart") OffsetDateTime dayStart,
            @Param("dayEnd") OffsetDateTime dayEnd
    );

    @Select("""
            SELECT count(*)
            FROM venue_device
            WHERE venue_id = #{venueId}
              AND status = 'ACTIVE'
              AND (#{category} = 'ALL' OR category = #{category})
            """)
    long countDevices(
            @Param("venueId") UUID venueId,
            @Param("category") String category
    );

    @Select("""
            SELECT id device_id,
                   category,
                   name,
                   summary,
                   image_url,
                   trial_mode
            FROM venue_device
            WHERE venue_id = #{venueId}
              AND status = 'ACTIVE'
              AND (#{category} = 'ALL' OR category = #{category})
            ORDER BY sort_order ASC, id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<VenueDeviceRow> findDevices(
            @Param("venueId") UUID venueId,
            @Param("category") String category,
            @Param("limit") int limit,
            @Param("offset") int offset
    );
}
