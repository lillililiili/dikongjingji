package com.lowaltitude.inspection.content.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运营端“发现内容”（S3-1）的读写。全部用绑定参数；改内容、改状态都带版本号（WHERE version = #{version}），
 * 改之前先锁行（FOR UPDATE）。正文块、条目、“内容中提到”和专题条目整组替换（先删后插）。
 * 读正文块、条目、提到的对象和专题条目沿用公开读取的 ContentMapper。
 */
public interface ContentOpsMapper {

    String ARTICLE_COLUMNS = """
            SELECT a.id,
                   a.kind,
                   a.title,
                   a.author_kind,
                   a.author_org_id,
                   a.author_name,
                   a.sponsored,
                   a.city_code,
                   a.cover_url,
                   a.sample,
                   a.status,
                   a.published_at,
                   a.video_duration_seconds,
                   a.video_orientation,
                   a.video_url,
                   a.video_caption,
                   a.video_tags,
                   a.video_rank,
                   a.version,
                   a.updated_at
            FROM content_article a
            """;

    /** video 为 true 只列视频，false 只列图文；其余条件不传为不筛。 */
    String ARTICLE_FILTER = """
            WHERE (a.kind = 'VIDEO') = #{video}
              AND (CAST(#{kind} AS varchar) IS NULL OR a.kind = #{kind})
              AND (CAST(#{cityCode} AS varchar) IS NULL OR a.city_code = #{cityCode})
              AND (CAST(#{status} AS varchar) IS NULL OR a.status = #{status})
              AND (CAST(#{keyword} AS varchar) IS NULL
                   OR a.title ILIKE '%' || #{keyword} || '%'
                   OR a.author_name ILIKE '%' || #{keyword} || '%')
            """;

    @Select("SELECT count(*) FROM content_article a " + ARTICLE_FILTER)
    long countArticles(
            @Param("video") boolean video,
            @Param("kind") String kind,
            @Param("cityCode") String cityCode,
            @Param("status") String status,
            @Param("keyword") String keyword
    );

