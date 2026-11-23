package com.lowaltitude.inspection.mall.application;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import com.lowaltitude.inspection.favorite.application.FavoriteTargetType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 收藏列表里的商品（计划 08 S5-6）：按当前城市读商品详情，这个城市没有在售商家或商品已下架时读不到。
 * 价格取在售商家里的最低价，几个价不同或是租赁时是起步价；说明照商城卡片的写法（“3 家在售”“租赁 · 1 家”“2 家可询价”）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class MallFavoriteTargets {

    @Bean
    FavoritePorts.TargetReader mallFavoriteReader(MallCatalogReadService catalog) {
        return reader(catalog);
    }

    static FavoritePorts.TargetReader reader(MallCatalogReadService catalog) {
        return new FavoritePorts.TargetReader() {
            @Override
            public FavoriteTargetType type() {
                return FavoriteTargetType.PRODUCT;
            }

            @Override
            public Optional<FavoritePorts.Target> find(UUID targetId, String cityCode) {
                MallProductDetailView product;
                try {
                    product = catalog.findProduct(targetId, cityCode);
                } catch (MallProductNotFoundException gone) {
                    return Optional.empty();
                }
                return Optional.of(target(product));
            }
        };
    }

    static FavoritePorts.Target target(MallProductDetailView product) {
        long sellers = product.offers().stream().map(offer -> offer.merchant().organizationId()).distinct().count();
        String meta = switch (product.saleMode()) {
            case RENT -> "租赁 · " + sellers + " 家";
            case INQUIRY -> sellers + " 家可询价";
            case BUY -> sellers + " 家在售";
        };
        return new FavoritePorts.Target(product.name(), meta, null, price(product),
                product.coverImage() == null ? null : product.coverImage().url(), product.category().name());
    }

    private static FavoritePorts.Price price(MallProductDetailView product) {
        MallOfferView lowest = product.offers().stream()
                .filter(offer -> offer.amountMinor() != null)
                .min(java.util.Comparator.comparingLong(MallOfferView::amountMinor))
                .orElse(null);
        if (lowest == null) {
            return FavoritePorts.Price.quote();
        }
        long distinct = product.offers().stream().map(MallOfferView::amountMinor).distinct().count();
        boolean from = distinct > 1 || product.saleMode() == MallSaleMode.RENT;
        String unit = switch (lowest.unit()) {
            case DAY -> "/天";
            case HALF_DAY -> "/半天";
            case ITEM -> null;
        };
        return FavoritePorts.Price.amount(lowest.amountMinor(), from, unit);
    }
}
