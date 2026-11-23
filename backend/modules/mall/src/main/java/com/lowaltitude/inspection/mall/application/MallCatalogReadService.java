package com.lowaltitude.inspection.mall.application;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.lowaltitude.inspection.mall.application.MallRows.OfferRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductRow;

/**
 * 商城目录只读服务：类目商品列表和商品详情（契约 contracts/openapi-mall-catalog.yaml）。
 * 只展示在请求城市有有效供给的商品；价格、在售商家数都按该城市的供给计算。
 */
public final class MallCatalogReadService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_KEYWORD_LENGTH = 50;
    private static final int MAX_FILTER_VALUES = 8;
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    /** 查询里“不限类目”的占位值。 */
    private static final String ALL = "ALL";

    private final MallCatalogReadStore store;
    private final MallInquiryPorts.MerchantPayments payments;

    /** payments：商家能不能在线收款（交易模块的收款账户登记），给商品详情里每个供给的 onlinePayment。 */
    MallCatalogReadService(MallCatalogReadStore store, MallInquiryPorts.MerchantPayments payments) {
        this.store = store;
        this.payments = payments;
    }

    /** 不带搜索和筛选的列表。 */
    public MallProductPageView listProducts(
            String cityCode,
            String categoryCode,
            String sortCode,
            int page,
            int size
    ) {
        return listProducts(new MallProductSearch(
                cityCode, categoryCode, null, null, null, null, null, null, sortCode, page, size));
    }

    public MallProductPageView listProducts(MallProductSearch search) {
        requireCityCode(search.cityCode());
        int page = search.page();
        int size = search.size();
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        String category = isBlank(search.category())
                ? ALL
                : parse(search.category(), MallCategory::valueOf).name();
        MallProductSort sort = isBlank(search.sort())
                ? MallProductSort.DEFAULT
                : parse(search.sort(), MallProductSort::valueOf);
        Long minPrice = search.minPriceMinor();
        Long maxPrice = search.maxPriceMinor();
        if ((minPrice != null && minPrice < 0)
                || (maxPrice != null && maxPrice < 0)
                || (minPrice != null && maxPrice != null && minPrice > maxPrice)) {
            throw new IllegalArgumentException("price range is invalid");
        }
        MallProductCriteria criteria = new MallProductCriteria(
                search.cityCode(),
                category,
                keywordPattern(search.keyword()),
                codes(search.saleModes(), MallSaleMode::valueOf),
                codes(search.experienceBadges(), MallExperienceBadge::valueOf),
                codes(search.merchantKinds(), MallMerchantKind::valueOf),
                minPrice,
                maxPrice,
                search.merchantId());

        long total = store.countProducts(criteria);
        List<MallProductCardView> items = store
                .findProducts(criteria, sort.name(), size, Math.multiplyExact(page - 1, size))
                .stream()
                .map(row -> new MallProductCardView(
                        row.productId(),
                        MallCategory.valueOf(row.category()),
                        row.name(),
                        secureMedia(row.coverUrl(), row.name()),
                        MallSaleMode.valueOf(row.saleMode()),
                        row.experienceBadge() == null ? null : MallExperienceBadge.valueOf(row.experienceBadge()),
                        new MallListPriceView(
                                row.fromAmountMinor(),
                                MallPriceUnit.valueOf(row.priceUnit()),
                                row.distinctPriceCount() > 1),
                        row.sellerCount()))
                .toList();
        return new MallProductPageView(page, size, total, (long) page * size < total, items);
    }

    /** 空白当作没有关键词；% 和 _ 按字面匹配，不当通配符。 */
    private static String keywordPattern(String keyword) {
        if (isBlank(keyword)) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("keyword is too long");
        }
        return "%" + trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    /** 每个值都必须是已知编码；去重后按编码排序，空列表表示不限。 */
    private static <E extends Enum<E>> List<String> codes(List<String> values, Function<String, E> parser) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_FILTER_VALUES) {
            throw new IllegalArgumentException("too many filter values");
        }
        return values.stream()
                .map(value -> {
                    if (isBlank(value)) {
                        throw new IllegalArgumentException("filter value is invalid");
                    }
                    return parse(value, parser).name();
                })
                .distinct()
                .sorted()
                .toList();
    }

    public MallProductDetailView findProduct(UUID productId, String cityCode) {
        if (productId == null) {
            throw new IllegalArgumentException("product id is required");
        }
        requireCityCode(cityCode);
        ProductRow product = store.findProduct(productId).orElseThrow(MallProductNotFoundException::new);
        List<OfferRow> offers = store.findOffers(productId, cityCode);
        // 在这个城市没有供给的商品，列表里不出现，详情也不应能打开。
        if (offers.isEmpty()) {
            throw new MallProductNotFoundException();
        }
        // 每家商家在这次请求里只问一次交易模块。
        Map<UUID, Boolean> collecting = new HashMap<>();
        Map<UUID, MallRows.TradeTermsRow> terms = new HashMap<>();
        store.findTradeTerms(offers.stream().map(OfferRow::offerId).toList())
                .forEach(row -> terms.put(row.offerId(), row));
        return new MallProductDetailView(
                product.productId(),
                MallCategory.valueOf(product.category()),
                product.name(),
                blankToNull(product.summary()),
                secureMedia(product.coverUrl(), product.name()),
                MallSaleMode.valueOf(product.saleMode()),
                product.experienceBadge() == null ? null : MallExperienceBadge.valueOf(product.experienceBadge()),
                offers.stream()
                        .map(row -> new MallOfferView(
                                row.offerId(),
                                row.skuId(),
                                row.skuName(),
                                new MallMerchantView(
                                        row.merchantId(),
                                        row.merchantName(),
                                        MallMerchantKind.valueOf(row.merchantKind())),
                                row.amountMinor(),
                                row.currency() == null ? "CNY" : row.currency().trim(),
                                MallPriceUnit.valueOf(row.priceUnit()),
                                blankToNull(row.deliveryNote()),
                                blankToNull(row.shippingNote()),
                                blankToNull(row.fulfilledFrom()),
                                collecting.computeIfAbsent(row.merchantId(), payments::canCollect),
                                row.stockQuantity(),
                                invoiceIssuer(terms.get(row.offerId()), row.merchantName()),
                                aftersaleParty(terms.get(row.offerId()), row.merchantName())))
                        .toList(),
                store.findSpecs(productId).stream()
                        .map(row -> new MallSpecView(row.label(), row.value()))
                        .toList(),
                store.findPackItems(productId).stream()
                        .map(row -> new MallPackItemView(row.name(), row.quantity()))
                        .toList(),
                store.findSellingPoints(productId),
                store.findSkus(productId).stream()
                        .map(row -> new MallSkuView(row.skuId(), row.name()))
                        .toList(),
                store.findGallery(productId).stream()
                        .map(url -> secureMedia(url, product.name()))
                        .filter(java.util.Objects::nonNull)
                        .toList(),
                store.findIntroBlocks(productId).stream()
                        .map(row -> new MallIntroBlockView(
                                row.blockType(),
                                blankToNull(row.text()),
                                secureMedia(row.imageUrl(), product.name())))
                        // 图片段的地址不是 https 时整段不给（没有图的图片段没有意义）。
                        .filter(block -> !"IMAGE".equals(block.type()) || block.image() != null)
                        .toList(),
                store.findNoReasonReturn(productId));
    }

    /**
     * 1.4.0（S4-4）：下单时要记的交易条款——开票方、售后责任方（供给 → 商家资料 → 商家名称）和商品是否支持七天无理由。
     * 给交易模块的结算端口用；没查到的供给不在结果里。
     */
    public Map<UUID, MallTradeTermsView> tradeTerms(java.util.Collection<UUID> offerIds) {
        if (offerIds == null || offerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MallTradeTermsView> found = new HashMap<>();
        for (MallRows.TradeTermsRow row : store.findTradeTerms(List.copyOf(new java.util.LinkedHashSet<>(offerIds)))) {
            found.put(row.offerId(), new MallTradeTermsView(blankToNull(row.invoiceIssuer()),
                    blankToNull(row.aftersaleParty()), row.noReasonReturn()));
        }
        return found;
    }

    public record MallTradeTermsView(String invoiceIssuer, String aftersaleParty, boolean noReasonReturn) {
    }

    private static String invoiceIssuer(MallRows.TradeTermsRow terms, String merchantName) {
        String value = terms == null ? null : blankToNull(terms.invoiceIssuer());
        return value != null ? value : blankToNull(merchantName);
    }

    private static String aftersaleParty(MallRows.TradeTermsRow terms, String merchantName) {
        String value = terms == null ? null : blankToNull(terms.aftersaleParty());
        return value != null ? value : blankToNull(merchantName);
    }

    private static <E extends Enum<E>> E parse(String code, Function<String, E> parser) {
        try {
            return parser.apply(code.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("query value is invalid");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireCityCode(String cityCode) {
        if (cityCode == null || !cityCode.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /** 只放行带主机名的 HTTPS 地址（AGENTS.md §11）；其余当作没有图片。 */
    static MallMediaView secureMedia(String url, String alt) {
        if (isBlank(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            return new MallMediaView(uri.toString(), alt);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}

record MallMediaView(String url, String alt) {
}

record MallListPriceView(Long fromAmountMinor, MallPriceUnit unit, boolean multiplePrices) {
}

record MallProductCardView(
        UUID productId,
        MallCategory category,
        String name,
        MallMediaView coverImage,
        MallSaleMode saleMode,
        MallExperienceBadge experienceBadge,
        MallListPriceView price,
        long sellerCount
) {
}

record MallProductPageView(
        int page,
        int size,
        long total,
        boolean hasMore,
        List<MallProductCardView> items
) {
}

record MallMerchantView(UUID organizationId, String name, MallMerchantKind kind) {
}

record MallOfferView(
        UUID offerId,
        UUID skuId,
        String skuName,
        MallMerchantView merchant,
        Long amountMinor,
        String currency,
        MallPriceUnit unit,
        String deliveryNote,
        String shippingNote,
        String fulfilledFrom,
        boolean onlinePayment,
        // 1.3.0：这家商家在这个城市这个规格的现货件数；null 表示不限。
        Integer stockQuantity,
        // 1.4.0：开票方、售后责任方（供给 → 商家资料 → 商家名称）。
        String invoiceIssuer,
        String aftersaleParty
) {
}

/** 1.3.0：商品的一个启用规格。 */
record MallSkuView(UUID skuId, String name) {
}

/** 1.3.0：图文介绍的一段；type 是 TEXT 或 IMAGE。 */
record MallIntroBlockView(String type, String text, MallMediaView image) {
}

record MallSpecView(String label, String value) {
}

record MallPackItemView(String name, int quantity) {
}

record MallProductDetailView(
        UUID productId,
        MallCategory category,
        String name,
        String summary,
        MallMediaView coverImage,
        MallSaleMode saleMode,
        MallExperienceBadge experienceBadge,
        List<MallOfferView> offers,
        List<MallSpecView> specs,
        List<MallPackItemView> packItems,
        List<String> sellingPoints,
        List<MallSkuView> skus,
        List<MallMediaView> gallery,
        List<MallIntroBlockView> introBlocks,
        // 1.4.0：是否支持七天无理由（未拆封）；商品可以标“不支持”。
        boolean noReasonReturn
) {
}
