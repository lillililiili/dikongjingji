package com.lowaltitude.inspection.experience.application;

import com.lowaltitude.inspection.search.application.SearchPorts;
import org.springframework.stereotype.Component;

/** 全局搜索的场馆组：场馆列表没有关键词参数，逐页看本城场馆，名称或地址包含关键词的留下。 */
@Component
public class VenueSearchFeed implements SearchPorts.Venues {

    private final ExperienceCatalogReadService service;

    public VenueSearchFeed(ExperienceCatalogReadService service) {
        this.service = service;
    }

    @Override
    public SearchPorts.Found search(String cityCode, String keyword, int limit) {
        return SearchPorts.scan(
                (page, size) -> {
                    VenuePageView loaded = service.listVenues(cityCode, page, size);
                    return new SearchPorts.Page<>(loaded.items(), loaded.hasMore());
                },
                card -> SearchPorts.contains(keyword, card.name(), card.address()),
                limit);
    }
}
