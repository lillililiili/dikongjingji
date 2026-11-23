package com.lowaltitude.inspection.proservice.application;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import com.lowaltitude.inspection.favorite.application.FavoriteTargetType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 收藏列表里的专业服务（计划 08 S5-6）：按当前城市读服务详情。起步价的单位在目录里写成“/天起”，
 * 这里拆成单位“/天”和起步价标记；按项目报价没有金额。说明是服务商名称。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ProServiceFavoriteTargets {

    @Bean
    FavoritePorts.TargetReader proServiceFavoriteReader(ProServiceReadService catalog) {
        return reader(catalog);
    }

    static FavoritePorts.TargetReader reader(ProServiceReadService catalog) {
        return new FavoritePorts.TargetReader() {
            @Override
            public FavoriteTargetType type() {
                return FavoriteTargetType.PRO_SERVICE;
            }

            @Override
            public Optional<FavoritePorts.Target> find(UUID targetId, String cityCode) {
                ProServiceDetailView service;
                try {
                    service = catalog.detail(targetId, cityCode);
                } catch (ProServiceNotFoundException gone) {
                    return Optional.empty();
                }
                return Optional.of(target(service));
            }
        };
    }

    static FavoritePorts.Target target(ProServiceDetailView service) {
        ProServicePriceView price = service.price();
        FavoritePorts.Price shown;
        if (price == null || price.mode() == ProServicePriceMode.PROJECT || price.amountMinor() == null) {
            shown = FavoritePorts.Price.quote();
        } else {
            String unit = price.unit() == null ? "" : price.unit().trim();
            if (unit.endsWith("起")) {
                unit = unit.substring(0, unit.length() - 1).trim();
            }
            shown = FavoritePorts.Price.amount(price.amountMinor(), true, unit.isEmpty() ? null : unit);
        }
        return new FavoritePorts.Target(service.title(), service.provider() == null ? null : service.provider().name(),
                null, shown, service.coverUrl(), null);
    }
}
