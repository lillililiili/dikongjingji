package com.lowaltitude.inspection.activity.application.ops;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityPriceMode;
import com.lowaltitude.inspection.activity.application.ActivityType;

/** 运营端“活动与场次”对外的形状（契约 contracts/openapi-ops-activity.yaml 1.1.0）。 */
public final class ActivityOpsViews {

    private ActivityOpsViews() {
    }

    /** 活动状态：DRAFT 草稿；PUBLISHED 已上线（App 能看到）；OFFLINE 已下线。删除的不再出现。 */
    public enum OpsActivityStatus {
        DRAFT,
        PUBLISHED,
        OFFLINE
    }

    /** 场次售卖：ON_SALE 在售；STOPPED 停售（App 不能再报名，已有报名照常）。 */
    public enum OpsSessionSaleStatus {
        ON_SALE,
        STOPPED
    }

    /** 机构；name 为 null 表示这家机构现在已停用（仍显示 ID）。 */
    public record OpsOrganizationRefView(UUID organizationId, String name) {
    }

    public record OpsActivitySessionView(
            UUID sessionId,
            String label,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt,
            OffsetDateTime enrollmentClosesAt,
            int capacity,
            int enrolledCount,
            OpsSessionSaleStatus saleStatus,
            /** 早先取消的场次（status = CANCELLED，App 不显示，运营端只读）。 */
            boolean cancelled,
            /** 有过报名单（任何状态）：不能删，只能停售。 */
            boolean hasEnrollments,
            /** 已经开始：不能再改。 */
            boolean started,
            int version
    ) {
    }

    public record OpsActivityView(
            UUID activityId,
            ActivityType type,
            String title,
            String subtitle,
            String coverUrl,
            List<String> galleryUrls,
            OpsOrganizationRefView organizer,
            OpsOrganizationRefView coOrganizer,
            String cityCode,
            String cityName,
            String venueName,
            String venueAddress,
            String scheduleNote,
            ActivityPriceMode priceMode,
            Long priceAmountMinor,
            Integer minAge,
            Integer maxAge,
            String eligibilityNote,
            String seatNote,
            String refundNote,
            String enrollmentNotice,
            /** 场次满员后 App 上能排候补（1.1.0）。 */
            boolean waitlistAllowed,
            /** App 上能团队报名（1.1.0）。 */
            boolean teamEnrollmentAllowed,
            /** 还在排队或已转正待确认的候补条数：关掉候补后它们照常按顺序转正（1.1.0）。 */
            int activeWaitlistCount,
            OpsActivityStatus status,
            OffsetDateTime firstPublishedAt,
            /** 有待付款或已确认的报名：收费方式、主办机构、城市不能再改。 */
            boolean hasEnrollments,
            /** 草稿且从没上线过：可以删。 */
            boolean deletable,
            /** 现在上线还差的必填项（字段键，和 422 的 details.missing 一样）；齐了为空数组。 */
            List<String> publishMissing,
            List<OpsActivitySessionView> sessions,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    public record OpsActivitySummaryView(
            UUID activityId,
            ActivityType type,
            String title,
            String cityCode,
            String cityName,
            OpsOrganizationRefView organizer,
            OpsActivityStatus status,
            ActivityPriceMode priceMode,
            Long priceAmountMinor,
            int sessionCount,
            OffsetDateTime nextStartsAt,
            int enrolledCount,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    public record OpsActivityPageView(int page, int size, long total, boolean hasMore,
                                      List<OpsActivitySummaryView> items) {
    }

    /** 删掉的草稿（之后读它是 404）。 */
    public record OpsActivityDeletedView(UUID activityId, boolean deleted) {
    }

    /** 活动表单（新建、修改共用；校验在服务里）。 */
    public record ActivityDraft(
            String type,
            String title,
            String subtitle,
            String coverUrl,
            List<String> galleryUrls,
            UUID organizerId,
            UUID coOrganizerId,
            String cityCode,
            String venueName,
            String venueAddress,
            String scheduleNote,
            String priceMode,
            Long priceAmountMinor,
            Integer minAge,
            Integer maxAge,
            String eligibilityNote,
            String seatNote,
            String refundNote,
            String enrollmentNotice,
            /** 1.1.0：null 时新建为 false、修改时保持原值。 */
            Boolean waitlistAllowed,
            /** 1.1.0：null 时新建为 false、修改时保持原值。 */
            Boolean teamEnrollmentAllowed
    ) {
    }

    /** 场次表单。 */
    public record SessionDraft(
            String label,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt,
            OffsetDateTime enrollmentClosesAt,
            Integer capacity
    ) {
    }

    /** 操作人（运营账号 ID 和显示名，写进改动记录）。 */
    public record Operator(UUID accountId, String displayName) {
    }
}
