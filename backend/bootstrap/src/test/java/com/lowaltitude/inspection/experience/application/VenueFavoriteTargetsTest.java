package com.lowaltitude.inspection.experience.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import org.junit.jupiter.api.Test;

/** 收藏列表里的场馆（计划 08 S5-6）：最低的项目价，几个项目价不同时是起步价；都免费是免费；没有项目不写价格。 */
class VenueFavoriteTargetsTest {

    @Test
    void theLowestProjectPriceStartsTheRange() {
        FavoritePorts.Target target = VenueFavoriteTargets.target(venue(List.of(project(6_800), project(0),
                project(12_800))));

        assertThat(target.title()).isEqualTo("东营低空体验馆");
        assertThat(target.meta()).isEqualTo("示例运营机构");
        assertThat(target.tag()).isEqualTo("场馆");
        assertThat(target.price()).isEqualTo(FavoritePorts.Price.amount(0, true, "/人"));
    }

    @Test
    void allFreeProjectsAreFreeAndNoProjectsHaveNoPrice() {
        assertThat(VenueFavoriteTargets.target(venue(List.of(project(0), project(0)))).price())
                .isEqualTo(FavoritePorts.Price.free());
        assertThat(VenueFavoriteTargets.target(venue(List.of())).price()).isNull();
    }

    private static VenueDetailView venue(List<VenueProjectView> projects) {
        return new VenueDetailView(UUID.randomUUID(), "东营低空体验馆", null, null, "东营区示例路 1 号", null, null, null,
                new VenueOperatorView(UUID.randomUUID(), "示例运营机构"), projects, List.of(), 0);
    }

    private static VenueProjectView project(long price) {
        return new VenueProjectView(UUID.randomUUID(), "无人机模拟飞行", 30, null, 6, null, price, "CNY", price > 0);
    }
}
