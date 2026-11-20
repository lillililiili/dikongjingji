package com.lowaltitude.inspection.content.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 发现页视频（1.2.0）：视频详情和视频流的读取，点赞、关注、评论、举报和隐藏评论的写入。全部用绑定参数；
 * “当前时间”由调用方传入。可能为空的参数都带 CAST，PostgreSQL 才知道它的类型。
 */
public interface ContentVideoMapper {

    /**
     * 视频详情和视频流的一条：视频字段，加点赞数、没隐藏的评论数，以及看的人（viewerId，可为空）是否点过赞、
     * 是否关注了发布机构。
     */
    String VIDEO_COLUMNS = """
            SELECT a.id,
                   a.title,
                   a.author_kind,
                   a.author_org_id,
                   a.author_name,
                   a.sponsored,
                   a.city_code,
                   a.cover_url,
                   a.sample,
                   a.published_at,
                   a.video_duration_seconds,
                   a.video_orientation,
                   a.video_url,
                   a.video_caption,
                   a.video_tags,
                   a.video_rank,
                   (SELECT count(*) FROM content_video_like l WHERE l.video_id = a.id) AS like_count,
                   (SELECT count(*) FROM content_video_comment c
                     WHERE c.video_id = a.id AND c.status = 'VISIBLE') AS comment_count,
                   EXISTS (SELECT 1 FROM content_video_like l
                            WHERE l.video_id = a.id AND l.account_id = CAST(#{viewerId} AS uuid)) AS liked,
                   EXISTS (SELECT 1 FROM content_follow f
                            WHERE f.organization_id = a.author_org_id
                              AND f.account_id = CAST(#{viewerId} AS uuid)) AS following_author
            FROM content_article a
            """;

    /** 在这个城市能看到的已发布视频（和图文的可见规则一样，视频都有城市）。 */
    String VISIBLE_VIDEO = """
            a.kind = 'VIDEO'
              AND a.status = 'PUBLISHED'
              AND a.published_at <= #{now}
              AND a.city_code = #{cityCode}
            """;

    @Select(VIDEO_COLUMNS + "WHERE a.id = #{videoId} AND " + VISIBLE_VIDEO)
    VideoRow findVideo(
            @Param("videoId") UUID videoId,
            @Param("cityCode") String cityCode,
            @Param("now") OffsetDateTime now,
            @Param("viewerId") UUID viewerId
    );

    /**
     * 视频流的一段：运营排序大的在前，再按发布时间新的在前。afterId 为空时从头开始；不为空时只要排在
     * (afterRank, afterPublishedAt, afterId) 之后的，inclusive 为 true 时连它自己也要（从发现页点的那一条开始）。
     * followerId 不为空时只要这个账号关注的机构发布的。
     */
    @Select(VIDEO_COLUMNS + "WHERE " + VISIBLE_VIDEO + """
              AND (CAST(#{followerId} AS uuid) IS NULL OR EXISTS (
                  SELECT 1
                  FROM content_follow f
                  WHERE f.account_id = CAST(#{followerId} AS uuid)
                    AND f.organization_id = a.author_org_id
              ))
              AND (CAST(#{afterId} AS uuid) IS NULL
                   OR (a.video_rank, a.published_at, a.id)
                      < (CAST(#{afterRank} AS integer), CAST(#{afterPublishedAt} AS timestamptz), CAST(#{afterId} AS uuid))
                   OR (CAST(#{inclusive} AS boolean) AND a.id = CAST(#{afterId} AS uuid)))
            ORDER BY a.video_rank DESC, a.published_at DESC, a.id DESC
            LIMIT #{limit}
            """)
    List<VideoRow> findStream(
            @Param("cityCode") String cityCode,
            @Param("now") OffsetDateTime now,
            @Param("viewerId") UUID viewerId,
            @Param("followerId") UUID followerId,
            @Param("afterRank") Integer afterRank,
            @Param("afterPublishedAt") OffsetDateTime afterPublishedAt,
            @Param("afterId") UUID afterId,
            @Param("inclusive") boolean inclusive,
            @Param("limit") int limit
    );

