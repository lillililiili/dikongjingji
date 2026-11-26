package com.lowaltitude.inspection.live.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运营端“直播间”（S3-1 第三部分，契约 contracts/openapi-ops-live.yaml）的读写：全部直播间（不分机构、不分城市）、
 * 带版本号的强制结束和回看链接、改动记录。全部用绑定参数；“当前时间”由调用方传入。推流地址、播放地址不读。
 */
public interface LiveOpsMapper {

    String COLUMNS = """
            SELECT r.id,
                   r.host_org_id,
                   r.host_name,
                   r.title,
                   r.subtitle,
                   r.cover_url,
                   r.city_code,
                   r.scheduled_at,
                   r.speaker_name,
                   r.status,
                   r.started_at,
                   r.ended_at,
                   r.end_reason,
                   r.stream_interrupted,
                   r.stream_id,
                   r.replay_url,
                   r.replay_platform_name,
                   CASE WHEN platform_demo_metrics() THEN r.viewer_count ELSE r.recorded_viewer_count END AS viewer_count,
                   CASE WHEN platform_demo_metrics() THEN r.like_count ELSE r.recorded_like_count END AS like_count,
                   CASE WHEN platform_demo_metrics() THEN r.comment_count ELSE r.recorded_comment_count END AS comment_count,
                   r.version,
                   r.created_at
              FROM live_room r
            """;

    String FILTER = """
             WHERE (CAST(#{status} AS varchar) IS NULL OR r.status = #{status})
               AND (CAST(#{cityCode} AS varchar) IS NULL OR r.city_code = #{cityCode})
               AND (CAST(#{hostOrgId} AS uuid) IS NULL OR r.host_org_id = #{hostOrgId})
               AND (CAST(#{keyword} AS varchar) IS NULL
                    OR r.title LIKE '%' || #{keyword} || '%'
                    OR r.host_name LIKE '%' || #{keyword} || '%')
            """;

    @Select("SELECT count(*) FROM live_room r " + FILTER)
    long countRooms(@Param("keyword") String keyword, @Param("status") String status,
                    @Param("cityCode") String cityCode, @Param("hostOrgId") UUID hostOrgId);

    /** 直播中的在前，再预告，再已结束；同一状态按开播时间新的在前。 */
    @Select(COLUMNS + FILTER + """
             ORDER BY CASE r.status WHEN 'LIVE' THEN 0 WHEN 'SCHEDULED' THEN 1 ELSE 2 END,
                      r.scheduled_at DESC, r.id
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<OpsRoomRow> findRooms(@Param("keyword") String keyword, @Param("status") String status,
                               @Param("cityCode") String cityCode, @Param("hostOrgId") UUID hostOrgId,
                               @Param("limit") int limit, @Param("offset") int offset);

    @Select(COLUMNS + "WHERE r.id = #{roomId}")
    OpsRoomRow findRoom(@Param("roomId") UUID roomId);

    @Select(COLUMNS + "WHERE r.id = #{roomId} FOR UPDATE OF r")
    OpsRoomRow lockRoom(@Param("roomId") UUID roomId);

    /** 平台强制结束：只在版本对得上、还没结束时改；断流标记一并清掉。 */
    @Update("""
            UPDATE live_room
            SET status = 'ENDED',
                ended_at = #{at},
                end_reason = 'PLATFORM',
                stream_interrupted = false,
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{roomId}
              AND version = #{version}
              AND status <> 'ENDED'
            """)
    int endRoom(@Param("roomId") UUID roomId, @Param("version") int version, @Param("at") OffsetDateTime at);

    /** 回看链接：只给已结束的直播间改，带版本号；两项同时为空就是去掉回看。 */
    @Update("""
            UPDATE live_room
            SET replay_url = #{replayUrl},
                replay_platform_name = #{replayPlatformName},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{roomId}
              AND version = #{version}
              AND status = 'ENDED'
            """)
    int updateReplay(@Param("roomId") UUID roomId, @Param("replayUrl") String replayUrl,
                     @Param("replayPlatformName") String replayPlatformName, @Param("version") int version,
                     @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO live_ops_change (room_id, action, summary, operator_id, operator_name, changed_at)
            VALUES (#{roomId}, #{action}, #{summary}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertChange(@Param("roomId") UUID roomId, @Param("action") String action, @Param("summary") String summary,
                     @Param("operatorId") UUID operatorId, @Param("operatorName") String operatorName,
                     @Param("at") OffsetDateTime at);

    @Select("""
            SELECT id, room_id, action, summary, operator_name, changed_at
              FROM live_ops_change
             WHERE room_id = #{roomId}
             ORDER BY changed_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<ChangeRow> findChanges(@Param("roomId") UUID roomId, @Param("limit") int limit);

    /** 这些主办机构的直播间数：全部、直播中、预告（运营端“主办机构”标签页）。 */
    @Select("""
            <script>
            SELECT r.host_org_id,
                   count(*) AS room_count,
                   count(*) FILTER (WHERE r.status = 'LIVE') AS live_count,
                   count(*) FILTER (WHERE r.status = 'SCHEDULED') AS scheduled_count
              FROM live_room r
             WHERE r.host_org_id IN
            <foreach collection="hostOrgIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             GROUP BY r.host_org_id
            </script>
            """)
    List<HostCountRow> countByHost(@Param("hostOrgIds") List<UUID> hostOrgIds);

    record OpsRoomRow(
            UUID id,
            UUID hostOrgId,
            String hostName,
            String title,
            String subtitle,
            String coverUrl,
            String cityCode,
            OffsetDateTime scheduledAt,
            String speakerName,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            String endReason,
            boolean streamInterrupted,
            String streamId,
            String replayUrl,
            String replayPlatformName,
            long viewerCount,
            long likeCount,
            long commentCount,
            int version,
            OffsetDateTime createdAt
    ) {
    }

    record ChangeRow(UUID id, UUID roomId, String action, String summary, String operatorName,
                     OffsetDateTime changedAt) {
    }

    record HostCountRow(UUID hostOrgId, long roomCount, long liveCount, long scheduledCount) {
    }
}
