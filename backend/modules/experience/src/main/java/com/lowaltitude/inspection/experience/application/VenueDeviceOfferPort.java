package com.lowaltitude.inspection.experience.application;

import java.util.Optional;
import java.util.UUID;

/**
 * 馆内设备关联的商城商品在场馆所在城市怎么卖（S4-4，原型画板 VenueDevices 的价格和“N 家在售”）。
 * 体验模块只存商品 ID，不读商城的表：由启动模块接到商城模块的商品读取。
 */
@FunctionalInterface
public interface VenueDeviceOfferPort {

    /** 这件商品在这个城市没有在售（或商品不存在、已下架）时为空。 */
    Optional<VenueDeviceOffer> offer(UUID productId, String cityCode);

    /** 没接商城时：设备一律不带商品。 */
    static VenueDeviceOfferPort none() {
        return (productId, cityCode) -> Optional.empty();
    }

    /** 交易方式，与商城的编码一致：购买、租赁、询价。 */
    enum SaleMode {
        BUY,
        RENT,
        INQUIRY
    }

    /** 计价单位，与商城的编码一致：按件、按天、按半天。 */
    enum PriceUnit {
        ITEM,
        DAY,
        HALF_DAY
    }

    /**
     * @param fromAmountMinor 在售商家里的最低价（分）；询价商品为 null
     * @param multiplePrices  在售商家的价格不止一种（页面在价格后写“起”）
     * @param sellerCount     在这个城市在售的商家数
     */
    record VenueDeviceOffer(
            SaleMode saleMode,
            Long fromAmountMinor,
            PriceUnit priceUnit,
            boolean multiplePrices,
            long sellerCount
    ) {
    }
}
