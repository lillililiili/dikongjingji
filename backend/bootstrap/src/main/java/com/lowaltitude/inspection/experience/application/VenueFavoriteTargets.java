package com.lowaltitude.inspection.experience.application;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import com.lowaltitude.inspection.favorite.application.FavoriteTargetType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 收藏列表里的场馆（计划 08 S5-6）：按当前城市读场馆详情。价格是可预约项目里最低的按人价（几个项目价不同时是起步价），
 * 都免费时是免费，没有项目时不写；说明是运营机构；小标签“场馆”（和活动放在同一个页签里）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class VenueFavoriteTargets {

    @Bean
    FavoritePorts.TargetReader venueFavoriteReader(ExperienceCatalogReadService catalog) {
        return reader(catalog);
    }

    static FavoritePorts.TargetReader reader(ExperienceCatalogReadService catalog) {
        return new FavoritePorts.TargetReader() {
            @Override
            public FavoriteTargetType type() {
                return FavoriteTargetType.VENUE;
            }

            @Override
            public Optional<FavoritePorts.Target> find(UUID targetId, String cityCode) {
                VenueDetailView venue;
                try {
                    venue = catalog.findVenue(targetId, cityCode);
                } catch (VenueNotFoundException gone) {
                    return Optional.empty();
                }
                return Optional.of(target(venue));
            }
        };
    }

    static FavoritePorts.Target target(VenueDetailView venue) {
        FavoritePorts.Price shown = null;
        if (!venue.projects().isEmpty()) {
            long lowest = venue.projects().stream().mapToLong(VenueProjectView::priceAmountMinor).min().orElse(0);
            long distinct = venue.projects().stream().map(VenueProjectView::priceAmountMinor).distinct().count();
            shown = lowest == 0 && distinct == 1
                    ? FavoritePorts.Price.free()
                    : FavoritePorts.Price.amount(lowest, distinct > 1, "/人");
        }
        return new FavoritePorts.Target(venue.name(), venue.operator() == null ? null : venue.operator().name(), "场馆",
                shown, venue.coverImage() == null ? null : venue.coverImage().url(), null);
    }
}
