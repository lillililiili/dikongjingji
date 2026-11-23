package com.lowaltitude.inspection.favorite.application;

/**
 * 收藏的对象（契约 FavoriteTargetType，与 V001 迁移的 CHECK 约束一致）。各模块在启动模块里为自己的类型接一个
 * {@link FavoritePorts.TargetReader}。
 */
public enum FavoriteTargetType {
    PRODUCT(FavoriteGroup.GOODS),
    INSPECTION_SERVICE(FavoriteGroup.SERVICE),
    PRO_SERVICE(FavoriteGroup.SERVICE),
    ACTIVITY(FavoriteGroup.EVENT),
    VENUE(FavoriteGroup.EVENT),
    CONTENT(FavoriteGroup.CONTENT);

    private final FavoriteGroup group;

    FavoriteTargetType(FavoriteGroup group) {
        this.group = group;
    }

    FavoriteGroup group() {
        return group;
    }
}
