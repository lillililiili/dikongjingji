package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 系统通知（S5-9）的类型：草稿、详情的几块内容、链接和视图。 */
public final class SystemNotices {

    private SystemNotices() {
    }

    public enum AudienceScope {
        ALL,
        CITY
    }

    /** 协议与规则里的五份文档（App features/settings/domain/agreement.ts）。 */
    public enum AgreementCode {
        PRIVACY_POLICY,
        USER_AGREEMENT,
        CHILD_PRIVACY_RULES,
        TRADE_AGREEMENT,
        ENROLLMENT_NOTICE
    }

    /**
     * 详情里一条“主要变化”或底部主按钮去哪。只放稳定编码，App 自己换成页面；App 还没有的页面走类型化网关提示。
     * AGREEMENT 要带 agreement，其他的不带。
     */
    public enum LinkType {
        AGREEMENT,
        ACTIVITY_PARTICIPANTS,
        PRIVACY_PERMISSIONS,
        PERSONAL_DATA_EXPORT,
        NOTIFICATION_SETTINGS,
        PLATFORM_SUPPORT
    }

    public record Link(LinkType type, AgreementCode agreement) {
    }

    /** 一对“名称 / 值”，例如 版本 / v1.1、生效日期 / 2026-11-08。 */
    public record Fact(String label, String value) {
    }

    /** 一条“主要变化”：标题、说明，可以带链接。 */
    public record Item(String title, String description, Link link) {
    }

    /** 底部主按钮，例如“查看完整隐私政策”。 */
    public record Action(String label, Link link) {
    }

    /** content jsonb 里存的东西。 */
    public record Content(List<String> paragraphs, List<Fact> facts, List<Item> items, String footnote, Action action) {
    }

    /** 平台运营要发布的一份公告。 */
    public record Draft(
            String title,
            String summary,
            Content content,
            AgreementCode aboutAgreement,
            AudienceScope audienceScope,
            String cityCode,
            Instant expiresAt
    ) {
    }

    public record Audience(AudienceScope scope, String cityCode) {
    }

    public enum Status {
        PUBLISHED,
        WITHDRAWN
    }

    public record SystemNoticeView(
            UUID id,
            String title,
            String summary,
            List<String> paragraphs,
            List<Fact> facts,
            List<Item> items,
            String footnote,
            Action action,
            AgreementCode aboutAgreement,
            Audience audience,
            Instant publishedAt,
            Instant expiresAt,
            Status status
    ) {
    }

    /** 协议页“有更新”用：最近一份公告的 ID、标题和发布时间。 */
    public record AgreementUpdateView(UUID noticeId, String title, Instant publishedAt) {
    }

    public record SystemNoticePageView(int page, int size, long total, boolean hasMore, List<SystemNoticeView> items) {
    }
}
