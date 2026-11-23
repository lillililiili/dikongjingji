package com.lowaltitude.inspection.content.application;

import com.lowaltitude.inspection.search.application.SearchPorts;
import org.springframework.stereotype.Component;

/**
 * 全局搜索的内容组：发现页推荐频道（图文和视频）没有关键词参数，逐页看，标题或发布方名称包含关键词的留下。
 * 推荐频道第一页的专题卡不算在内。
 */
@Component
public class ContentSearchFeed implements SearchPorts.Contents {

    private final ContentReadService service;

    public ContentSearchFeed(ContentReadService service) {
        this.service = service;
    }

    @Override
    public SearchPorts.Found search(String cityCode, String keyword, int limit) {
        return SearchPorts.scan(
                (page, size) -> {
                    ContentFeedView loaded = service.feed(cityCode, null, page, size);
                    return new SearchPorts.Page<>(loaded.items(), loaded.hasMore());
                },
                card -> SearchPorts.contains(keyword, card.title(), card.author() == null ? null : card.author().name()),
                limit);
    }
}
