package com.lowaltitude.inspection.mall.application;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper.OfferRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租赁询价（F3）用的只读入口：一个商城供给能不能租、谁出租、参考价多少。别的模块不直接读商城的表，
 * 由启动模块把它接到租赁模块的端口上。
 */
public class MallRentalOffers {

    private final MallCartMapper mapper;

    MallRentalOffers(MallCartMapper mapper) {
        this.mapper = mapper;
    }

    /** 这个供给的租赁信息；不存在时为空。rentable 为 false 表示现在不能租（下架、停售、不是租赁商品）。 */
    @Transactional(readOnly = true)
    public Optional<RentalOffer> find(UUID offerId) {
        if (offerId == null) {
            return Optional.empty();
        }
        OfferRow row = mapper.findOffer(offerId);
        if (row == null) {
            return Optional.empty();
        }
        boolean rentable = "PUBLISHED".equals(row.productStatus())
                && "ACTIVE".equals(row.skuStatus())
                && "ACTIVE".equals(row.offerStatus())
                && MallSaleMode.RENT.name().equals(row.saleMode());
        String city = row.offerCityCode() == null || row.offerCityCode().isBlank() ? null : row.offerCityCode().trim();
        return Optional.of(new RentalOffer(row.offerId(), row.productId(), row.productName(), row.skuName(),
                row.coverUrl(), row.merchantId(), row.merchantName(), row.merchantKind(), city,
                row.unitAmountMinor(), row.priceUnit(), row.fulfilledFrom(), rentable));
    }

    /**
     * @param cityCode            供给所在城市；为空表示全国
     * @param referenceAmountMinor 参考价（分），如“¥380/天 起”；没有标价时为空
     * @param priceUnit           DAY、HALF_DAY 或 ITEM
     */
    public record RentalOffer(
            UUID offerId,
            UUID productId,
            String productName,
            String skuName,
            String coverUrl,
            UUID merchantId,
            String merchantName,
            String merchantKind,
            String cityCode,
            Long referenceAmountMinor,
            String priceUnit,
            String fulfilledFrom,
            boolean rentable
    ) {
    }
}
