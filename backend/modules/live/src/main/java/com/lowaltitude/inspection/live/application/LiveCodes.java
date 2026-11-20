package com.lowaltitude.inspection.live.application;

// 直播的稳定编码（契约 contracts/openapi-live.yaml），与 V001 迁移里的 CHECK 约束一致。

/** SCHEDULED 预告；LIVE 直播中；ENDED 已结束。 */
enum LiveRoomStatus {
    SCHEDULED,
    LIVE,
    ENDED
}

/** 讲解项是平台内的什么：商品、专业服务、巡检服务、活动、场馆。 */
enum LiveItemType {
    PRODUCT,
    PRO_SERVICE,
    INSPECTION_SERVICE,
    ACTIVITY,
    VENUE
}

/** VIEWER 观众；HOST 主播（主办机构的操作员）。 */
enum LiveCommentSide {
    VIEWER,
    HOST
}
