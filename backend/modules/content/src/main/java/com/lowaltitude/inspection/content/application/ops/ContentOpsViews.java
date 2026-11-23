package com.lowaltitude.inspection.content.application.ops;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsArticleKind;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsAuthorKind;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsBlockType;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsContentStatus;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsRefType;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsVideoOrientation;

/**
 * 运营端“发现内容”对外的形状（契约 contracts/openapi-ops-content.yaml 1.0.0）和服务收的草稿。
 */
public final class ContentOpsViews {

    private ContentOpsViews() {
    }

    // ---- 对外 ----

    /** 作者、专题发布方或发布商家：服务商、商家带机构 ID（名称是机构名称的快照）；平台没有机构，写署名。 */
    public record OpsContentAuthorView(OpsAuthorKind kind, UUID organizationId, String name) {
    }

    public record OpsBlockItemView(String title, String text) {
    }

    /** 配图：url 为空表示还没有授权图片（开发种子），上线前要补。 */
    public record OpsBlockImageView(String url, String alt, String caption) {
    }

    /** 一块正文：text、items、image 按 type 只有一个有内容。 */
    public record OpsBlockView(OpsBlockType type, String text, List<OpsBlockItemView> items, OpsBlockImageView image) {
    }

    /** “内容中提到”的一项：类型、稳定 ID、录入时的名称快照。 */
    public record OpsRefView(OpsRefType type, UUID refId, String title) {
    }

    /** 图文（案例、知识）。publishMissing：现在上线还差哪些必填项（字段键），齐了为空。 */
    public record OpsArticleView(
            UUID articleId,
            OpsArticleKind kind,
            String title,
            OpsContentAuthorView author,
            boolean sponsored,
            String cityCode,
            String coverUrl,
            boolean sample,
            OpsContentStatus status,
            OffsetDateTime publishedAt,
            List<OpsBlockView> blocks,
            List<OpsRefView> refs,
            List<String> publishMissing,
            int version,
            OffsetDateTime updatedAt
    ) {
    }

    /** 视频。rank 是运营排序（大的在前，再按发布时间）。 */
    public record OpsVideoView(
            UUID videoId,
            String title,
            OpsContentAuthorView author,
            boolean sponsored,
            String cityCode,
            String coverUrl,
            boolean sample,
            OpsContentStatus status,
            OffsetDateTime publishedAt,
            int durationSeconds,
            OpsVideoOrientation orientation,
            String playUrl,
            String caption,
            List<String> tags,
            int rank,
            List<OpsRefView> refs,
            List<String> publishMissing,
            int version,
            OffsetDateTime updatedAt
    ) {
    }

    /** 专题里的商家内容：商品 ID 和发布商家。 */
    public record OpsTopicMerchantItemView(UUID productId, OpsContentAuthorView merchant) {
    }

    /** 专题。startsOn、endsOn 是北京时间的日期（YYYY-MM-DD，含两端）。 */
    public record OpsTopicView(
            UUID topicId,
            String title,
            String subtitle,
            OpsContentAuthorView publisher,
            String cityCode,
            String startsOn,
            String endsOn,
            String coverUrl,
            boolean sample,
            OpsContentStatus status,
            List<UUID> activityIds,
            List<UUID> venueIds,
            List<UUID> articleIds,
            List<OpsTopicMerchantItemView> merchantItems,
            List<String> publishMissing,
            int version,
            OffsetDateTime updatedAt
    ) {
    }

    /** 列表里的一条图文或视频。kind：CASE、KNOWLEDGE 或 VIDEO。 */
    public record OpsContentSummaryView(
            UUID contentId,
            String kind,
            String title,
            String authorName,
            boolean sponsored,
            String cityCode,
            String coverUrl,
            boolean sample,
            OpsContentStatus status,
            OffsetDateTime publishedAt,
            int version,
            OffsetDateTime updatedAt
    ) {
    }

    public record OpsContentPageView(int page, int size, long total, boolean hasMore,
                                     List<OpsContentSummaryView> items) {
    }

    public record OpsTopicSummaryView(
            UUID topicId,
            String title,
            String publisherName,
            String cityCode,
            String startsOn,
            String endsOn,
            boolean sample,
            OpsContentStatus status,
            int version,
            OffsetDateTime updatedAt
    ) {
    }

    public record OpsTopicPageView(int page, int size, long total, boolean hasMore, List<OpsTopicSummaryView> items) {
    }

    // ---- 收的草稿（控制器把请求体换成这些；字段按文字收，服务按枚举和长度校验） ----

    public record Operator(UUID accountId, String name) {
    }

    /** kind：PROVIDER / MERCHANT 带机构 ID；PLATFORM 带署名 name。 */
    public record AuthorDraft(String kind, UUID organizationId, String name) {
    }

    public record BlockItemDraft(String title, String text) {
    }

    public record BlockDraft(String type, String text, List<BlockItemDraft> items, String imageUrl, String imageAlt,
                             String imageCaption) {
    }

    public record RefDraft(String type, UUID refId, String title) {
    }

    public record ArticleDraft(String kind, String title, AuthorDraft author, String cityCode, String coverUrl,
                               Boolean sample, List<BlockDraft> blocks, List<RefDraft> refs) {
    }

    public record VideoDraft(String title, AuthorDraft author, String cityCode, String coverUrl, Boolean sample,
                             Integer durationSeconds, String orientation, String playUrl, String caption,
                             List<String> tags, Integer rank, List<RefDraft> refs) {
    }

    public record MerchantItemDraft(UUID productId, UUID merchantOrganizationId) {
    }

    public record TopicDraft(String title, String subtitle, AuthorDraft publisher, String cityCode, String startsOn,
                             String endsOn, String coverUrl, Boolean sample, List<UUID> activityIds,
                             List<UUID> venueIds, List<UUID> articleIds, List<MerchantItemDraft> merchantItems) {
    }
}