    /** 最近改过的在前。 */
    @Select(ARTICLE_COLUMNS + ARTICLE_FILTER + """
            ORDER BY a.updated_at DESC, a.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<OpsArticleRow> findArticles(
            @Param("video") boolean video,
            @Param("kind") String kind,
            @Param("cityCode") String cityCode,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select(ARTICLE_COLUMNS + "WHERE a.id = #{id}")
    OpsArticleRow findArticle(@Param("id") UUID id);

    @Select(ARTICLE_COLUMNS + "WHERE a.id = #{id} FOR UPDATE")
    OpsArticleRow lockArticle(@Param("id") UUID id);

    /** 专题挂的图文要存在、不是视频（状态不限，公开读取再按可见性过滤）。 */
    @Select("SELECT count(*) FROM content_article a WHERE a.id = #{id} AND a.kind <> 'VIDEO'")
    int countPlainArticle(@Param("id") UUID id);

    @Insert("""
            INSERT INTO content_article (id, kind, title, author_kind, author_org_id, author_name, sponsored, city_code,
                                         cover_url, sample, status, published_at, video_duration_seconds,
                                         video_orientation, video_url, video_caption, video_tags, video_rank,
                                         version, created_at, updated_at)
            VALUES (#{row.id}, #{row.kind}, #{row.title}, #{row.authorKind}, #{row.authorOrgId}, #{row.authorName},
                    #{row.sponsored}, #{row.cityCode}, #{row.coverUrl}, #{row.sample}, 'DRAFT', NULL,
                    #{row.videoDurationSeconds}, #{row.videoOrientation}, #{row.videoUrl}, #{row.videoCaption},
                    #{row.videoTags}, #{row.videoRank}, 0, #{at}, #{at})
            """)
    int insertArticle(@Param("row") ArticleWrite row, @Param("at") OffsetDateTime at);

    /** 改内容（不改状态和发布时间），版本号加一。 */
    @Update("""
            UPDATE content_article
            SET kind = #{row.kind},
                title = #{row.title},
                author_kind = #{row.authorKind},
                author_org_id = #{row.authorOrgId},
                author_name = #{row.authorName},
                sponsored = #{row.sponsored},
                city_code = #{row.cityCode},
                cover_url = #{row.coverUrl},
                sample = #{row.sample},
                video_duration_seconds = #{row.videoDurationSeconds},
                video_orientation = #{row.videoOrientation},
                video_url = #{row.videoUrl},
                video_caption = #{row.videoCaption},
                video_tags = #{row.videoTags},
                video_rank = #{row.videoRank},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{row.id}
              AND version = #{version}
            """)
    int updateArticle(@Param("row") ArticleWrite row, @Param("version") int version, @Param("at") OffsetDateTime at);

    /** 上线（带发布时间）或下线（发布时间保留），版本号加一。 */
    @Update("""
            UPDATE content_article
            SET status = #{status},
                published_at = #{publishedAt},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id}
              AND version = #{version}
            """)
    int updateArticleStatus(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("publishedAt") OffsetDateTime publishedAt,
            @Param("version") int version,
            @Param("at") OffsetDateTime at
    );

    // ---- 正文块和“内容中提到”：整组替换 ----

    /** 条目随块级联删掉。 */
    @Delete("DELETE FROM content_article_block WHERE article_id = #{articleId}")
    int deleteBlocks(@Param("articleId") UUID articleId);

    @Insert("""
            INSERT INTO content_article_block (id, article_id, sort_order, block_type, text, image_url, image_alt,
                                               image_caption)
            VALUES (#{id}, #{articleId}, #{sortOrder}, #{blockType}, #{text}, #{imageUrl}, #{imageAlt}, #{imageCaption})
            """)
    int insertBlock(
            @Param("id") UUID id,
            @Param("articleId") UUID articleId,
            @Param("sortOrder") int sortOrder,
            @Param("blockType") String blockType,
            @Param("text") String text,
            @Param("imageUrl") String imageUrl,
            @Param("imageAlt") String imageAlt,
            @Param("imageCaption") String imageCaption
    );

    @Insert("""
            INSERT INTO content_article_block_item (block_id, sort_order, title, text)
            VALUES (#{blockId}, #{sortOrder}, #{title}, #{text})
            """)
    int insertBlockItem(
            @Param("blockId") UUID blockId,
            @Param("sortOrder") int sortOrder,
            @Param("title") String title,
            @Param("text") String text
    );

    @Delete("DELETE FROM content_article_ref WHERE article_id = #{articleId}")
    int deleteRefs(@Param("articleId") UUID articleId);

    @Insert("""
            INSERT INTO content_article_ref (article_id, sort_order, ref_type, ref_id, title)
            VALUES (#{articleId}, #{sortOrder}, #{refType}, #{refId}, #{title})
            """)
    int insertRef(
            @Param("articleId") UUID articleId,
            @Param("sortOrder") int sortOrder,
            @Param("refType") String refType,
            @Param("refId") UUID refId,
            @Param("title") String title
    );

    // ---- 专题 ----

    String TOPIC_COLUMNS = """
            SELECT t.id,
                   t.title,
                   t.subtitle,
                   t.publisher_kind,
                   t.publisher_org_id,
                   t.publisher_name,
                   t.city_code,
                   t.starts_on,
                   t.ends_on,
                   t.cover_url,
                   t.sample,
                   t.status,
                   t.version,
                   t.updated_at
            FROM content_topic t
            """;

    String TOPIC_FILTER = """
            WHERE (CAST(#{cityCode} AS varchar) IS NULL OR t.city_code = #{cityCode})
              AND (CAST(#{status} AS varchar) IS NULL OR t.status = #{status})
              AND (CAST(#{keyword} AS varchar) IS NULL
                   OR t.title ILIKE '%' || #{keyword} || '%'
                   OR t.publisher_name ILIKE '%' || #{keyword} || '%')
            """;

    @Select("SELECT count(*) FROM content_topic t " + TOPIC_FILTER)
    long countTopics(
            @Param("cityCode") String cityCode,
            @Param("status") String status,
            @Param("keyword") String keyword
    );

    @Select(TOPIC_COLUMNS + TOPIC_FILTER + """
            ORDER BY t.updated_at DESC, t.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<OpsTopicRow> findTopics(
            @Param("cityCode") String cityCode,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select(TOPIC_COLUMNS + "WHERE t.id = #{id}")
    OpsTopicRow findTopic(@Param("id") UUID id);

    @Select(TOPIC_COLUMNS + "WHERE t.id = #{id} FOR UPDATE")
    OpsTopicRow lockTopic(@Param("id") UUID id);

    @Insert("""
            INSERT INTO content_topic (id, title, subtitle, publisher_kind, publisher_org_id, publisher_name, city_code,
                                       starts_on, ends_on, cover_url, sample, status, version, created_at, updated_at)
            VALUES (#{row.id}, #{row.title}, #{row.subtitle}, #{row.publisherKind}, #{row.publisherOrgId},
                    #{row.publisherName}, #{row.cityCode}, #{row.startsOn}, #{row.endsOn}, #{row.coverUrl},
                    #{row.sample}, 'DRAFT', 0, #{at}, #{at})
            """)
    int insertTopic(@Param("row") TopicWrite row, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE content_topic
            SET title = #{row.title},
                subtitle = #{row.subtitle},
                publisher_kind = #{row.publisherKind},
                publisher_org_id = #{row.publisherOrgId},
                publisher_name = #{row.publisherName},
                city_code = #{row.cityCode},
                starts_on = #{row.startsOn},
                ends_on = #{row.endsOn},
                cover_url = #{row.coverUrl},
                sample = #{row.sample},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{row.id}
              AND version = #{version}
            """)
    int updateTopic(@Param("row") TopicWrite row, @Param("version") int version, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE content_topic
            SET status = #{status},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id}
              AND version = #{version}
            """)
    int updateTopicStatus(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("version") int version,
            @Param("at") OffsetDateTime at
    );

    @Delete("DELETE FROM content_topic_item WHERE topic_id = #{topicId}")
    int deleteTopicItems(@Param("topicId") UUID topicId);

    @Insert("""
            INSERT INTO content_topic_item (topic_id, sort_order, item_type, item_id, merchant_org_id, merchant_name)
            VALUES (#{topicId}, #{sortOrder}, #{itemType}, #{itemId}, #{merchantOrgId}, #{merchantName})
            """)
    int insertTopicItem(
            @Param("topicId") UUID topicId,
            @Param("sortOrder") int sortOrder,
            @Param("itemType") String itemType,
            @Param("itemId") UUID itemId,
            @Param("merchantOrgId") UUID merchantOrgId,
            @Param("merchantName") String merchantName
    );

    // ---- 改动记录 ----

    @Insert("""
            INSERT INTO content_ops_change (id, subject_type, subject_id, action, summary, operator_id, operator_name,
                                            changed_at)
            VALUES (#{id}, #{subjectType}, #{subjectId}, #{action}, #{summary}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertChange(
            @Param("id") UUID id,
            @Param("subjectType") String subjectType,
            @Param("subjectId") UUID subjectId,
            @Param("action") String action,
            @Param("summary") String summary,
            @Param("operatorId") UUID operatorId,
            @Param("operatorName") String operatorName,
            @Param("at") OffsetDateTime at
    );

    /** 新的在前，最多 limit 条。 */
    @Select("""
            SELECT c.id, c.subject_type, c.subject_id, c.action, c.summary, c.operator_name, c.changed_at
            FROM content_ops_change c
            WHERE c.subject_type = #{subjectType}
              AND c.subject_id = #{subjectId}
            ORDER BY c.changed_at DESC, c.id DESC
            LIMIT #{limit}
            """)
    List<ChangeRow> findChanges(
            @Param("subjectType") String subjectType,
            @Param("subjectId") UUID subjectId,
            @Param("limit") int limit
    );

    // ---- 行 ----

    record OpsArticleRow(
            UUID id,
            String kind,
            String title,
            String authorKind,
            UUID authorOrgId,
            String authorName,
            boolean sponsored,
            String cityCode,
            String coverUrl,
            boolean sample,
            String status,
            OffsetDateTime publishedAt,
            Integer videoDurationSeconds,
            String videoOrientation,
            String videoUrl,
            String videoCaption,
            String videoTags,
            int videoRank,
            int version,
            OffsetDateTime updatedAt
    ) {
    }

    /** 新建、修改时写的字段（图文的视频字段都为空、排序为 0）。 */
    record ArticleWrite(
            UUID id,
            String kind,
            String title,
            String authorKind,
            UUID authorOrgId,
            String authorName,
            boolean sponsored,
            String cityCode,
            String coverUrl,
            boolean sample,
            Integer videoDurationSeconds,
            String videoOrientation,
            String videoUrl,
            String videoCaption,
            String videoTags,
            int videoRank
    ) {
    }

    record OpsTopicRow(
            UUID id,
            String title,
            String subtitle,
            String publisherKind,
            UUID publisherOrgId,
            String publisherName,
            String cityCode,
            LocalDate startsOn,
            LocalDate endsOn,
            String coverUrl,
            boolean sample,
            String status,
            int version,
            OffsetDateTime updatedAt
    ) {
    }

    record TopicWrite(
            UUID id,
            String title,
            String subtitle,
            String publisherKind,
            UUID publisherOrgId,
            String publisherName,
            String cityCode,
            LocalDate startsOn,
            LocalDate endsOn,
            String coverUrl,
            boolean sample
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
