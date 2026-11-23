package com.lowaltitude.inspection.live.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 直播间、讲解清单、观看、提醒、关注、点赞、弹幕和服务商回调的读写（M20）。全部用绑定参数；“当前时间”由调用方传入。
 * 主办方的读写以主办机构为条件；观众的读写以账号为条件。读出的列和行记录的字段一一对应、顺序一致。
 */
public interface LiveMapper {

    /** 同一个账号（或同一个机构建直播间）的写入排队：幂等键查重和写入之间不会插进同一个人的另一次提交。 */
    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('live-actor:' || CAST(#{actorId} AS text), 0))
            ) locked
            """)
    Integer lockActor(@Param("actorId") UUID actorId);

    // ---- 直播间 ----

    String ROOM_COLUMNS = """
            SELECT r.id,
                   r.host_org_id,
                   r.host_name,
                   r.title,
                   r.subtitle,
                   r.cover_url,
                   r.city_code,
                   r.scheduled_at,
                   r.speaker_name,
                   r.speaker_title,
                   r.topics,
                   r.status,
                   r.started_at,
                   r.ended_at,
                   r.stream_interrupted,
                   r.stream_id,
                   r.push_url,
                   r.play_url,
                   r.last_event_at,
                   r.replay_url,
                   r.replay_platform_name,
                   r.current_item_seq,
                   r.viewer_count,
                   r.like_count,
                   r.comment_count,
                   r.request_hash,
                   r.created_at,
                   current_item.item_type current_item_type,
                   current_item.ref_id current_item_ref_id,
                   current_item.title current_item_title
            FROM live_room r
            LEFT JOIN live_room_item current_item
              ON current_item.room_id = r.id AND current_item.seq = r.current_item_seq
            """;

    @Select(ROOM_COLUMNS + "WHERE r.id = #{roomId}")
    RoomRow findRoom(@Param("roomId") UUID roomId);

    @Select(ROOM_COLUMNS + "WHERE r.id = #{roomId} FOR UPDATE OF r")
    RoomRow lockRoom(@Param("roomId") UUID roomId);

    @Select(ROOM_COLUMNS + "WHERE r.stream_id = #{streamId} FOR UPDATE OF r")
    RoomRow lockRoomByStream(@Param("streamId") String streamId);

    @Select(ROOM_COLUMNS + "WHERE r.host_org_id = #{hostOrgId} AND r.idempotency_key = #{idempotencyKey}")
    RoomRow findRoomByKey(@Param("hostOrgId") UUID hostOrgId, @Param("idempotencyKey") UUID idempotencyKey);

    /**
     * 公开列表能看到的直播间：当前城市或全国；预告开播时间过了一天还没开播的不列；已结束的只列有回看的。
     * status 为空时三种都要。
     */
    String LISTED = """
            WHERE (r.city_code IS NULL OR r.city_code = #{cityCode})
              AND (CAST(#{status} AS varchar) IS NULL OR r.status = CAST(#{status} AS varchar))
              AND (r.status <> 'SCHEDULED' OR r.scheduled_at > #{staleBefore})
              AND (r.status <> 'ENDED' OR r.replay_url IS NOT NULL)
            """;

    @Select("SELECT count(*) FROM live_room r " + LISTED)
    long countListed(
            @Param("cityCode") String cityCode,
            @Param("status") String status,
            @Param("staleBefore") OffsetDateTime staleBefore
    );

    /** 直播中的在前（开播早的在前），预告按开播时间先后，已结束的结束晚的在前。不按人气排。 */
    @Select(ROOM_COLUMNS + LISTED + """
            ORDER BY CASE r.status WHEN 'LIVE' THEN 0 WHEN 'SCHEDULED' THEN 1 ELSE 2 END,
                     CASE WHEN r.status = 'LIVE' THEN r.started_at WHEN r.status = 'SCHEDULED' THEN r.scheduled_at END ASC,
                     CASE WHEN r.status = 'ENDED' THEN r.ended_at END DESC,
                     r.id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<RoomRow> findListed(
            @Param("cityCode") String cityCode,
            @Param("status") String status,
            @Param("staleBefore") OffsetDateTime staleBefore,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 主办方自己的直播间（1.1.0 起工作台可按状态筛；status 为空时全部）。 */
    String HOSTED = """
            WHERE r.host_org_id = #{hostOrgId}
              AND (CAST(#{status} AS varchar) IS NULL OR r.status = CAST(#{status} AS varchar))
            """;

    @Select("SELECT count(*) FROM live_room r " + HOSTED)
    long countHostRooms(@Param("hostOrgId") UUID hostOrgId, @Param("status") String status);

    @Select(ROOM_COLUMNS + HOSTED + """
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<RoomRow> findHostRooms(@Param("hostOrgId") UUID hostOrgId, @Param("status") String status,
                                @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT EXISTS (SELECT 1 FROM live_room WHERE host_org_id = #{hostOrgId})")
    boolean hostExists(@Param("hostOrgId") UUID hostOrgId);

    @Insert("""
            INSERT INTO live_room (
                id, host_org_id, host_name, title, subtitle, cover_url, city_code, scheduled_at, speaker_name,
                speaker_title, topics, stream_id, push_url, play_url, idempotency_key, request_hash, created_at, updated_at
            )
            VALUES (
                #{roomId}, #{hostOrgId}, #{hostName}, #{draft.title}, #{draft.subtitle}, #{draft.coverUrl},
                #{draft.cityCode}, #{draft.scheduledAt}, #{draft.speakerName}, #{draft.speakerTitle}, #{draft.topics},
                #{streamId}, #{pushUrl}, #{playUrl}, #{idempotencyKey}, #{requestHash}, #{at}, #{at}
            )
            """)
    int insertRoom(
            @Param("roomId") UUID roomId,
            @Param("hostOrgId") UUID hostOrgId,
            @Param("hostName") String hostName,
            @Param("draft") RoomFields draft,
            @Param("streamId") String streamId,
            @Param("pushUrl") String pushUrl,
            @Param("playUrl") String playUrl,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    /** 改预告：只在预告时。 */
    @Update("""
            UPDATE live_room
            SET title = #{draft.title},
                subtitle = #{draft.subtitle},
                cover_url = #{draft.coverUrl},
                city_code = #{draft.cityCode},
                scheduled_at = #{draft.scheduledAt},
                speaker_name = #{draft.speakerName},
                speaker_title = #{draft.speakerTitle},
                topics = #{draft.topics},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{roomId}
              AND status = 'SCHEDULED'
            """)
    int updatePreview(@Param("roomId") UUID roomId, @Param("draft") RoomFields draft, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE live_room
            SET replay_url = #{replayUrl},
                replay_platform_name = #{replayPlatformName},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{roomId}
            """)
    int updateReplay(
            @Param("roomId") UUID roomId,
            @Param("replayUrl") String replayUrl,
            @Param("replayPlatformName") String replayPlatformName,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE live_room
            SET current_item_seq = #{seq},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{roomId}
            """)
    int updateCurrentItem(@Param("roomId") UUID roomId, @Param("seq") Integer seq, @Param("at") OffsetDateTime at);

    /** 状态迁移：开播、断流、恢复、结束。lastEventAt 是这次处理的回调的发生时间（平台客服结束时不变）。 */
    @Update("""
            UPDATE live_room
            SET status = #{status},
                started_at = #{startedAt},
                ended_at = #{endedAt},
                end_reason = #{endReason},
                stream_interrupted = #{interrupted},
                last_event_at = #{lastEventAt},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{roomId}
            """)
    int updateStatus(
            @Param("roomId") UUID roomId,
            @Param("status") String status,
            @Param("startedAt") OffsetDateTime startedAt,
            @Param("endedAt") OffsetDateTime endedAt,
            @Param("endReason") String endReason,
            @Param("interrupted") boolean interrupted,
            @Param("lastEventAt") OffsetDateTime lastEventAt,
            @Param("at") OffsetDateTime at
    );

    @Update("UPDATE live_room SET viewer_count = viewer_count + 1 WHERE id = #{roomId} AND status = 'LIVE'")
    int addViewer(@Param("roomId") UUID roomId);

    @Select("""
            UPDATE live_room SET like_count = like_count + #{count}
            WHERE id = #{roomId}
            RETURNING like_count
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long addLikes(@Param("roomId") UUID roomId, @Param("count") int count);

    /** 下一条弹幕的序号。行锁到提交为止，所以序号按提交的先后递增，轮询不会漏掉晚提交的小序号。 */
    @Select("""
            UPDATE live_room SET comment_count = comment_count + 1
            WHERE id = #{roomId}
            RETURNING comment_count
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long nextCommentSeq(@Param("roomId") UUID roomId);

    // ---- 讲解清单 ----

    @Select("""
            SELECT seq, item_type, ref_id, title
            FROM live_room_item
            WHERE room_id = #{roomId}
            ORDER BY seq
            """)
    List<ItemRow> findItems(@Param("roomId") UUID roomId);

    @Delete("DELETE FROM live_room_item WHERE room_id = #{roomId}")
    int deleteItems(@Param("roomId") UUID roomId);

    @Insert("""
            INSERT INTO live_room_item (room_id, seq, item_type, ref_id, title)
            VALUES (#{roomId}, #{seq}, #{type}, #{refId}, #{title})
            """)
    int insertItem(
            @Param("roomId") UUID roomId,
            @Param("seq") int seq,
            @Param("type") String type,
            @Param("refId") UUID refId,
            @Param("title") String title
    );

    // ---- 观看、提醒、关注、点赞 ----

    @Insert("""
            INSERT INTO live_room_viewer (room_id, viewer_key, created_at)
            VALUES (#{roomId}, #{viewerKey}, #{at})
            ON CONFLICT DO NOTHING
            """)
    int insertViewer(@Param("roomId") UUID roomId, @Param("viewerKey") UUID viewerKey, @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO live_reminder (room_id, account_id, created_at)
            VALUES (#{roomId}, #{accountId}, #{at})
            ON CONFLICT DO NOTHING
            """)
    int insertReminder(@Param("roomId") UUID roomId, @Param("accountId") UUID accountId,
                       @Param("at") OffsetDateTime at);

    @Delete("DELETE FROM live_reminder WHERE room_id = #{roomId} AND account_id = #{accountId}")
    int deleteReminder(@Param("roomId") UUID roomId, @Param("accountId") UUID accountId);

    @Select("SELECT EXISTS (SELECT 1 FROM live_reminder WHERE room_id = #{roomId} AND account_id = #{accountId})")
    boolean reminderExists(@Param("roomId") UUID roomId, @Param("accountId") UUID accountId);

    /** 开播了：把还没通知过的提醒标上通知时间，返回这些账号。 */
    @Select("""
            UPDATE live_reminder
            SET notified_at = #{at}
            WHERE room_id = #{roomId}
              AND notified_at IS NULL
            RETURNING account_id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<UUID> claimReminders(@Param("roomId") UUID roomId, @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO live_host_follow (account_id, host_org_id, created_at)
            VALUES (#{accountId}, #{hostOrgId}, #{at})
            ON CONFLICT DO NOTHING
            """)
    int insertFollow(@Param("accountId") UUID accountId, @Param("hostOrgId") UUID hostOrgId,
                     @Param("at") OffsetDateTime at);

    @Delete("DELETE FROM live_host_follow WHERE account_id = #{accountId} AND host_org_id = #{hostOrgId}")
    int deleteFollow(@Param("accountId") UUID accountId, @Param("hostOrgId") UUID hostOrgId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM live_host_follow WHERE account_id = #{accountId} AND host_org_id = #{hostOrgId}
            )
            """)
    boolean followExists(@Param("accountId") UUID accountId, @Param("hostOrgId") UUID hostOrgId);

    @Insert("""
            INSERT INTO live_like_window (room_id, account_id, window_start)
            VALUES (#{roomId}, #{accountId}, #{windowStart})
            ON CONFLICT DO NOTHING
            """)
    int insertLikeWindow(@Param("roomId") UUID roomId, @Param("accountId") UUID accountId,
                         @Param("windowStart") OffsetDateTime windowStart);

    @Select("""
            SELECT like_count FROM live_like_window
            WHERE room_id = #{roomId} AND account_id = #{accountId} AND window_start = #{windowStart}
            FOR UPDATE
            """)
    int lockLikeWindow(@Param("roomId") UUID roomId, @Param("accountId") UUID accountId,
                       @Param("windowStart") OffsetDateTime windowStart);

    @Update("""
            UPDATE live_like_window SET like_count = #{count}
            WHERE room_id = #{roomId} AND account_id = #{accountId} AND window_start = #{windowStart}
            """)
    int setLikeWindow(@Param("roomId") UUID roomId, @Param("accountId") UUID accountId,
                      @Param("windowStart") OffsetDateTime windowStart, @Param("count") int count);

    // ---- 弹幕 ----

    String COMMENT_COLUMNS = """
            SELECT c.id,
                   c.room_id,
                   c.seq,
                   c.side,
                   c.author_name,
                   c.body,
                   c.hidden_at,
                   c.request_hash,
                   c.created_at
            FROM live_comment c
            """;

    @Select(COMMENT_COLUMNS + "WHERE c.author_id = #{authorId} AND c.idempotency_key = #{idempotencyKey}")
    CommentRow findCommentByKey(@Param("authorId") UUID authorId, @Param("idempotencyKey") UUID idempotencyKey);

    @Select(COMMENT_COLUMNS + "WHERE c.id = #{commentId} AND c.room_id = #{roomId}")
    CommentRow findRoomComment(@Param("roomId") UUID roomId, @Param("commentId") UUID commentId);

    /** 这个账号最近一条观众弹幕的时间（限频；跨直播间）。 */
    @Select("""
            SELECT max(created_at) FROM live_comment
            WHERE author_id = #{authorId} AND side = 'VIEWER'
            """)
    OffsetDateTime lastViewerCommentAt(@Param("authorId") UUID authorId);

    @Select("""
            INSERT INTO live_comment (
                room_id, seq, side, author_id, author_name, body, idempotency_key, request_hash, created_at
            )
            VALUES (
                #{roomId}, #{seq}, #{side}, #{authorId}, #{authorName}, #{body}, #{idempotencyKey}, #{requestHash}, #{at}
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertComment(
            @Param("roomId") UUID roomId,
            @Param("seq") long seq,
            @Param("side") String side,
            @Param("authorId") UUID authorId,
            @Param("authorName") String authorName,
            @Param("body") String body,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    /** 比 since 新的没隐藏的弹幕，新的在前（服务按先后翻过来）；since 为空时就是最近的。 */
    @Select(COMMENT_COLUMNS + """
            WHERE c.room_id = #{roomId}
              AND c.hidden_at IS NULL
              AND (CAST(#{since} AS bigint) IS NULL OR c.seq > CAST(#{since} AS bigint))
            ORDER BY c.seq DESC
            LIMIT #{limit}
            """)
    List<CommentRow> findComments(@Param("roomId") UUID roomId, @Param("since") Long since, @Param("limit") int limit);

    @Select("""
            SELECT c.seq FROM live_comment c
            WHERE c.room_id = #{roomId}
              AND c.hidden_at IS NOT NULL
              AND c.seq > #{fromSeq}
            ORDER BY c.seq
            """)
    List<Long> findHiddenSeqs(@Param("roomId") UUID roomId, @Param("fromSeq") long fromSeq);

    @Update("""
            UPDATE live_comment
            SET hidden_at = #{at},
                hidden_by = #{hiddenBy}
            WHERE id = #{commentId}
              AND room_id = #{roomId}
              AND hidden_at IS NULL
            """)
    int hideComment(
            @Param("roomId") UUID roomId,
            @Param("commentId") UUID commentId,
            @Param("hiddenBy") String hiddenBy,
            @Param("at") OffsetDateTime at
    );

    // ---- 服务商回调 ----

    /** 同一个事件 ID 只处理一次：已经有了时什么都不写。 */
    @Insert("""
            INSERT INTO live_stream_event (event_id, room_id, event_type, occurred_at, received_at)
            VALUES (#{eventId}, #{roomId}, #{type}, #{occurredAt}, #{at})
            ON CONFLICT DO NOTHING
            """)
    int insertStreamEvent(
            @Param("eventId") String eventId,
            @Param("roomId") UUID roomId,
            @Param("type") String type,
            @Param("occurredAt") OffsetDateTime occurredAt,
            @Param("at") OffsetDateTime at
    );

    // ---- 行 ----

    /** 写进直播间的预告字段（topics 已经按换行拼好）。 */
    record RoomFields(
            String title,
            String subtitle,
            String coverUrl,
            String cityCode,
            OffsetDateTime scheduledAt,
            String speakerName,
            String speakerTitle,
            String topics
    ) {
    }

    record RoomRow(
            UUID id,
            UUID hostOrgId,
            String hostName,
            String title,
            String subtitle,
            String coverUrl,
            String cityCode,
            OffsetDateTime scheduledAt,
            String speakerName,
            String speakerTitle,
            String topics,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            boolean streamInterrupted,
            String streamId,
            String pushUrl,
            String playUrl,
            OffsetDateTime lastEventAt,
            String replayUrl,
            String replayPlatformName,
            Integer currentItemSeq,
            long viewerCount,
            long likeCount,
            long commentCount,
            String requestHash,
            OffsetDateTime createdAt,
            String currentItemType,
            UUID currentItemRefId,
            String currentItemTitle
    ) {
    }

    record ItemRow(int seq, String itemType, UUID refId, String title) {
    }

    record CommentRow(
            UUID id,
            UUID roomId,
            long seq,
            String side,
            String authorName,
            String body,
            OffsetDateTime hiddenAt,
            String requestHash,
            OffsetDateTime createdAt
    ) {
    }
}
