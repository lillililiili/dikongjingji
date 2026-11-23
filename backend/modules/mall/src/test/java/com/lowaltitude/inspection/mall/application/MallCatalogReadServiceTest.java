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

    private static final UUID COLLECTING = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID NOT_COLLECTING = UUID.fromString("c1000000-0000-4000-8000-000000000006");

    private final FakeStore store = new FakeStore();
    /** 问过交易模块的商家（按顺序）：每次请求每家只问一次。 */
    private final List<UUID> asked = new java.util.ArrayList<>();
    private final MallCatalogReadService service = new MallCatalogReadService(store, merchantId -> {
        asked.add(merchantId);
        return COLLECTING.equals(merchantId);
    });

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
    void searchAndFiltersAreValidatedAndHandedToTheStore() {
        service.listProducts(new MallProductSearch(
                "370500", "ROBOT", "  100%_编程\\  ",
                List.of("RENT", "BUY", "BUY"), List.of("VENUE_DISPLAY"), List.of(" LOCAL_STORE "),
                10000L, 500000L, null, 1, 20));

        assertThat(store.criteria).isEqualTo(new MallProductCriteria(
                "370500", "ROBOT", "%100\\%\\_编程\\\\%",
                List.of("BUY", "RENT"), List.of("VENUE_DISPLAY"), List.of("LOCAL_STORE"),
                10000L, 500000L));
    }

    @Test
    void anEmptySearchMeansNoKeywordAndNoFilters() {
        service.listProducts(new MallProductSearch(
                "370500", null, "   ", List.of(), null, null, null, null, null, 1, 20));

        assertThat(store.criteria).isEqualTo(new MallProductCriteria(
                "370500", "ALL", null, List.of(), List.of(), List.of(), null, null));
    }

    @Test
    void rejectsInvalidSearchAndFilterValues() {
        assertInvalid(() -> search("x".repeat(51), null, null, null, null, null));
        assertInvalid(() -> search(null, List.of("GIFT"), null, null, null, null));
        assertInvalid(() -> search(null, null, List.of(" "), null, null, null));
        assertInvalid(() -> search(null, null, null, List.of("ANYONE"), null, null));
        assertInvalid(() -> search(null, List.of("BUY", "BUY", "BUY", "BUY", "BUY", "BUY", "BUY", "BUY", "BUY"),
                null, null, null, null));
        assertInvalid(() -> search(null, null, null, null, -1L, null));
        assertInvalid(() -> search(null, null, null, null, null, -1L));
        assertInvalid(() -> search(null, null, null, null, 200L, 100L));
        assertThat(store.listQueried).isFalse();
    }

    private void search(
            String keyword, List<String> saleModes, List<String> badges, List<String> merchantKinds,
            Long minPrice, Long maxPrice
    ) {
        service.listProducts(new MallProductSearch(
                "370500", null, keyword, saleModes, badges, merchantKinds, minPrice, maxPrice, null, 1, 20));
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
                129900L, "CNY", "ITEM", "东营现货", " ", "示例机器人商家 东营仓", 12));
        store.specs = List.of(new SpecRow("适用年龄", "8 岁以上"));
        store.packItems = List.of(new PackItemRow("机器人主机", 1));

        MallProductDetailView view = service.findProduct(PRODUCT_ID, "370500");

        assertThat(view.coverImage()).isNull();
        assertThat(view.offers()).singleElement().satisfies(offer -> {
            assertThat(offer.merchant().kind()).isEqualTo(MallMerchantKind.FACTORY_DIRECT);
            assertThat(offer.amountMinor()).isEqualTo(129900L);
            assertThat(offer.shippingNote()).isNull();
            assertThat(offer.fulfilledFrom()).isEqualTo("示例机器人商家 东营仓");
            assertThat(offer.onlinePayment()).isFalse();
        });
        assertThat(asked).hasSize(1);
        assertThat(view.specs()).containsExactly(new MallSpecView("适用年龄", "8 岁以上"));
        assertThat(view.packItems()).containsExactly(new MallPackItemView("机器人主机", 1));
        assertThat(store.offerCity).isEqualTo("370500");
        assertThat(view.offers().getFirst().stockQuantity()).isEqualTo(12);
        // 运营端没录卖点、图集、图文时都是空的。
        assertThat(view.sellingPoints()).isEmpty();
        assertThat(view.gallery()).isEmpty();
        assertThat(view.introBlocks()).isEmpty();
    }

    // 1.3.0：卖点、全部启用规格、图集和图文介绍原样给出；图片只认 https，没有 https 图的图片段整段不给。
    @Test
    void detailCarriesSellingPointsSkusGalleryAndIntroduction() {
        store.product = Optional.of(new ProductRow(
                PRODUCT_ID, "ROBOT", "编程机器人探索套装", "图形化编程入门", null, "BUY", null));
        store.offers = List.of(offer(UUID.randomUUID(), "示例机器人商家", 129900));
        UUID standard = UUID.randomUUID();
        UUID classroom = UUID.randomUUID();
        store.sellingPoints = List.of("图形化编程", "避障练习");
        store.skus = List.of(new MallRows.SkuRow(standard, "标准套装"), new MallRows.SkuRow(classroom, "教室套装"));
        store.gallery = List.of("https://media.example.com/a.jpg", "http://127.0.0.1:18420/api/v1/mall/media/x.jpg");
        store.intro = List.of(
                new MallRows.IntroBlockRow("TEXT", "图形化编程，8 岁以上", null),
                new MallRows.IntroBlockRow("IMAGE", null, "https://media.example.com/b.jpg"),
                new MallRows.IntroBlockRow("IMAGE", "开发环境的图", "http://127.0.0.1:18420/api/v1/mall/media/y.jpg"));

        MallProductDetailView view = service.findProduct(PRODUCT_ID, "370500");

        assertThat(view.sellingPoints()).containsExactly("图形化编程", "避障练习");
        assertThat(view.skus()).extracting(MallSkuView::skuId).containsExactly(standard, classroom);
        assertThat(view.gallery()).extracting(MallMediaView::url).containsExactly("https://media.example.com/a.jpg");
        assertThat(view.introBlocks()).extracting(MallIntroBlockView::type).containsExactly("TEXT", "IMAGE");
        assertThat(view.introBlocks().get(1).image().url()).isEqualTo("https://media.example.com/b.jpg");
        assertThat(view.introBlocks().get(0).image()).isNull();
    }

    // F3 的缺口：租赁、询价、购买都要商家能在线收款，详情里每个供给说清楚，页面不先选上收不了款的那家。
    @Test
    void eachOfferSaysWhetherItsMerchantCanTakePaymentOnlineAskingOncePerMerchant() {
        store.product = Optional.of(new ProductRow(PRODUCT_ID, "ROBOT", "巡检机器狗", null, null, "RENT", null));
        store.offers = List.of(
                offer(NOT_COLLECTING, "示例设备租赁商", 36000L),
                offer(COLLECTING, "示例机器人商家", 38000L),
                offer(NOT_COLLECTING, "示例设备租赁商", 72000L));

        MallProductDetailView view = service.findProduct(PRODUCT_ID, "370500");

        assertThat(view.offers()).extracting(offer -> offer.merchant().name(), MallOfferView::onlinePayment)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("示例设备租赁商", false),
                        org.assertj.core.api.Assertions.tuple("示例机器人商家", true),
                        org.assertj.core.api.Assertions.tuple("示例设备租赁商", false));
        assertThat(asked).containsExactly(NOT_COLLECTING, COLLECTING);
        // 下一次请求重新问（收款账户可能刚登记或停用）。
        service.findProduct(PRODUCT_ID, "370500");
        assertThat(asked).hasSize(4);
        // 列表不问。
        store.cards = List.of();
        service.listProducts("370500", null, null, 1, 20);
        assertThat(asked).hasSize(4);
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

    private static OfferRow offer(UUID merchantId, String merchantName, long amountMinor) {
        return new OfferRow(UUID.randomUUID(), UUID.randomUUID(), "标准版", merchantId, merchantName, "FACTORY_DIRECT",
                amountMinor, "CNY", "DAY", null, null, null, null);
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
        List<String> sellingPoints = List.of();
        List<MallRows.SkuRow> skus = List.of();
        List<String> gallery = List.of();
        List<MallRows.IntroBlockRow> intro = List.of();
        String category;
        String sort;
        String offerCity;
        int limit;
        int offset;
        boolean listQueried;
        MallProductCriteria criteria;

        @Override
        public long countProducts(MallProductCriteria criteria) {
            listQueried = true;
            this.category = criteria.category();
            this.criteria = criteria;
            return total;
        }

        @Override
        public List<ProductCardRow> findProducts(
                MallProductCriteria criteria, String sort, int limit, int offset
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

        @Override
        public List<String> findSellingPoints(UUID productId) {
            return sellingPoints;
        }

        @Override
        public List<MallRows.SkuRow> findSkus(UUID productId) {
            return skus;
        }

        @Override
        public List<String> findGallery(UUID productId) {
            return gallery;
        }

        @Override
        public List<MallRows.IntroBlockRow> findIntroBlocks(UUID productId) {
            return intro;
        }
    }
}
