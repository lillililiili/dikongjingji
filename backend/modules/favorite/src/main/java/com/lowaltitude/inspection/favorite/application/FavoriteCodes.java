package com.lowaltitude.inspection.favorite.application;

// 收藏的稳定编码（契约 contracts/openapi-favorite.yaml）。

/** 收藏页的四个页签：商品；服务（巡检、专业服务）；活动（活动、场馆）；内容。 */
enum FavoriteGroup {
    GOODS,
    SERVICE,
    EVENT,
    CONTENT
}
