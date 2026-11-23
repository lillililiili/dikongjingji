package com.lowaltitude.inspection.mall.application;

import com.lowaltitude.inspection.search.application.SearchPorts;
import org.springframework.stereotype.Component;

/** 全局搜索的商品组：商城列表的关键词搜索，默认排序，和商城频道里搜同一批结果。 */
@Component
public class MallSearchFeed implements SearchPorts.Products {

    private final MallCatalogReadService service;

    public MallSearchFeed(MallCatalogReadService service) {
        this.service = service;
    }

    @Override
    public SearchPorts.Found search(String cityCode, String keyword, int limit) {
        MallProductPageView page = service.listProducts(new MallProductSearch(
                cityCode, null, keyword, null, null, null, null, null, null, 1, limit));
        return new SearchPorts.Found(page.total(), page.items());
    }
}
