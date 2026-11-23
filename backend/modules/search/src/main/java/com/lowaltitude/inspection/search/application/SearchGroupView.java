package com.lowaltitude.inspection.search.application;

import java.util.List;

/** 一组结果；items 的每一项是那个模块列表自己的卡片（服务组是 {@link SearchServiceHitView}）。 */
public record SearchGroupView(long total, List<?> items) {

    public SearchGroupView {
        items = List.copyOf(items);
    }
}
