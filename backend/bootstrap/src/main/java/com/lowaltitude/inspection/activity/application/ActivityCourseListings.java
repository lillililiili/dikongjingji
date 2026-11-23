package com.lowaltitude.inspection.activity.application;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ops.ActivityOpsService;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.springframework.stereotype.Component;

/**
 * 给课程模块用的活动入口（S5-5）：商城“课程”页的线下班卡片指向一个活动，标题、封面、价格取自它现在的公开信息。
 * 放在这个包里是因为活动的视图记录只在包内可见；课程模块只拿到它要的几个字段，不知道活动的结构。
 */
@Component
public class ActivityCourseListings {

    /** 课程卡片要的活动信息；priceMode 是 PER_PERSON、FREE 或 INQUIRY。 */
    public record Listing(UUID activityId, String title, String coverUrl, String coverAlt, String priceMode,
                          Long amountMinor) {
    }

    private final ActivityCatalogReadService catalog;
    private final ActivityOpsService ops;

    public ActivityCourseListings(ActivityCatalogReadService catalog, ActivityOpsService ops) {
        this.catalog = catalog;
        this.ops = ops;
    }

    /** 已上线、在这个城市的活动；不存在、没上线、城市不对时为空。 */
    public Optional<Listing> find(UUID activityId, String cityCode) {
        if (activityId == null || cityCode == null) {
            return Optional.empty();
        }
        try {
            ActivityDetailView detail = catalog.findDetail(activityId, cityCode);
            return Optional.of(new Listing(
                    detail.activityId(),
                    detail.title(),
                    detail.coverImage() == null ? null : detail.coverImage().url(),
                    detail.coverImage() == null ? null : detail.coverImage().alt(),
                    detail.price().mode().name(),
                    detail.price().amountMinor()));
        } catch (ActivityNotFoundException | IllegalArgumentException notListed) {
            return Optional.empty();
        }
    }

    /** 这个活动存在（不管上没上线）。 */
    public boolean exists(UUID activityId) {
        if (activityId == null) {
            return false;
        }
        try {
            ops.activity(activityId);
            return true;
        } catch (OpsException notFound) {
            return false;
        }
    }
}