    /** 写操作和评论列表用：已发布的视频（不看城市）。 */
    @Select("""
            SELECT a.id, a.title, a.author_org_id, a.author_name
            FROM content_article a
            WHERE a.id = #{videoId}
              AND a.kind = 'VIDEO'
              AND a.status = 'PUBLISHED'
              AND a.published_at <= #{now}
            """)
    VideoTargetRow findPublishedVideo(@Param("videoId") UUID videoId, @Param("now") OffsetDateTime now);

    // ---- 点赞 ----

    @Insert("""
            INSERT INTO content_video_like (video_id, account_id, created_at)
            VALUES (#{videoId}, #{accountId}, #{at})
            ON CONFLICT DO NOTHING
            """)
    int insertLike(@Param("videoId") UUID videoId, @Param("accountId") UUID accountId, @Param("at") OffsetDateTime at);

    @Delete("""
            DELETE FROM content_video_like
            WHERE video_id = #{videoId}
              AND account_id = #{accountId}
            """)
    int deleteLike(@Param("videoId") UUID videoId, @Param("accountId") UUID accountId);

    @Select("SELECT count(*) FROM content_video_like l WHERE l.video_id = #{videoId}")
    long countLikes(@Param("videoId") UUID videoId);

    // ---- 关注 ----

    /** 只能关注发布过内容（任何一类、已发布）的机构。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM content_article a
                WHERE a.author_org_id = #{organizationId}
                  AND a.status = 'PUBLISHED'
                  AND a.published_at <= #{now}
            )
            """)
    boolean isPublishingOrganization(@Param("organizationId") UUID organizationId, @Param("now") OffsetDateTime now);

    @Insert("""
            INSERT INTO content_follow (account_id, organization_id, created_at)
            VALUES (#{accountId}, #{organizationId}, #{at})
            ON CONFLICT DO NOTHING
            """)
    int insertFollow(
            @Param("accountId") UUID accountId,
            @Param("organizationId") UUID organizationId,
            @Param("at") OffsetDateTime at
    );

    @Delete("""
            DELETE FROM content_follow
            WHERE account_id = #{accountId}
              AND organization_id = #{organizationId}
            """)
    int deleteFollow(@Param("accountId") UUID accountId, @Param("organizationId") UUID organizationId);

    // ---- 评论 ----

    /** 同一账号发评论排队，限频的计数和幂等键的查找在锁里做。 */
    @Select("""
            SELECT 1
            FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('content-commenter:' || CAST(#{accountId} AS text), 0))
            ) locked
            """)
    Integer lockCommenter(@Param("accountId") UUID accountId);

    @Select("""
            SELECT count(*)
            FROM content_video_comment c
            WHERE c.account_id = #{accountId}
              AND c.created_at > #{since}
            """)
    long countCommentsSince(@Param("accountId") UUID accountId, @Param("since") OffsetDateTime since);

    String COMMENT_COLUMNS = """
            SELECT c.id,
                   c.video_id,
                   c.account_id,
                   c.display_name,
                   c.by_author,
                   c.body,
                   c.status,
                   c.request_hash,
                   c.created_at
            FROM content_video_comment c
            """;

    @Select(COMMENT_COLUMNS + """
            WHERE c.account_id = #{accountId}
              AND c.idempotency_key = #{idempotencyKey}
            """)
    CommentRow findCommentByKey(@Param("accountId") UUID accountId, @Param("idempotencyKey") UUID idempotencyKey);

    @Select("""
            INSERT INTO content_video_comment (
                video_id, account_id, display_name, by_author, body, idempotency_key, request_hash, created_at, updated_at
            )
            VALUES (#{videoId}, #{accountId}, #{displayName}, #{byAuthor}, #{body}, #{idempotencyKey}, #{requestHash},
                    #{at}, #{at})
            RETURNING id, video_id, account_id, display_name, by_author, body, status, request_hash, created_at
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    CommentRow insertComment(
            @Param("videoId") UUID videoId,
            @Param("accountId") UUID accountId,
            @Param("displayName") String displayName,
            @Param("byAuthor") boolean byAuthor,
            @Param("body") String body,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            SELECT count(*)
            FROM content_video_comment c
            WHERE c.video_id = #{videoId}
              AND c.status = 'VISIBLE'
            """)
    long countVisibleComments(@Param("videoId") UUID videoId);

