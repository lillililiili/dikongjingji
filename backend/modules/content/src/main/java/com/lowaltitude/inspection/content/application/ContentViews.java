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

/**
 * 瀑布流里的一张卡片（图文或视频）。cityCode 为 null 表示不分城市。coverImage 没有授权图片时为 null（页面用纯色块加标题）。
 * video 是视频卡片的时长和方向，图文为 null。
 */
record ContentArticleCardView(
        UUID articleId,
        ContentArticleKind kind,
        String title,
        ContentAuthorView author,
        ContentVideoSummaryView video,
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

/**
 * R6-3 相关内容：提到某个对象（“内容中提到”里的类型 + 稳定 ID）的图文卡片，只给这个城市能看到的，新发布的在前。
 */
record ContentRelatedView(ContentRefType refType, UUID refId, List<ContentArticleCardView> items) {
}

// ---- 1.2.0 视频 ----

/** 卡片上的视频信息：时长（秒）和画面方向。 */
record ContentVideoSummaryView(int durationSeconds, ContentVideoOrientation orientation) {
}

/**
 * 视频详情，也是视频流里的一条。playUrl 只给带主机名的 HTTPS 地址，没有时为 null；refs 的第一项是视频挂的入口；
 * liked、followingAuthor 不登录时为 false。
 */
record ContentVideoView(
        UUID videoId,
        String title,
        ContentAuthorView author,
        boolean sponsored,
        String cityCode,
        ContentMediaView coverImage,
        boolean sample,
        OffsetDateTime publishedAt,
        int durationSeconds,
        ContentVideoOrientation orientation,
        String playUrl,
        String caption,
        List<String> tags,
        List<ContentRefView> refs,
        long likeCount,
        long commentCount,
        boolean liked,
        boolean followingAuthor
) {
}

/** 视频流的一段：nextCursor 为 null 表示没有了。 */
record ContentVideoPageView(String cityCode, ContentVideoFeed feed, List<ContentVideoView> items, String nextCursor) {
}

record ContentVideoLikeView(UUID videoId, boolean liked, long likeCount) {
}

record ContentFollowView(UUID organizationId, boolean following) {
}

/** 一条评论：authorName 是打码后的显示名，或发布机构的名称（byAuthor）。mine 不登录时为 false。 */
record ContentCommentView(
        UUID commentId,
        UUID videoId,
        String authorName,
        boolean byAuthor,
        boolean mine,
        String text,
        OffsetDateTime createdAt
) {
}

record ContentCommentPageView(int page, int size, long total, boolean hasMore, List<ContentCommentView> items) {
}

/** 发评论的结果：created 为 false 表示同一个幂等键的重放。 */
record ContentCommentPosted(ContentCommentView comment, boolean created) {
}

record ContentCommentReportView(UUID commentId, ContentCommentReportReason reason, OffsetDateTime reportedAt) {
}

/** 举报的结果：created 为 false 表示这个账号举报过这一条了。 */
record ContentCommentReported(ContentCommentReportView report, boolean created) {
}

record ContentCommentHiddenView(UUID commentId, boolean hidden, OffsetDateTime hiddenAt) {
}
