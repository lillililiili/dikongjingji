package com.lowaltitude.inspection.content.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 对外的形状，字段与契约 contracts/openapi-content.yaml 逐个对应。

record ContentMediaView(String url, String alt) {
}

/** 作者或发布方。organizationId 在平台时为 null；服务商的“主页”按它进服务商主页。 */
record ContentAuthorView(ContentAuthorKind kind, UUID organizationId, String name) {
}

/** 瀑布流里的一张图文卡片。cityCode 为 null 表示不分城市。coverImage 没有授权图片时为 null（页面用纯色块加标题）。 */
record ContentArticleCardView(
        UUID articleId,
        ContentArticleKind kind,
        String title,
        ContentAuthorView author,
        boolean sponsored,
        String cityCode,
        ContentMediaView coverImage,
        boolean sample,
        OffsetDateTime publishedAt
) {
}

/** 推荐频道顶部的专题卡。 */
record ContentTopicCardView(
        UUID topicId,
        String title,
        String subtitle,
        ContentAuthorView publisher,
        LocalDate startsOn,
        LocalDate endsOn,
        ContentMediaView coverImage,
        boolean sample
) {
}

/** 一页发现内容：topics 只在推荐频道的第一页给出（当前城市正在进行的专题），其余为空。 */
record ContentFeedView(
        ContentFeedChannel channel,
        String cityCode,
        int page,
        int size,
        long total,
        boolean hasMore,
        List<ContentTopicCardView> topics,
        List<ContentArticleCardView> items
) {
}

record ContentBlockItemView(String title, String text) {
}

/** 正文的一块：text 和 items 按 type 只有一个有内容（另一个为 null / 空）。 */
record ContentBlockView(ContentBlockType type, String text, List<ContentBlockItemView> items) {
}

/** “内容中提到”的一项：title 是录入时的名称，页面按 type + refId 读真实的名称、价格和状态。 */
record ContentRefView(ContentRefType type, UUID refId, String title) {
}

record ContentArticleView(
        UUID articleId,
        ContentArticleKind kind,
        String title,
        ContentAuthorView author,
        boolean sponsored,
        String cityCode,
        ContentMediaView coverImage,
        boolean sample,
        OffsetDateTime publishedAt,
        List<ContentBlockView> blocks,
        List<ContentRefView> refs
) {
}

/**
 * 专题详情：activityIds、venueIds 按专题里的顺序，页面到赛事研学、体验模块读真实的活动和场馆；
 * articles 是专题挂的、在这个城市能看到的图文卡片。
 */
record ContentTopicView(
        UUID topicId,
        String title,
        String subtitle,
        ContentAuthorView publisher,
        String cityCode,
        LocalDate startsOn,
        LocalDate endsOn,
        ContentMediaView coverImage,
        boolean sample,
        List<UUID> activityIds,
        List<UUID> venueIds,
        List<ContentArticleCardView> articles
) {
}
