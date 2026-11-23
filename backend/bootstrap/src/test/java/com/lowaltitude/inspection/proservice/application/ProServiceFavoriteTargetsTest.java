package com.lowaltitude.inspection.proservice.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import org.junit.jupiter.api.Test;

/** 收藏列表里的专业服务（计划 08 S5-6）：“/天起”拆成单位和起步价；按项目报价没有金额。 */
class ProServiceFavoriteTargetsTest {

    @Test
    void aStartingPriceKeepsItsUnitWithoutTheFromWord() {
        FavoritePorts.Target target = ProServiceFavoriteTargets.target(service(
                new ProServicePriceView(ProServicePriceMode.PRICE_FROM, 80_000L, "/天起", null)));

        assertThat(target.title()).isEqualTo("飞行作业指导");
        assertThat(target.meta()).isEqualTo("示例航拍服务商");
        assertThat(target.price()).isEqualTo(FavoritePorts.Price.amount(80_000, true, "/天"));
    }

    @Test
    void aProjectQuoteHasNoAmount() {
        assertThat(ProServiceFavoriteTargets.target(service(
                new ProServicePriceView(ProServicePriceMode.PROJECT, null, null, "按项目报价"))).price())
                .isEqualTo(FavoritePorts.Price.quote());
    }

    private static ProServiceDetailView service(ProServicePriceView price) {
        return new ProServiceDetailView(UUID.randomUUID(), ProServiceType.AERIAL, null, "飞行作业指导", null, price, null,
                true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                new ProServiceProviderView(UUID.randomUUID(), "示例航拍服务商", null, null, true), List.of(), null);
    }
}
