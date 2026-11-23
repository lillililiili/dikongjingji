package com.lowaltitude.inspection.search.application;

/** 一次搜索的结果，字段与契约 SearchResult 逐个对应；不在范围里的组为 null。 */
public record SearchResultView(
        String cityCode,
        String keyword,
        SearchScope scope,
        SearchCorrectionView correction,
        SearchGroupView products,
        SearchGroupView services,
        SearchGroupView activities,
        SearchGroupView contents,
        SearchGroupView venues
) {
}
