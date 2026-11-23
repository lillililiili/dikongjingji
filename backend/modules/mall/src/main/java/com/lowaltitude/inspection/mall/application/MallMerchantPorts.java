package com.lowaltitude.inspection.mall.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 商家主页（S5-2）要的别的模块的数据，由启动模块接上：这家机构在这个城市主办的活动（活动模块）、发布的内容（内容模块）。
 * 商城不读活动和内容的表。没接时两个页签是空的。
 */
public final class MallMerchantPorts {

    private MallMerchantPorts() {
    }

    public interface Activities {
        Activities NONE = (organizationId, cityCode, limit) -> new Slice<>(0, List.of());

        Slice<ActivityItem> upcoming(UUID organizationId, String cityCode, int limit);
    }

    public interface Contents {
        Contents NONE = (organizationId, cityCode, limit) -> new Slice<>(0, List.of());

        Slice<ContentItem> published(UUID organizationId, String cityCode, int limit);
    }

    public record Slice<T>(long total, List<T> items) {
    }

    /** priceMode：PER_PERSON 按人收费（priceAmountMinor 是单价）、FREE 免费、INQUIRY 咨询。 */
    public record ActivityItem(UUID activityId, String title, String coverUrl, String venueName, Instant startsAt,
                               String priceMode, Long priceAmountMinor) {
    }

    /** kind：CASE 案例、KNOWLEDGE 知识、VIDEO 视频。 */
    public record ContentItem(UUID contentId, String kind, String title, String coverUrl, boolean sponsored,
                              boolean sample, Instant publishedAt) {
    }
}
