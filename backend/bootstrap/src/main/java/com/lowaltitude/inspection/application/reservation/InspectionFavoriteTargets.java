package com.lowaltitude.inspection.application.reservation;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import com.lowaltitude.inspection.favorite.application.FavoriteTargetType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 收藏列表里的巡检服务（计划 08 S5-6）：按当前城市读服务详情，已下架、这个城市不提供或城市没开通时读不到。
 * 价格照目录卡片：起步价“¥x /次起”，按项目报价没有金额；说明是服务商名称。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class InspectionFavoriteTargets {

    static final String STARTING_PRICE = "STARTING_PRICE";

    @Bean
    FavoritePorts.TargetReader inspectionFavoriteReader(InspectionServiceCatalogReadService catalog) {
        return reader(catalog);
    }

    static FavoritePorts.TargetReader reader(InspectionServiceCatalogReadService catalog) {
        return new FavoritePorts.TargetReader() {
            @Override
            public FavoriteTargetType type() {
                return FavoriteTargetType.INSPECTION_SERVICE;
            }

            @Override
            public Optional<FavoritePorts.Target> find(UUID targetId, String cityCode) {
                InspectionServiceDetailView service;
                try {
                    service = catalog.findDetail(targetId, cityCode);
                } catch (InspectionServiceNotFoundException | InspectionCatalogCityNotEnabledException gone) {
                    return Optional.empty();
                }
                return Optional.of(target(service));
            }
        };
    }

    static FavoritePorts.Target target(InspectionServiceDetailView service) {
        ServicePriceView price = service.price();
        FavoritePorts.Price shown;
        if (price == null || price.startingAmountMinor() == null) {
            shown = FavoritePorts.Price.quote();
        } else {
            String unit = price.unitLabel() == null || price.unitLabel().isBlank() ? null : "/" + price.unitLabel().trim();
            shown = FavoritePorts.Price.amount(price.startingAmountMinor(), STARTING_PRICE.equals(price.mode()), unit);
        }
        return new FavoritePorts.Target(service.name(), service.provider() == null ? null : service.provider().name(),
                null, shown, service.coverImage() == null ? null : service.coverImage().url(), null);
    }
}
