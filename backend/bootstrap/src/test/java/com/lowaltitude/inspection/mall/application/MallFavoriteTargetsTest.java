package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import com.lowaltitude.inspection.favorite.application.FavoriteTargetType;
import org.junit.jupiter.api.Test;

/** 收藏列表里的商品（计划 08 S5-6）：最低价、起步价、单位、在售商家数和类目照商城卡片的规则。 */
class MallFavoriteTargetsTest {

    private static final UUID PRODUCT = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID DEALER = UUID.fromString("c1000000-0000-4000-8000-000000000002");

    @Test
    void aProductShowsItsLowestPriceAndHowManyMerchantsSellIt() {
        FavoritePorts.Target target = MallFavoriteTargets.target(product(MallSaleMode.BUY, List.of(
                offer(ROBOT, 139_900L, MallPriceUnit.ITEM), offer(DEALER, 129_900L, MallPriceUnit.ITEM),
                offer(DEALER, 159_900L, MallPriceUnit.ITEM))));

        assertThat(target.title()).isEqualTo("编程机器人探索套装");
        assertThat(target.meta()).isEqualTo("2 家在售");
        assertThat(target.price()).isEqualTo(FavoritePorts.Price.amount(129_900, true, null));
        assertThat(target.coverUrl()).isEqualTo("https://img.example.com/kit.jpg");
        assertThat(target.productCategory()).isEqualTo("ROBOT");
    }

    @Test
    void rentalsStartFromTheirDayPriceAndInquiriesHaveNoAmount() {
        FavoritePorts.Target rental = MallFavoriteTargets.target(product(MallSaleMode.RENT,
                List.of(offer(ROBOT, 38_000L, MallPriceUnit.DAY))));
        FavoritePorts.Target inquiry = MallFavoriteTargets.target(product(MallSaleMode.INQUIRY,
                List.of(offer(ROBOT, null, MallPriceUnit.ITEM), offer(DEALER, null, MallPriceUnit.ITEM))));

        assertThat(rental.meta()).isEqualTo("租赁 · 1 家");
        assertThat(rental.price()).isEqualTo(FavoritePorts.Price.amount(38_000, true, "/天"));
        assertThat(inquiry.meta()).isEqualTo("2 家可询价");
        assertThat(inquiry.price()).isEqualTo(FavoritePorts.Price.quote());
    }

    @Test
    void aProductTheCityDoesNotSellIsNotFound() {
        MallCatalogReadService catalog = mock(MallCatalogReadService.class);
        when(catalog.findProduct(PRODUCT, "JINAN")).thenThrow(new MallProductNotFoundException());
        FavoritePorts.TargetReader reader = MallFavoriteTargets.reader(catalog);

        assertThat(reader.type()).isEqualTo(FavoriteTargetType.PRODUCT);
        assertThat(reader.find(PRODUCT, "JINAN")).isEmpty();
    }

    private static MallProductDetailView product(MallSaleMode mode, List<MallOfferView> offers) {
        return new MallProductDetailView(PRODUCT, MallCategory.ROBOT, "编程机器人探索套装", null,
                new MallMediaView("https://img.example.com/kit.jpg", "编程机器人探索套装"), mode, null, offers, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), true);
    }

    private static MallOfferView offer(UUID merchant, Long amount, MallPriceUnit unit) {
        return new MallOfferView(UUID.randomUUID(), UUID.randomUUID(), "标准套装",
                new MallMerchantView(merchant, "示例商家", MallMerchantKind.FACTORY_DIRECT), amount, "CNY", unit, null,
                null, null, true, null, null, null);
    }
}
