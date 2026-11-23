package com.lowaltitude.inspection.experience.application.ops;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.VenueDeviceCategory;
import com.lowaltitude.inspection.experience.application.VenueDeviceTrialMode;

/** 运营端“场馆与设备”对外的形状（契约 contracts/openapi-ops-venue.yaml 1.0.0）。 */
public final class VenueOpsViews {

    private VenueOpsViews() {
    }

    /** 场馆状态：草稿、已上线（App 可见）、已下线。 */
    public enum VenueOpsStatus {
        DRAFT,
        PUBLISHED,
        OFFLINE
    }

    /** 项目、设备：启用（App 可见）、停用。 */
    public enum VenueItemStatus {
        ACTIVE,
        DISABLED
    }

    /** 场次：在售、停售（不接新预约，已有的照常）、已取消。 */
    public enum VenueSessionOpsStatus {
        ACTIVE,
        STOPPED,
        CANCELLED
    }

    public record OpsVenueSummaryView(UUID venueId, String name, String cityCode, UUID operatorId,
                                      String operatorName, VenueOpsStatus status, int sortOrder, long projectCount,
                                      long upcomingSessionCount, OffsetDateTime updatedAt, int version) {
    }

    public record OpsVenuePageView(int page, int size, long total, boolean hasMore, List<OpsVenueSummaryView> items) {
    }

    public record OpsVenueInfoItemView(String label, String content) {
    }

    public record OpsVenueProjectView(UUID projectId, String name, int durationMinutes, long priceAmountMinor,
                                      Integer minAge, int capacity, String limitNote, int sortOrder,
                                      VenueItemStatus status, long upcomingSessionCount,
                                      OffsetDateTime nextSessionAt, int version) {
    }

    public record OpsVenueDeviceView(UUID deviceId, VenueDeviceCategory category, String name, String summary,
                                     String imageUrl, VenueDeviceTrialMode trialMode, UUID productId, int sortOrder,
                                     VenueItemStatus status, int version) {
    }

    /**
     * @param publishMissing 现在上线还差哪些必填项（字段键，契约 OpsVenuePublishField）；空表示可以上线
     */
    public record OpsVenueDetailView(UUID venueId, String name, UUID operatorId, String operatorName,
                                     String cityCode, String cityName, String coverUrl, String address,
                                     String phone, String opensAt, String closesAt, String closedNote,
                                     String openingNote, int checkinOpensMinutes, Integer lateEntryMinutes,
                                     int sortOrder, VenueOpsStatus status, OffsetDateTime publishedAt,
                                     List<OpsVenueInfoItemView> infoItems, List<OpsVenueProjectView> projects,
                                     List<OpsVenueDeviceView> devices, List<String> publishMissing,
                                     OffsetDateTime updatedAt, int version) {
    }

    public record OpsVenueSessionView(UUID sessionId, UUID projectId, OffsetDateTime startsAt,
                                      OffsetDateTime endsAt, int capacity, int bookedCount,
                                      long activeBookingCount, VenueSessionOpsStatus status, int version) {
    }

    /**
     * @param createdCount         新生成的场次数
     * @param skippedExistingCount 同一项目同一开始时间已经有（没取消的）场次，跳过
     * @param skippedPastCount     开始时间已过，跳过
     */
    public record OpsVenueSessionsGeneratedView(int createdCount, int skippedExistingCount, int skippedPastCount) {
    }

    // ---- 输入（控制器把请求体转成这些） ----

    public record InfoItemDraft(String label, String content) {
    }

    public record VenueDraft(UUID operatorId, String name, String cityCode, String coverUrl, String address,
                             String phone, String opensAt, String closesAt, String closedNote,
                             Integer checkinOpensMinutes, Integer lateEntryMinutes, Integer sortOrder,
                             List<InfoItemDraft> infoItems) {
    }

    public record ProjectDraft(String name, Integer durationMinutes, Long priceAmountMinor, Integer minAge,
                               Integer capacity, String limitNote, Integer sortOrder) {
    }

    public record DeviceDraft(String category, String name, String summary, String imageUrl, String trialMode,
                              UUID productId, Integer sortOrder) {
    }

    /**
     * 按周模板生成场次：fromDate–toDate（北京时间，含两端）里 weekdays 那几天，每天 startTimes 这几个开始时间
     * （HH:mm），时长取项目时长，名额默认取项目每场人数。
     */
    public record SessionPlan(String fromDate, String toDate, List<String> weekdays, List<String> startTimes,
                              Integer capacity) {
    }
}
