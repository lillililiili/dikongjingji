package com.lowaltitude.inspection.activity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运营端“活动与场次”（S3-1，契约 contracts/openapi-ops-activity.yaml 1.0.0）的读写。全部用绑定参数；
 * 改活动、改场次都带版本号（WHERE version = #{version}），场次的改、删、停售先锁住场次行（和报名同一把锁），
 * 名额和报名数在锁内判断。已删除（DELETED）的活动当作不存在。不读身份模块的表：机构名称由服务经端口取。
 */
public interface ActivityOpsMapper {

    String LIST_FILTER = """
            WHERE a.status <> 'DELETED'
              AND (CAST(#{keyword} AS varchar) IS NULL OR a.title ILIKE '%' || #{keyword} || '%'
                   OR a.venue_name ILIKE '%' || #{keyword} || '%')
              AND (CAST(#{cityCode} AS varchar) IS NULL OR a.city_code = #{cityCode})
              AND (CAST(#{type} AS varchar) IS NULL OR a.type = #{type})
              AND (CAST(#{status} AS varchar) IS NULL OR a.status = #{status})
            """;

    String ACTIVITY_COLUMNS = """
            SELECT a.id, a.type, a.title, a.subtitle, a.cover_url, a.organizer_id, a.co_organizer_id, a.city_code,
                   a.venue_name, a.venue_address, a.schedule_note, a.price_mode, a.price_amount_minor,
                   a.min_age::integer min_age, a.max_age::integer max_age, a.eligibility_note, a.seat_note,
                   a.refund_note, a.enrollment_notice, a.status, a.first_published_at, a.version, a.created_at,
                   a.updated_at,
                   EXISTS (
                       SELECT 1 FROM activity_enrollment e
                       WHERE e.activity_id = a.id AND e.status IN ('PENDING_PAYMENT', 'CONFIRMED')
                   ) has_enrollments
            FROM activity a
            """;

    @Select("SELECT count(*) FROM activity a " + LIST_FILTER)
    long count(
            @Param("keyword") String keyword,
            @Param("cityCode") String cityCode,
            @Param("type") String type,
            @Param("status") String status
    );