    /** 一页没隐藏的评论，新的在前。 */
    @Select(COMMENT_COLUMNS + """
            WHERE c.video_id = #{videoId}
              AND c.status = 'VISIBLE'
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<CommentRow> findVisibleComments(
            @Param("videoId") UUID videoId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 举报和隐藏用：评论、它的状态，以及它所在视频的标题、发布机构和是否还在发布。 */
    @Select("""
            SELECT c.id AS comment_id,
                   c.status,
                   c.hidden_at,
                   a.id AS video_id,
                   a.title AS video_title,
                   a.author_org_id AS video_author_org_id,
                   (a.status = 'PUBLISHED' AND a.published_at <= #{now}) AS video_published
            FROM content_video_comment c
            JOIN content_article a ON a.id = c.video_id
            WHERE c.id = #{commentId}
            """)
    CommentTargetRow findCommentTarget(@Param("commentId") UUID commentId, @Param("now") OffsetDateTime now);

    /** 隐藏一条还在显示的评论；已经隐藏的不动（返回空）。 */
    @Select("""
            UPDATE content_video_comment
            SET status = 'HIDDEN',
                hidden_by = #{hiddenBy},
                hidden_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{commentId}
              AND status = 'VISIBLE'
            RETURNING hidden_at
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    OffsetDateTime hideComment(
            @Param("commentId") UUID commentId,
            @Param("hiddenBy") UUID hiddenBy,
            @Param("at") OffsetDateTime at
    );

    // ---- 举报 ----

    String REPORT_COLUMNS = """
            SELECT r.comment_id, r.reason, r.created_at
            FROM content_comment_report r
            """;

    @Select(REPORT_COLUMNS + """
            WHERE r.comment_id = #{commentId}
              AND r.account_id = #{accountId}
            """)
    ReportRow findReport(@Param("commentId") UUID commentId, @Param("accountId") UUID accountId);

    /** 同一账号对同一条已经举报过（并发的另一次先写了）时什么都不写，返回空。 */
    @Select("""
            INSERT INTO content_comment_report (comment_id, account_id, reason, feedback_id, created_at)
            VALUES (#{commentId}, #{accountId}, #{reason}, #{feedbackId}, #{at})
            ON CONFLICT DO NOTHING
            RETURNING comment_id, reason, created_at
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    ReportRow insertReport(
            @Param("commentId") UUID commentId,
            @Param("accountId") UUID accountId,
            @Param("reason") String reason,
            @Param("feedbackId") UUID feedbackId,
            @Param("at") OffsetDateTime at
    );

    // ---- 行 ----

    record VideoRow(
            UUID id,
            String title,
            String authorKind,
            UUID authorOrgId,
            String authorName,
            boolean sponsored,
            String cityCode,
            String coverUrl,
            boolean sample,
            OffsetDateTime publishedAt,
            int videoDurationSeconds,
            String videoOrientation,
            String videoUrl,
            String videoCaption,
            String videoTags,
            int videoRank,
            long likeCount,
            long commentCount,
            boolean liked,
            boolean followingAuthor
    ) {
    }

    record VideoTargetRow(UUID id, String title, UUID authorOrgId, String authorName) {
    }

    record CommentRow(
            UUID id,
            UUID videoId,
            UUID accountId,
            String displayName,
            boolean byAuthor,
            String body,
            String status,
            String requestHash,
            OffsetDateTime createdAt
    ) {
    }

    record CommentTargetRow(
            UUID commentId,
            String status,
            OffsetDateTime hiddenAt,
            UUID videoId,
            String videoTitle,
            UUID videoAuthorOrgId,
            boolean videoPublished
    ) {
    }

    record ReportRow(UUID commentId, String reason, OffsetDateTime createdAt) {
    }
}
