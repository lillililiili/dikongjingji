package com.lowaltitude.inspection.mall.application;

import java.util.List;

/**
 * 商品列表的查询条件，原样来自请求；由 {@link MallCatalogReadService} 校验。
 * 价格区间按该城市的最低标价比较，单位是分；设置任一端后，没有标价的询价商品不出现。
 */
public record MallProductSearch(
        String cityCode,
        String category,
        String keyword,
        List<String> saleModes,
        List<String> experienceBadges,
        List<String> merchantKinds,
        Long minPriceMinor,
        Long maxPriceMinor,
        String sort,
        int page,
        int size
) {
}
