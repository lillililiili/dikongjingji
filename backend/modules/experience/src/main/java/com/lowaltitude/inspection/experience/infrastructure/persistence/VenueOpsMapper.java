package com.lowaltitude.inspection.experience.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运营端“场馆与设备”（S3-1，契约 contracts/openapi-ops-venue.yaml）的读写。所有条件都用绑定参数；“当前时间”由调用方传入。
 * 场馆运营机构的名称和别的读取一样从 identity_organization 连表取（体验模块原有的做法，只读名称）。
 * 时间列（营业时间）一律按 'HH24:MI' 文本读写。
 */
public interface VenueOpsMapper {

    // ---- 场馆列表 ----

    @Select("""
            SELECT count(*)
            FROM venue v
            WHERE v.status <> 'DELETED'
              AND (CAST(#{status} AS varchar) IS NULL OR v.status = #{status})
              AND (CAST(#{cityCode} AS varchar) IS NULL OR v.city_code = #{cityCode})
              AND (CAST(#{keyword} AS varchar) IS NULL OR v.name ILIKE '%' || CAST(#{keyword} AS varchar) || '%'
                   OR v.address ILIKE '%' || CAST(#{keyword} AS varchar) || '%')
            """)
    long countVenues(@Param("keyword") String keyword, @Param("cityCode") String cityCode,
                     @Param("status") String status);

