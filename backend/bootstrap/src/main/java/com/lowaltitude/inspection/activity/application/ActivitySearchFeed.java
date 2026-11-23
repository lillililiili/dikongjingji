package com.lowaltitude.inspection.activity.application;

import com.lowaltitude.inspection.search.application.SearchPorts;
import org.springframework.stereotype.Component;

/**
 * 全局搜索的活动组。活动列表没有关键词参数，这里按赛事研学频道的顺序逐页看本城活动，
 * 标题或场地名包含关键词的留下（SearchPorts.scan，最多看 200 条）。
 */
@Component
public class ActivitySearchFeed implements SearchPorts.Activities {

    private final ActivityCatalogReadService service;

    public ActivitySearchFeed(ActivityCatalogReadService service) {
        this.service = service;
    }

    @Override
    public SearchPorts.Found search(String cityCode, String keyword, int limit) {
        return SearchPorts.scan(
                (page, size) -> {
                    ActivityPageView loaded = service.search(cityCode, null, null, false, false, page, size);
                    return new SearchPorts.Page<>(loaded.items(), loaded.hasMore());
                },
                card -> SearchPorts.contains(keyword, card.title(), card.venueName()),
                limit);
    }
}
