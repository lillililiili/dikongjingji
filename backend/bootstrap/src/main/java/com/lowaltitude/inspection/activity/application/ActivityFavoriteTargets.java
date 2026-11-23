package com.lowaltitude.inspection.activity.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import com.lowaltitude.inspection.favorite.application.FavoriteTargetType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 收藏列表里的活动（计划 08 S5-6）：按当前城市读活动详情。价格按人、免费或咨询报价；说明是最近一场还没结束的场次的
 * 北京日期（原型 Favorites 写“11/15”），都结束了就不写。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ActivityFavoriteTargets {

    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Bean
    FavoritePorts.TargetReader activityFavoriteReader(ActivityCatalogReadService catalog) {
        return reader(catalog, Clock.systemUTC());
    }

    static FavoritePorts.TargetReader reader(ActivityCatalogReadService catalog, Clock clock) {
        return new FavoritePorts.TargetReader() {
            @Override
            public FavoriteTargetType type() {
                return FavoriteTargetType.ACTIVITY;
            }

            @Override
            public Optional<FavoritePorts.Target> find(UUID targetId, String cityCode) {
                ActivityDetailView activity;
                try {
                    activity = catalog.findDetail(targetId, cityCode);
                } catch (ActivityNotFoundException gone) {
                    return Optional.empty();
                }
                return Optional.of(target(activity, OffsetDateTime.now(clock)));
            }
        };
    }

    static FavoritePorts.Target target(ActivityDetailView activity, OffsetDateTime now) {
        ActivityPriceView price = activity.price();
        FavoritePorts.Price shown;
        if (price == null || price.mode() == ActivityPriceMode.INQUIRY) {
            shown = FavoritePorts.Price.quote();
        } else if (price.mode() == ActivityPriceMode.FREE || price.amountMinor() == null) {
            shown = FavoritePorts.Price.free();
        } else {
            shown = FavoritePorts.Price.amount(price.amountMinor(), false, "/人");
        }
        String meta = activity.sessions().stream()
                .filter(session -> session.endsAt() == null || session.endsAt().isAfter(now))
                .map(ActivitySessionView::startsAt)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(startsAt -> {
                    var day = startsAt.atZoneSameInstant(BEIJING);
                    return day.getMonthValue() + "/" + day.getDayOfMonth();
                })
                .orElse(null);
        return new FavoritePorts.Target(activity.title(), meta, null, shown,
                activity.coverImage() == null ? null : activity.coverImage().url(), null);
    }
}