    @Select("""
            SELECT v.id venue_id,
                   v.name,
                   v.city_code,
                   v.operator_id,
                   operator.organization_name operator_name,
                   v.status,
                   v.sort_order,
                   (SELECT count(*) FROM venue_project p WHERE p.venue_id = v.id AND p.status = 'ACTIVE') project_count,
                   (
                       SELECT count(*)
                       FROM venue_session s
                       JOIN venue_project p ON p.id = s.project_id
                       WHERE p.venue_id = v.id
                         AND p.status = 'ACTIVE'
                         AND s.status = 'ACTIVE'
                         AND s.starts_at > #{now}
                   ) upcoming_session_count,
                   v.updated_at,
                   v.version
            FROM venue v
            JOIN identity_organization operator ON operator.id = v.operator_id
            WHERE v.status <> 'DELETED'
              AND (CAST(#{status} AS varchar) IS NULL OR v.status = #{status})
              AND (CAST(#{cityCode} AS varchar) IS NULL OR v.city_code = #{cityCode})
              AND (CAST(#{keyword} AS varchar) IS NULL OR v.name ILIKE '%' || CAST(#{keyword} AS varchar) || '%'
                   OR v.address ILIKE '%' || CAST(#{keyword} AS varchar) || '%')
            ORDER BY CASE v.status WHEN 'DRAFT' THEN 0 WHEN 'PUBLISHED' THEN 1 ELSE 2 END,
                     v.sort_order ASC, v.id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<VenueSummaryRow> findVenues(@Param("keyword") String keyword, @Param("cityCode") String cityCode,
                                     @Param("status") String status, @Param("now") OffsetDateTime now,
                                     @Param("limit") int limit, @Param("offset") int offset);

    // ---- 一个场馆 ----

    String VENUE_COLUMNS = """
            SELECT v.id venue_id,
                   v.name,
                   v.operator_id,
                   operator.organization_name operator_name,
                   v.city_code,
                   v.cover_url,
                   v.address,
                   v.phone,
                   to_char(v.opens_at, 'HH24:MI') opens_at,
                   to_char(v.closes_at, 'HH24:MI') closes_at,
                   v.closed_note,
                   v.opening_note,
                   v.checkin_opens_minutes,
                   v.late_entry_minutes,
                   v.sort_order,
                   v.status,
                   v.published_at,
                   v.updated_at,
                   v.version,
                   v.project_summary
            FROM venue v
            JOIN identity_organization operator ON operator.id = v.operator_id
            """;

    @Select(VENUE_COLUMNS + """
            WHERE v.id = #{venueId}
              AND v.status <> 'DELETED'
            """)
    VenueRow findVenue(@Param("venueId") UUID venueId);

    /** 锁住场馆（改资料、上线、下线、加项目和设备都先锁它，免得同时上线和停用最后一个项目）。 */
    @Select(VENUE_COLUMNS + """
            WHERE v.id = #{venueId}
              AND v.status <> 'DELETED'
            FOR UPDATE OF v
            """)
    VenueRow lockVenue(@Param("venueId") UUID venueId);

    @Select("SELECT id FROM venue WHERE ops_create_key = #{key}")
    UUID findVenueByCreateKey(@Param("key") UUID key);

    @Insert("""
            INSERT INTO venue (
                id, operator_id, name, cover_url, city_code, address, phone, opens_at, closes_at, closed_note,
                opening_note, checkin_opens_minutes, late_entry_minutes, sort_order, status, ops_create_key,
                project_summary, created_at, updated_at
            )
            VALUES (
                #{venueId}, #{operatorId}, #{name}, #{coverUrl}, #{cityCode}, #{address}, #{phone},
                CAST(#{opensAt} AS time), CAST(#{closesAt} AS time), #{closedNote}, #{openingNote},
                #{checkinOpensMinutes}, #{lateEntryMinutes}, #{sortOrder}, 'DRAFT', #{createKey},
                #{projectSummary}, #{at}, #{at}
            )
            """)
    int insertVenue(@Param("venueId") UUID venueId, @Param("operatorId") UUID operatorId, @Param("name") String name,
                    @Param("coverUrl") String coverUrl, @Param("cityCode") String cityCode,
                    @Param("address") String address, @Param("phone") String phone,
                    @Param("opensAt") String opensAt, @Param("closesAt") String closesAt,
                    @Param("closedNote") String closedNote, @Param("openingNote") String openingNote,
                    @Param("checkinOpensMinutes") int checkinOpensMinutes,
                    @Param("lateEntryMinutes") Integer lateEntryMinutes, @Param("sortOrder") int sortOrder,
                    @Param("createKey") UUID createKey, @Param("projectSummary") String projectSummary,
                    @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE venue
            SET operator_id = #{operatorId},
                name = #{name},
                cover_url = #{coverUrl},
                city_code = #{cityCode},
                address = #{address},
                phone = #{phone},
                opens_at = CAST(#{opensAt} AS time),
                closes_at = CAST(#{closesAt} AS time),
                closed_note = #{closedNote},
                opening_note = #{openingNote},
                checkin_opens_minutes = #{checkinOpensMinutes},
                late_entry_minutes = #{lateEntryMinutes},
                sort_order = #{sortOrder},
                project_summary = #{projectSummary},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{venueId}
              AND version = #{version}
              AND status <> 'DELETED'
            """)
    int updateVenue(@Param("venueId") UUID venueId, @Param("operatorId") UUID operatorId, @Param("name") String name,
                    @Param("coverUrl") String coverUrl, @Param("cityCode") String cityCode,
                    @Param("address") String address, @Param("phone") String phone,
                    @Param("opensAt") String opensAt, @Param("closesAt") String closesAt,
                    @Param("closedNote") String closedNote, @Param("openingNote") String openingNote,
                    @Param("checkinOpensMinutes") int checkinOpensMinutes,
                    @Param("lateEntryMinutes") Integer lateEntryMinutes, @Param("sortOrder") int sortOrder,
                    @Param("projectSummary") String projectSummary,
                    @Param("version") int version, @Param("at") OffsetDateTime at);

    /** 上线写 published_at（第一次上线的时间不再改），下线保留。 */
    @Update("""
            UPDATE venue
            SET status = #{status},
                published_at = COALESCE(published_at, #{publishedAt}),
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{venueId}
              AND version = #{version}
              AND status = #{fromStatus}
            """)
    int updateVenueStatus(@Param("venueId") UUID venueId, @Param("fromStatus") String fromStatus,
                          @Param("status") String status, @Param("publishedAt") OffsetDateTime publishedAt,
                          @Param("version") int version, @Param("at") OffsetDateTime at);

    /** 场馆里加了项目、设备或场次（详情里显示的东西变了）：只更新时间，不动版本号（版本号管场馆资料本身）。 */
    @Update("UPDATE venue SET updated_at = #{at} WHERE id = #{venueId}")
    int touchVenue(@Param("venueId") UUID venueId, @Param("at") OffsetDateTime at);

    // ---- 场馆信息条目：整组替换 ----

    @Select("""
            SELECT label, content
            FROM venue_info_item
            WHERE venue_id = #{venueId}
              AND status = 'ACTIVE'
            ORDER BY sort_order ASC, id ASC
            """)
    List<InfoItemRow> findInfoItems(@Param("venueId") UUID venueId);

    @Delete("DELETE FROM venue_info_item WHERE venue_id = #{venueId}")
    int deleteInfoItems(@Param("venueId") UUID venueId);

    @Insert("""
            INSERT INTO venue_info_item (venue_id, label, content, sort_order, created_at, updated_at)
            VALUES (#{venueId}, #{label}, #{content}, #{sortOrder}, #{at}, #{at})
            """)
    int insertInfoItem(@Param("venueId") UUID venueId, @Param("label") String label,
                       @Param("content") String content, @Param("sortOrder") int sortOrder,
                       @Param("at") OffsetDateTime at);

    // ---- 项目 ----

    String PROJECT_COLUMNS = """
            SELECT p.id project_id,
                   p.name,
                   p.duration_minutes,
                   p.price_amount_minor,
                   p.min_age,
                   p.capacity,
                   p.limit_note,
                   p.sort_order,
                   p.status,
                   (
                       SELECT count(*)
                       FROM venue_session s
                       WHERE s.project_id = p.id
                         AND s.status = 'ACTIVE'
                         AND s.starts_at > #{now}
                   ) upcoming_session_count,
                   (
                       SELECT min(s.starts_at)
                       FROM venue_session s
                       WHERE s.project_id = p.id
                         AND s.status = 'ACTIVE'
                         AND s.starts_at > #{now}
                   ) next_session_at,
                   p.version
            FROM venue_project p
            """;

    @Select(PROJECT_COLUMNS + """
            WHERE p.venue_id = #{venueId}
            ORDER BY p.sort_order ASC, p.id ASC
            """)
    List<ProjectRow> findProjects(@Param("venueId") UUID venueId, @Param("now") OffsetDateTime now);

    @Select(PROJECT_COLUMNS + """
            WHERE p.venue_id = #{venueId}
              AND p.id = #{projectId}
            FOR UPDATE OF p
            """)
    ProjectRow lockProject(@Param("venueId") UUID venueId, @Param("projectId") UUID projectId,
                           @Param("now") OffsetDateTime now);

    @Select("SELECT id FROM venue_project WHERE venue_id = #{venueId} AND ops_create_key = #{key}")
    UUID findProjectByCreateKey(@Param("venueId") UUID venueId, @Param("key") UUID key);

    @Insert("""
            INSERT INTO venue_project (
                id, venue_id, name, duration_minutes, min_age, capacity, limit_note, price_amount_minor, sort_order,
                status, ops_create_key, created_at, updated_at
            )
            VALUES (
                #{projectId}, #{venueId}, #{name}, #{durationMinutes}, #{minAge}, #{capacity}, #{limitNote},
                #{priceAmountMinor}, #{sortOrder}, 'ACTIVE', #{createKey}, #{at}, #{at}
            )
            """)
    int insertProject(@Param("projectId") UUID projectId, @Param("venueId") UUID venueId, @Param("name") String name,
                      @Param("durationMinutes") int durationMinutes, @Param("minAge") Integer minAge,
                      @Param("capacity") int capacity, @Param("limitNote") String limitNote,
                      @Param("priceAmountMinor") long priceAmountMinor, @Param("sortOrder") int sortOrder,
                      @Param("createKey") UUID createKey, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE venue_project
            SET name = #{name},
                duration_minutes = #{durationMinutes},
                min_age = #{minAge},
                capacity = #{capacity},
                limit_note = #{limitNote},
                price_amount_minor = #{priceAmountMinor},
                sort_order = #{sortOrder},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{projectId}
              AND venue_id = #{venueId}
              AND version = #{version}
            """)
    int updateProject(@Param("venueId") UUID venueId, @Param("projectId") UUID projectId, @Param("name") String name,
                      @Param("durationMinutes") int durationMinutes, @Param("minAge") Integer minAge,
                      @Param("capacity") int capacity, @Param("limitNote") String limitNote,
                      @Param("priceAmountMinor") long priceAmountMinor, @Param("sortOrder") int sortOrder,
                      @Param("version") int version, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE venue_project
            SET status = #{status},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{projectId}
              AND venue_id = #{venueId}
              AND version = #{version}
            """)
    int updateProjectStatus(@Param("venueId") UUID venueId, @Param("projectId") UUID projectId,
                            @Param("status") String status, @Param("version") int version,
                            @Param("at") OffsetDateTime at);

    @Select("SELECT count(*) FROM venue_project WHERE venue_id = #{venueId} AND status = 'ACTIVE'")
    long countActiveProjects(@Param("venueId") UUID venueId);

    /** 能约的场次：启用的项目里、没开始、在售的场次（不管满没满）。 */
    @Select("""
            SELECT count(*)
            FROM venue_session s
            JOIN venue_project p ON p.id = s.project_id
            WHERE p.venue_id = #{venueId}
              AND p.status = 'ACTIVE'
              AND s.status = 'ACTIVE'
              AND s.starts_at > #{now}
            """)
    long countUpcomingSessions(@Param("venueId") UUID venueId, @Param("now") OffsetDateTime now);

    // ---- 场次 ----

    String SESSION_COLUMNS = """
            SELECT s.id session_id,
                   s.project_id,
                   s.starts_at,
                   s.ends_at,
                   s.capacity,
                   s.booked_count,
                   (
                       SELECT count(*)
                       FROM venue_booking b
                       WHERE b.session_id = s.id
                         AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN')
                   ) active_booking_count,
                   s.status,
                   s.version
            FROM venue_session s
            JOIN venue_project p ON p.id = s.project_id
            """;

    @Select(SESSION_COLUMNS + """
            WHERE p.venue_id = #{venueId}
              AND s.project_id = #{projectId}
              AND s.starts_at >= #{from}
              AND s.starts_at < #{to}
            ORDER BY s.starts_at ASC, s.id ASC
            LIMIT #{limit}
            """)
    List<SessionRow> findSessions(@Param("venueId") UUID venueId, @Param("projectId") UUID projectId,
                                  @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                                  @Param("limit") int limit);

    /** 锁住场次（和到馆预约一样先锁场次，取消场次时数到的有效预约才可信）。 */
    @Select(SESSION_COLUMNS + """
            WHERE p.venue_id = #{venueId}
              AND s.id = #{sessionId}
            FOR UPDATE OF s
            """)
    SessionRow lockSession(@Param("venueId") UUID venueId, @Param("sessionId") UUID sessionId);

    /** 这个项目在这段时间里没取消的场次的开始时间（批量生成时跳过已有的）。 */
    @Select("""
            SELECT starts_at
            FROM venue_session
            WHERE project_id = #{projectId}
              AND status <> 'CANCELLED'
              AND starts_at >= #{from}
              AND starts_at < #{to}
            """)
    List<OffsetDateTime> findSessionStarts(@Param("projectId") UUID projectId, @Param("from") OffsetDateTime from,
                                           @Param("to") OffsetDateTime to);

    @Insert("""
            INSERT INTO venue_session (project_id, starts_at, ends_at, capacity, created_at, updated_at)
            VALUES (#{projectId}, #{startsAt}, #{endsAt}, #{capacity}, #{at}, #{at})
            """)
    int insertSession(@Param("projectId") UUID projectId, @Param("startsAt") OffsetDateTime startsAt,
                      @Param("endsAt") OffsetDateTime endsAt, @Param("capacity") int capacity,
                      @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE venue_session
            SET status = #{status},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{sessionId}
              AND status = #{fromStatus}
              AND version = #{version}
            """)
    int updateSessionStatus(@Param("sessionId") UUID sessionId, @Param("fromStatus") String fromStatus,
                            @Param("status") String status, @Param("version") int version,
                            @Param("at") OffsetDateTime at);

    // ---- 馆内设备 ----

    String DEVICE_COLUMNS = """
            SELECT d.id device_id,
                   d.category,
                   d.name,
                   d.summary,
                   d.image_url,
                   d.trial_mode,
                   d.product_id,
                   d.sort_order,
                   d.status,
                   d.version
            FROM venue_device d
            """;

    @Select(DEVICE_COLUMNS + """
            WHERE d.venue_id = #{venueId}
            ORDER BY d.sort_order ASC, d.id ASC
            """)
    List<DeviceRow> findDevices(@Param("venueId") UUID venueId);

    @Select(DEVICE_COLUMNS + """
            WHERE d.venue_id = #{venueId}
              AND d.id = #{deviceId}
            FOR UPDATE OF d
            """)
    DeviceRow lockDevice(@Param("venueId") UUID venueId, @Param("deviceId") UUID deviceId);

    @Select("SELECT id FROM venue_device WHERE venue_id = #{venueId} AND ops_create_key = #{key}")
    UUID findDeviceByCreateKey(@Param("venueId") UUID venueId, @Param("key") UUID key);

    @Insert("""
            INSERT INTO venue_device (
                id, venue_id, category, name, summary, image_url, trial_mode, product_id, sort_order, status,
                ops_create_key, created_at, updated_at
            )
            VALUES (
                #{deviceId}, #{venueId}, #{category}, #{name}, #{summary}, #{imageUrl}, #{trialMode}, #{productId},
                #{sortOrder}, 'ACTIVE', #{createKey}, #{at}, #{at}
            )
            """)
    int insertDevice(@Param("deviceId") UUID deviceId, @Param("venueId") UUID venueId,
                     @Param("category") String category, @Param("name") String name,
                     @Param("summary") String summary, @Param("imageUrl") String imageUrl,
                     @Param("trialMode") String trialMode, @Param("productId") UUID productId,
                     @Param("sortOrder") int sortOrder, @Param("createKey") UUID createKey,
                     @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE venue_device
            SET category = #{category},
                name = #{name},
                summary = #{summary},
                image_url = #{imageUrl},
                trial_mode = #{trialMode},
                product_id = #{productId},
                sort_order = #{sortOrder},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{deviceId}
              AND venue_id = #{venueId}
              AND version = #{version}
            """)
    int updateDevice(@Param("venueId") UUID venueId, @Param("deviceId") UUID deviceId,
                     @Param("category") String category, @Param("name") String name,
                     @Param("summary") String summary, @Param("imageUrl") String imageUrl,
                     @Param("trialMode") String trialMode, @Param("productId") UUID productId,
                     @Param("sortOrder") int sortOrder, @Param("version") int version,
                     @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE venue_device
            SET status = #{status},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{deviceId}
              AND venue_id = #{venueId}
              AND version = #{version}
            """)
    int updateDeviceStatus(@Param("venueId") UUID venueId, @Param("deviceId") UUID deviceId,
                           @Param("status") String status, @Param("version") int version,
                           @Param("at") OffsetDateTime at);

    // ---- 改动记录（只追加） ----

    @Insert("""
            INSERT INTO venue_ops_change (
                venue_id, subject_type, subject_id, action, summary, operator_id, operator_name, changed_at
            )
            VALUES (#{venueId}, #{subjectType}, #{subjectId}, #{action}, #{summary}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertChange(@Param("venueId") UUID venueId, @Param("subjectType") String subjectType,
                     @Param("subjectId") UUID subjectId, @Param("action") String action,
                     @Param("summary") String summary, @Param("operatorId") UUID operatorId,
                     @Param("operatorName") String operatorName, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT id change_id, subject_type, subject_id, action, summary, operator_name, changed_at
            FROM venue_ops_change
            WHERE venue_id = #{venueId}
            ORDER BY changed_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChangeRow> findChanges(@Param("venueId") UUID venueId, @Param("limit") int limit);

    // ---- 行 ----

    record VenueSummaryRow(UUID venueId, String name, String cityCode, UUID operatorId, String operatorName,
                           String status, int sortOrder, long projectCount, long upcomingSessionCount,
                           OffsetDateTime updatedAt, int version) {
    }

    record VenueRow(UUID venueId, String name, UUID operatorId, String operatorName, String cityCode,
                    String coverUrl, String address, String phone, String opensAt, String closesAt,
                    String closedNote, String openingNote, int checkinOpensMinutes, Integer lateEntryMinutes,
                    int sortOrder, String status, OffsetDateTime publishedAt, OffsetDateTime updatedAt, int version,
                    String projectSummary) {
    }

    record InfoItemRow(String label, String content) {
    }

    record ProjectRow(UUID projectId, String name, int durationMinutes, long priceAmountMinor, Integer minAge,
                      int capacity, String limitNote, int sortOrder, String status, long upcomingSessionCount,
                      OffsetDateTime nextSessionAt, int version) {
    }

    record SessionRow(UUID sessionId, UUID projectId, OffsetDateTime startsAt, OffsetDateTime endsAt, int capacity,
                      int bookedCount, long activeBookingCount, String status, int version) {
    }

    record DeviceRow(UUID deviceId, String category, String name, String summary, String imageUrl,
                     String trialMode, UUID productId, int sortOrder, String status, int version) {
    }

    record ChangeRow(UUID changeId, String subjectType, UUID subjectId, String action, String summary,
                     String operatorName, OffsetDateTime changedAt) {
    }
}
