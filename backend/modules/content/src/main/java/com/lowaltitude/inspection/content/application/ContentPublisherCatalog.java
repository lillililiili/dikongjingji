package com.lowaltitude.inspection.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.content.infrastructure.persistence.ContentPublisherMapper;

/**
 * 给别的模块用的只读入口（S5-2）：某个机构发布的、这个城市能看到的图文和视频。商城的商家主页经 bootstrap 的端口来读。
 */
public final class ContentPublisherCatalog {

    private static final int MAX_LIMIT = 20;

    private final ContentPublisherMapper mapper;
    private final Clock clock;

    public ContentPublisherCatalog(ContentPublisherMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** kind：CASE 案例、KNOWLEDGE 知识、VIDEO 视频。 */
    public record PublishedContent(UUID contentId, String kind, String title, String coverUrl, boolean sponsored,
                                   boolean sample, Instant publishedAt) {
    }

    public record Published(long total, List<PublishedContent> items) {
    }

    public Published published(UUID organizationId, String cityCode, int limit) {
        if (organizationId == null || cityCode == null || cityCode.isBlank()) {
            throw new IllegalArgumentException("organization and city are required");
        }
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        Instant now = clock.instant();
        String city = cityCode.trim();
        return new Published(
                mapper.countPublished(organizationId, city, now),
                mapper.findPublished(organizationId, city, now, size).stream()
                        .map(row -> new PublishedContent(row.articleId(), row.kind(), row.title(), row.coverUrl(),
                                row.sponsored(), row.sample(), row.publishedAt()))
                        .toList());
    }
}
