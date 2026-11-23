package com.lowaltitude.inspection.search.application;

/** 服务组的一项：巡检服务卡或专业服务卡，另一个为 null。 */
public record SearchServiceHitView(SearchServiceKind kind, Object inspection, Object proService) {

    static SearchServiceHitView inspection(Object card) {
        return new SearchServiceHitView(SearchServiceKind.INSPECTION, card, null);
    }

    static SearchServiceHitView proService(Object card) {
        return new SearchServiceHitView(SearchServiceKind.PRO_SERVICE, null, card);
    }
}
