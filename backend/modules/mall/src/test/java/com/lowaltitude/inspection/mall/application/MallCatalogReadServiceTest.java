package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.mall.application.MallRows.OfferRow;
import com.lowaltitude.inspection.mall.application.MallRows.PackItemRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductCardRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductRow;
import com.lowaltitude.inspection.mall.application.MallRows.SpecRow;
import org.junit.jupiter.api.Test;

class MallCatalogReadServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("c2000000-0000-4000-8000-000000000101");

    private final FakeStore store = new FakeStore();
    private final MallCatalogReadService service = new MallCatalogReadService(store);

    @Test
    void listPassesCategorySortAndPagingToTheStore() {
        store.total = 5;
        store.cards = List.of(card(129900L, "ITEM", 3, 3, "BUY", "ONLINE_EXPERIENCE"));

        MallProductPageView page = service.listProducts("370500", " ROBOT ", "PRICE_ASC", 2, 2);

        assertThat(store.category).isEqualTo("ROBOT");
        assertThat(store.sort).isEqualTo("PRICE_ASC");
        assertThat(store.limit).isEqualTo(2);
        assertThat(store.offset).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.category()).isEqualTo(MallCategory.ROBOT);
            assertThat(item.saleMode()).isEqualTo(MallSaleMode.BUY);
            assertThat(item.experienceBadge()).isEqualTo(MallExperienceBadge.ONLINE_EXPERIENCE);
            assertThat(item.price()).isEqualTo(new MallListPriceView(129900L, MallPriceUnit.ITEM, true));
            assertThat(item.sellerCount()).isEqualTo(3);
        });
    }

    @Test
    void listDefaultsToAllCategoriesInTheOperatorOrder() {
        service.listProducts("370500", null, " ", 1, 20);

        assertThat(store.category).isEqualTo("ALL");
        assertThat(store.sort).isEqualTo("DEFAULT");
    }

    @Test
    void aSinglePriceIsNotARangeAndAnInquiryHasNoAmount() {
        store.cards = List.of(
                card(32900L, "ITEM", 1, 2, "BUY", null),
                card(null, "ITEM", 0, 2, "INQUIRY", null),
                card(38000L, "DAY", 1, 1, "RENT", "VENUE_DISPLAY"));

        List<MallProductCardView> items = service.listProducts("370500", null, null, 1, 20).items();

        assertThat(items.get(0).price().multiplePrices()).isFalse();
        assertThat(items.get(0).experienceBadge()).isNull();
        assertThat(items.get(1).price().fromAmountMinor()).isNull();
        assertThat(items.get(1).saleMode()).isEqualTo(MallSaleMode.INQUIRY);
        assertThat(items.get(2).price().unit()).isEqualTo(MallPriceUnit.DAY);
    }

    @Test
    void rejectsInvalidListArguments() {
        assertInvalid(() -> service.listProducts(null, null, null, 1, 20));
        assertInvalid(() -> service.listProducts("x", null, null, 1, 20));
        assertInvalid(() -> service.listProducts("370500", "TOY", null, 1, 20));
        assertInvalid(() -> service.listProducts("370500", null, "RANDOM", 1, 20));
        assertInvalid(() -> service.listProducts("370500", null, null, 0, 20));
        assertInvalid(() -> service.listProducts("370500", null, null, 1, 51));
        assertThat(store.listQueried).isFalse();
    }

    @Test
    void detailCarriesOffersSpecsAndPackItems() {
        store.product = Optional.of(new ProductRow(
                PRODUCT_ID, "ROBOT", "编程机器人探索套装", "图形化编程入门", "http://insecure.example/a.jpg",
                "BUY", "ONLINE_EXPERIENCE"));
        store.offers = List.of(new OfferRow(
                UUID.randomUUID(), UUID.randomUUID(), "标准版",
                UUID.randomUUID(), "示例机器人商家", "FACTORY_DIRECT",
                129900L, "CNY", "ITEM", "东营现货", " ", "示例机器人商家 东营仓"));
        store.specs = List.of(new SpecRow("适用年龄", "8 岁以上"));
        store.packItems = List.of(new PackItemRow("机器人主机", 1));

        MallProductDetailView view = service.findProduct(PRODUCT_ID, "370500");

        assertThat(view.coverImage()).isNull();
        assertThat(view.offers()).singleElement().satisfies(offer -> {
            assertThat(offer.merchant().kind()).isEqualTo(MallMerchantKind.FACTORY_DIRECT);
            assertThat(offer.amountMinor()).isEqualTo(129900L);
            assertThat(offer.shippingNote()).isNull();
            assertThat(offer.fulfilledFrom()).isEqualTo("示例机器人商家 东营仓");
        });
        assertThat(view.specs()).containsExactly(new MallSpecView("适用年龄", "8 岁以上"));
        assertThat(view.packItems()).containsExactly(new MallPackItemView("机器人主机", 1));
        assertThat(store.offerCity).isEqualTo("370500");
    }

    @Test
    void aProductWithoutAnOfferInTheCityIsNotFound() {
        store.product = Optional.of(new ProductRow(PRODUCT_ID, "ROBOT", "仅济南在售", null, null, "BUY", null));
        store.offers = List.of();

        assertThatThrownBy(() -> service.findProduct(PRODUCT_ID, "370500"))
                .isInstanceOf(MallProductNotFoundException.class);

        store.product = Optional.empty();
        assertThatThrownBy(() -> service.findProduct(PRODUCT_ID, "370500"))
                .isInstanceOf(MallProductNotFoundException.class);
        assertInvalid(() -> service.findProduct(null, "370500"));
        assertInvalid(() -> service.findProduct(PRODUCT_ID, null));
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
    }

    private static ProductCardRow card(
            Long from, String unit, long distinctPrices, long sellers, String saleMode, String badge
    ) {
        return new ProductCardRow(
                UUID.randomUUID(), "ROBOT", "示例商品", null, saleMode, badge, from, unit, distinctPrices, sellers);
    }

    private static final class FakeStore implements MallCatalogReadStore {
        long total;
        List<ProductCardRow> cards = List.of();
        Optional<ProductRow> product = Optional.empty();
        List<OfferRow> offers = List.of();
        List<SpecRow> specs = List.of();
        List<PackItemRow> packItems = List.of();
        String category;
        String sort;
        String offerCity;
        int limit;
        int offset;
        boolean listQueried;

        @Override
        public long countProducts(String cityCode, String category) {
            listQueried = true;
            this.category = category;
            return total;
        }

        @Override
        public List<ProductCardRow> findProducts(
                String cityCode, String category, String sort, int limit, int offset
        ) {
            this.sort = sort;
            this.limit = limit;
            this.offset = offset;
            return cards;
        }

        @Override
        public Optional<ProductRow> findProduct(UUID productId) {
            return product;
        }

        @Override
        public List<OfferRow> findOffers(UUID productId, String cityCode) {
            this.offerCity = cityCode;
            return offers;
        }

        @Override
        public List<SpecRow> findSpecs(UUID productId) {
            return specs;
        }

        @Override
        public List<PackItemRow> findPackItems(UUID productId) {
            return packItems;
        }
    }
}
