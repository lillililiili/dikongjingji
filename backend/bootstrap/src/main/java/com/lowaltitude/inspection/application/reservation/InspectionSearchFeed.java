package com.lowaltitude.inspection.application.reservation;

import com.lowaltitude.inspection.search.application.SearchPorts;
import org.springframework.stereotype.Component;

/** 全局搜索的巡检服务：巡检目录的关键词搜索（综合排序）；城市没开通巡检时是空组。 */
@Component
public class InspectionSearchFeed implements SearchPorts.InspectionServices {

    private final InspectionServiceCatalogReadService service;

    public InspectionSearchFeed(InspectionServiceCatalogReadService service) {
        this.service = service;
    }

    @Override
    public SearchPorts.Found search(String cityCode, String keyword, int limit) {
        try {
            InspectionServicePageView page = service.search(new InspectionCatalogSearch(
                    cityCode, keyword, null, null, null, null, null, null, null, null, 1, limit));
            return new SearchPorts.Found(page.total(), page.items());
        } catch (InspectionCatalogCityNotEnabledException notEnabled) {
            return SearchPorts.Found.empty();
        }
    }
}
