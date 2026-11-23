package com.lowaltitude.inspection.favorite.application;

import java.util.Optional;
import java.util.UUID;

/** 收藏用到的别的模块，由启动模块接上；本模块不读别的模块的表。 */
public final class FavoritePorts {

    private FavoritePorts() {
    }

    /**
     * 一类对象的读取：按当前城市读一次，读不到（不存在、已下架、这个城市不提供）为空。只把“找不到”变成空，
     * 别的故障照常抛出，不能把读取失败说成已下架。
     */
    public interface TargetReader {

        FavoriteTargetType type();

        Optional<Target> find(UUID targetId, String cityCode);
    }

    /**
     * 列表上一条收藏要显示的东西。title 必填；meta、tag、price、coverUrl 可以为空；productCategory 只给商品
     * （商城类目编码，App 只让同一类目的商品对比）。
     */
    public record Target(String title, String meta, String tag, Price price, String coverUrl, String productCategory) {
    }

    /** 当前标价：AMOUNT 带分为单位的金额；from 为 true 表示起步价；unitLabel 如“/天”“/人”，按件为空。 */
    public record Price(PriceMode mode, Long amountMinor, boolean from, String unitLabel) {

        public static Price amount(long amountMinor, boolean from, String unitLabel) {
            return new Price(PriceMode.AMOUNT, amountMinor, from, unitLabel);
        }

        public static Price quote() {
            return new Price(PriceMode.QUOTE, null, false, null);
        }

        public static Price free() {
            return new Price(PriceMode.FREE, null, false, null);
        }
    }

    /** 契约 FavoritePriceMode。 */
    public enum PriceMode {
        AMOUNT,
        QUOTE,
        FREE
    }
}
