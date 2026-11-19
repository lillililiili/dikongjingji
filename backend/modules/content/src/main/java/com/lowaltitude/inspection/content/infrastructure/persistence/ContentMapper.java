package com.lowaltitude.inspection.content.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 发现页图文内容和专题的只读查询（H1）。全部用绑定参数；“当前时间”和“今天”由调用方传入。
 * 一篇内容在一个城市能看到：已发布、发布时间不晚于现在、城市是这个城市或不分城市（VISIBLE）。
 */
public interface ContentMapper {

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
                   a.published_at
            FROM content_article a
            """;

    String VISIBLE = """
            a.status = 'PUBLISHED'
              AND a.published_at <= #{now}
              AND (a.city_code IS NULL OR a.city_code = #{cityCode})
            """;

    String FEED = "WHERE " + VISIBLE + """
              AND (CAST(#{kind} AS varchar) IS NULL OR a.kind = #{kind})
            """;

    @Select("SELECT count(*) FROM content_article a " + FEED)
    long countFeed(@Param("cityCode") String cityCode, @Param("kind") String kind, @Param("now") OffsetDateTime now);

    /** 新发布的在前。 */
    @Select(ARTICLE_COLUMNS + FEED + """
            ORDER BY a.published_at DESC, a.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ArticleRow> findFeedPage(
            @Param("cityCode") String cityCode,
            @Param("kind") String kind,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select(ARTICLE_COLUMNS + "WHERE a.id = #{articleId} AND " + VISIBLE)
    ArticleRow findArticle(
            @Param("articleId") UUID articleId,
            @Param("cityCode") String cityCode,
            @Param("now") OffsetDateTime now
    );

    @Select("""
            SELECT b.id, b.block_type, b.text
            FROM content_article_block b
            WHERE b.article_id = #{articleId}
            ORDER BY b.sort_order ASC
            """)
    List<BlockRow> findBlocks(@Param("articleId") UUID articleId);

    @Select("""
            SELECT i.block_id, i.title, i.text
            FROM content_article_block_item i
            JOIN content_article_block b ON b.id = i.block_id
            WHERE b.article_id = #{articleId}
            ORDER BY b.sort_order ASC, i.sort_order ASC
            """)
    List<BlockItemRow> findBlockItems(@Param("articleId") UUID articleId);

    @Select("""
            SELECT r.ref_type, r.ref_id, r.title
            FROM content_article_ref r
            WHERE r.article_id = #{articleId}
            ORDER BY r.sort_order ASC
            """)
    List<RefRow> findRefs(@Param("articleId") UUID articleId);

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
                   t.sample
            FROM content_topic t
            """;

    /** 这个城市今天正在进行的专题（含起止两天），新开始的在前。 */
    @Select(TOPIC_COLUMNS + """
            WHERE t.status = 'PUBLISHED'
              AND t.city_code = #{cityCode}
              AND t.starts_on <= #{today}
              AND t.ends_on >= #{today}
            ORDER BY t.starts_on DESC, t.id ASC
            """)
    List<TopicRow> findCurrentTopics(@Param("cityCode") String cityCode, @Param("today") LocalDate today);

    /** 专题详情：已发布、是这个城市的（不看日期，分享出去的链接过了期也能打开）。 */
    @Select(TOPIC_COLUMNS + """
            WHERE t.id = #{topicId}
              AND t.status = 'PUBLISHED'
              AND t.city_code = #{cityCode}
            """)
    TopicRow findTopic(@Param("topicId") UUID topicId, @Param("cityCode") String cityCode);

    @Select("""
            SELECT i.item_type, i.item_id
            FROM content_topic_item i
            WHERE i.topic_id = #{topicId}
            ORDER BY i.sort_order ASC
            """)
    List<TopicItemRow> findTopicItems(@Param("topicId") UUID topicId);

    // ---- 行 ----

    record ArticleRow(
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
            OffsetDateTime publishedAt
    ) {
    }

    record BlockRow(UUID id, String blockType, String text) {
    }

    record BlockItemRow(UUID blockId, String title, String text) {
    }

    record RefRow(String refType, UUID refId, String title) {
    }

    record TopicRow(
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

    record TopicItemRow(String itemType, UUID itemId) {
    }
}
