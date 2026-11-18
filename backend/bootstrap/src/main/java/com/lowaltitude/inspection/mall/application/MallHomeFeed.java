package com.lowaltitude.inspection.mall.application;

import java.util.List;

import org.springframework.stereotype.Component;

/** 给首页推荐用的商品入口：本城在售商品的前几件，和商城频道默认排序一致。 */
@Component
public class MallHomeFeed {

    private final MallCatalogReadService service;

    public MallHomeFeed(MallCatalogReadService service) {
        this.service = service;
    }

    public List<?> featured(String cityCode, int size) {
        return service.listProducts(cityCode, null, null, 1, size).items();
    }
}
