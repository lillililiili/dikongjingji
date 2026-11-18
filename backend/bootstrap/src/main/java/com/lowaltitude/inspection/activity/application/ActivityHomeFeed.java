package com.lowaltitude.inspection.activity.application;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 给首页推荐用的活动入口：本城最近的几个活动，和赛事研学频道第一页同一个顺序。
 * 放在这个包里是因为活动的视图记录只在包内可见，首页聚合接口不需要知道它们的结构。
 */
@Component
public class ActivityHomeFeed {

    private final ActivityCatalogReadService service;

    public ActivityHomeFeed(ActivityCatalogReadService service) {
        this.service = service;
    }

    public List<?> upcoming(String cityCode, int size) {
        return service.search(cityCode, null, null, false, false, 1, size).items();
    }
}
