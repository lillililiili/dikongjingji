package com.lowaltitude.inspection.content.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 某个机构发布的、这个城市能看到的图文和视频（S5-2 商家主页的“内容”页签）：已发布、发布时间已到，
 * 城市相同或不分城市（知识）。只读，新的在前。
 */
public interface ContentPublisherMapper {

    String PUBLISHED = """
            FROM content_article c
            WHERE c.author_org_id = #{organizationId}
              AND c.status = 'PUBLISHED'
              AND c.published_at <= #{now}
              AND (c.city_code IS NULL OR c.city_code = #{cityCode})
            """;

    @Select("SELECT count(*) " + PUBLISHED)
    long countPublished(@Param("organizationId") UUID organizationId, @Param("cityCode") String cityCode,
                        @Param("now") Instant now);

    @Select("""
            SELECT c.id article_id, c.kind, c.title, c.cover_url, c.sponsored, c.sample, c.published_at
            """ + PUBLISHED + """
            ORDER BY c.published_at DESC, c.id DESC
            LIMIT #{limit}
            """)
    List<PublishedRow> findPublished(@Param("organizationId") UUID organizationId, @Param("cityCode") String cityCode,
                                     @Param("now") Instant now, @Param("limit") int limit);

    record PublishedRow(UUID articleId, String kind, String title, String coverUrl, boolean sponsored, boolean sample,
                        Instant publishedAt) {
    }
}