    @Select("""
            SELECT a.id, a.type, a.title, a.city_code, a.organizer_id, a.status, a.price_mode, a.price_amount_minor,
                   (SELECT count(*) FROM activity_session s
                    WHERE s.activity_id = a.id AND s.status = 'ACTIVE')::integer session_count,
                   (SELECT min(s.starts_at) FROM activity_session s
                    WHERE s.activity_id = a.id AND s.status = 'ACTIVE' AND s.starts_at > #{now}) next_starts_at,
                   (SELECT coalesce(sum(s.enrolled_count), 0) FROM activity_session s
                    WHERE s.activity_id = a.id AND s.status = 'ACTIVE')::integer enrolled_count,
                   a.updated_at, a.version
            FROM activity a
            """ + LIST_FILTER + """
            ORDER BY a.updated_at DESC, a.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SummaryRow> page(
            @Param("keyword") String keyword,
            @Param("cityCode") String cityCode,
            @Param("type") String type,
            @Param("status") String status,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select(ACTIVITY_COLUMNS + """
            WHERE a.id = #{activityId}
              AND a.status <> 'DELETED'
            """)
    ActivityRow find(@Param("activityId") UUID activityId);

    @Select("""
            SELECT g.url FROM activity_gallery_image g
            WHERE g.activity_id = #{activityId}
            ORDER BY g.sort_order ASC
            """)
    List<String> gallery(@Param("activityId") UUID activityId);

    String SESSION_COLUMNS = """
            SELECT s.id, s.activity_id, s.label, s.starts_at, s.ends_at, s.check_in_opens_at, s.enrollment_closes_at,
                   s.capacity, s.enrolled_count, s.status, s.sale_status, s.version,
                   (SELECT count(*) FROM activity_enrollment e WHERE e.session_id = s.id) enrollment_count
            FROM activity_session s
            """;

    @Select(SESSION_COLUMNS + """
            WHERE s.activity_id = #{activityId}
            ORDER BY s.starts_at ASC, s.id ASC
            """)
    List<SessionRow> sessions(@Param("activityId") UUID activityId);

    /** 改、删、停售一个场次前先锁住它：报名也先锁场次，两边不会同时动名额。 */
    @Select(SESSION_COLUMNS + """
            WHERE s.id = #{sessionId}
              AND s.activity_id = #{activityId}
            FOR UPDATE OF s
            """)
    SessionRow lockSession(@Param("activityId") UUID activityId, @Param("sessionId") UUID sessionId);

    @Insert("""
            INSERT INTO activity (id, organizer_id, co_organizer_id, type, title, subtitle, cover_url, city_code,
                                  venue_name, venue_address, schedule_note, price_mode, price_amount_minor, currency,
                                  min_age, max_age, eligibility_note, seat_note, refund_note, enrollment_notice,
                                  status, version, created_at, updated_at)
            VALUES (#{id}, #{d.organizerId}, #{d.coOrganizerId}, #{d.type}, #{d.title}, #{d.subtitle}, #{d.coverUrl},
                    #{d.cityCode}, #{d.venueName}, #{d.venueAddress}, #{d.scheduleNote}, #{d.priceMode},
                    #{d.priceAmountMinor}, 'CNY', #{d.minAge}, #{d.maxAge}, #{d.eligibilityNote}, #{d.seatNote},
                    #{d.refundNote}, #{d.enrollmentNotice}, 'DRAFT', 0, #{at}, #{at})
            """)
    int insert(@Param("id") UUID id, @Param("d") ActivityValues values, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE activity
            SET organizer_id = #{d.organizerId}, co_organizer_id = #{d.coOrganizerId}, type = #{d.type},
                title = #{d.title}, subtitle = #{d.subtitle}, cover_url = #{d.coverUrl}, city_code = #{d.cityCode},
                venue_name = #{d.venueName}, venue_address = #{d.venueAddress}, schedule_note = #{d.scheduleNote},
                price_mode = #{d.priceMode}, price_amount_minor = #{d.priceAmountMinor}, min_age = #{d.minAge},
                max_age = #{d.maxAge}, eligibility_note = #{d.eligibilityNote}, seat_note = #{d.seatNote},
                refund_note = #{d.refundNote}, enrollment_notice = #{d.enrollmentNotice},
                version = version + 1, updated_at = #{at}
            WHERE id = #{id}
              AND version = #{version}
              AND status <> 'DELETED'
            """)
    int update(@Param("id") UUID id, @Param("d") ActivityValues values, @Param("version") int version,
               @Param("at") OffsetDateTime at);

    /** 上线时记第一次上线的时间（以后不改）。 */
    @Update("""
            UPDATE activity
            SET status = #{status},
                first_published_at = CASE WHEN #{status} = 'PUBLISHED' THEN coalesce(first_published_at, #{at})
                                          ELSE first_published_at END,
                version = version + 1, updated_at = #{at}
            WHERE id = #{id}
              AND version = #{version}
              AND status <> 'DELETED'
            """)
    int changeStatus(@Param("id") UUID id, @Param("status") String status, @Param("version") int version,
                     @Param("at") OffsetDateTime at);

    @Delete("DELETE FROM activity_gallery_image WHERE activity_id = #{activityId}")
    int clearGallery(@Param("activityId") UUID activityId);

    @Insert("""
            INSERT INTO activity_gallery_image (activity_id, sort_order, url, created_at)
            VALUES (#{activityId}, #{sortOrder}, #{url}, #{at})
            """)
    int insertGalleryImage(@Param("activityId") UUID activityId, @Param("sortOrder") int sortOrder,
                           @Param("url") String url, @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO activity_session (id, activity_id, label, starts_at, ends_at, check_in_opens_at,
                                          enrollment_closes_at, capacity, enrolled_count, status, sale_status,
                                          version, created_at, updated_at)
            VALUES (#{id}, #{activityId}, #{s.label}, #{s.startsAt}, #{s.endsAt}, #{s.checkInOpensAt},
                    #{s.enrollmentClosesAt}, #{s.capacity}, 0, 'ACTIVE', 'ON_SALE', 0, #{at}, #{at})
            """)
    int insertSession(@Param("id") UUID id, @Param("activityId") UUID activityId, @Param("s") SessionValues values,
                      @Param("at") OffsetDateTime at);

    /** 名额不能少于已报名数（报名也会改 enrolled_count 和版本号，所以带版本号就够；这里再写一遍防御）。 */
    @Update("""
            UPDATE activity_session
            SET label = #{s.label}, starts_at = #{s.startsAt}, ends_at = #{s.endsAt},
                check_in_opens_at = #{s.checkInOpensAt}, enrollment_closes_at = #{s.enrollmentClosesAt},
                capacity = #{s.capacity}, version = version + 1, updated_at = #{at}
            WHERE id = #{id}
              AND version = #{version}
              AND enrolled_count <= #{s.capacity}
            """)
    int updateSession(@Param("id") UUID id, @Param("s") SessionValues values, @Param("version") int version,
                      @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE activity_session
            SET sale_status = #{saleStatus}, version = version + 1, updated_at = #{at}
            WHERE id = #{id}
              AND version = #{version}
            """)
    int changeSaleStatus(@Param("id") UUID id, @Param("saleStatus") String saleStatus,
                         @Param("version") int version, @Param("at") OffsetDateTime at);

    /** 只删没有任何报名单的场次（报名单对场次是 RESTRICT 外键，这里先按计数拦下）。 */
    @Delete("""
            DELETE FROM activity_session s
            WHERE s.id = #{id}
              AND s.version = #{version}
              AND NOT EXISTS (SELECT 1 FROM activity_enrollment e WHERE e.session_id = s.id)
            """)
    int deleteSession(@Param("id") UUID id, @Param("version") int version);

    /** 场次增删改时把活动的更新时间往前推（列表按它排），不动活动的版本号。 */
    @Update("UPDATE activity SET updated_at = #{at} WHERE id = #{id}")
    int touch(@Param("id") UUID id, @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO activity_ops_change (activity_id, subject_type, subject_id, action, summary, operator_id,
                                             operator_name, changed_at)
            VALUES (#{activityId}, #{subjectType}, #{subjectId}, #{action}, #{summary}, #{operatorId},
                    #{operatorName}, #{at})
            """)
    int insertChange(@Param("activityId") UUID activityId, @Param("subjectType") String subjectType,
                     @Param("subjectId") UUID subjectId, @Param("action") String action,
                     @Param("summary") String summary, @Param("operatorId") UUID operatorId,
                     @Param("operatorName") String operatorName, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT c.id, c.subject_type, c.subject_id, c.action, c.summary, c.operator_name, c.changed_at
            FROM activity_ops_change c
            WHERE c.activity_id = #{activityId}
            ORDER BY c.changed_at DESC, c.id DESC
            LIMIT #{limit}
            """)
    List<ChangeRow> changes(@Param("activityId") UUID activityId, @Param("limit") int limit);

    /** 写进表的活动字段（已校验、已整理）。 */
    record ActivityValues(
            String type,
            String title,
            String subtitle,
            String coverUrl,
            UUID organizerId,
            UUID coOrganizerId,
            String cityCode,
            String venueName,
            String venueAddress,
            String scheduleNote,
            String priceMode,
            Long priceAmountMinor,
            Integer minAge,
            Integer maxAge,
            String eligibilityNote,
            String seatNote,
            String refundNote,
            String enrollmentNotice
    ) {
    }

    record SessionValues(
            String label,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt,
            OffsetDateTime enrollmentClosesAt,
            int capacity
    ) {
    }

    record ActivityRow(
            UUID id,
            String type,
            String title,
            String subtitle,
            String coverUrl,
            UUID organizerId,
            UUID coOrganizerId,
            String cityCode,
            String venueName,
            String venueAddress,
            String scheduleNote,
            String priceMode,
            Long priceAmountMinor,
            Integer minAge,
            Integer maxAge,
            String eligibilityNote,
            String seatNote,
            String refundNote,
            String enrollmentNotice,
            String status,
            OffsetDateTime firstPublishedAt,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            /** 有待付款或已确认的报名单。 */
            boolean hasEnrollments
    ) {
    }

    record SummaryRow(
            UUID id,
            String type,
            String title,
            String cityCode,
            UUID organizerId,
            String status,
            String priceMode,
            Long priceAmountMinor,
            int sessionCount,
            OffsetDateTime nextStartsAt,
            int enrolledCount,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    record SessionRow(
            UUID id,
            UUID activityId,
            String label,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt,
            OffsetDateTime enrollmentClosesAt,
            int capacity,
            int enrolledCount,
            String status,
            String saleStatus,
            int version,
            /** 这一场的报名单数（任何状态，含已取消）。 */
            long enrollmentCount
    ) {
    }

    record ChangeRow(
            UUID id,
            String subjectType,
            UUID subjectId,
            String action,
            String summary,
            String operatorName,
            OffsetDateTime changedAt
    ) {
    }
}
