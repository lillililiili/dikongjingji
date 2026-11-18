package com.lowaltitude.inspection.mall.application;

import java.util.List;

/** 校验后的查询条件。keyword 已转成 ILIKE 模式；列表为空表示不限。 */
public record MallProductCriteria(
        String cityCode,
        String category,
        String keyword,
        List<String> saleModes,
        List<String> experienceBadges,
        List<String> merchantKinds,
        Long minPriceMinor,
        Long maxPriceMinor
) {
}
