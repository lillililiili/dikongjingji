package com.lowaltitude.inspection.favorite.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 收藏接口的返回对象（契约 contracts/openapi-favorite.yaml 的 schemas，字段一一对应）。

/** 这个对象收藏了没有；没收藏时 favoritedAt 为 null。 */
record FavoriteStateView(FavoriteTargetType targetType, UUID targetId, boolean favorited, OffsetDateTime favoritedAt) {
}

/** PUT 的结果：created 为 true 时回 201。 */
record FavoritePut(FavoriteStateView state, boolean created) {
}

record FavoritePriceView(FavoritePorts.PriceMode mode, Long amountMinor, boolean from, String unitLabel) {
}

/** 一条收藏。available 为 false 时只有收藏时记下的标题，其余为 null。 */
record FavoriteItemView(
        FavoriteTargetType targetType,
        UUID targetId,
        OffsetDateTime favoritedAt,
        boolean available,
        String title,
        String meta,
        String tag,
        FavoritePriceView price,
        String coverUrl,
        String productCategory
) {
}

record FavoriteCountsView(long goods, long service, long event, long content) {
}

record FavoritePageView(int page, int size, long total, boolean hasMore, FavoriteCountsView counts,
                        List<FavoriteItemView> items) {
}
