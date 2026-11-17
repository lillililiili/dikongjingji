package com.lowaltitude.inspection.mall.application;

import java.net.URI;
import java.util.List;
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
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    /** 查询里“不限类目”的占位值。 */
    private static final String ALL = "ALL";

    private final MallCatalogReadStore store;

    MallCatalogReadService(MallCatalogReadStore store) {
        this.store = store;
    }

    public MallProductPageView listProducts(
            String cityCode,
            String categoryCode,
            String sortCode,
            int page,
            int size
    ) {
        requireCityCode(cityCode);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        String category = isBlank(categoryCode) ? ALL : parse(categoryCode, MallCategory::valueOf).name();
        MallProductSort sort = isBlank(sortCode)
                ? MallProductSort.DEFAULT
                : parse(sortCode, MallProductSort::valueOf);

        long total = store.countProducts(cityCode, category);
        List<MallProductCardView> items = store
                .findProducts(cityCode, category, sort.name(), size, Math.multiplyExact(page - 1, size))
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
                                blankToNull(row.fulfilledFrom())))
                        .toList(),
                store.findSpecs(productId).stream()
                        .map(row -> new MallSpecView(row.label(), row.value()))
                        .toList(),
                store.findPackItems(productId).stream()
                        .map(row -> new MallPackItemView(row.name(), row.quantity()))
                        .toList());
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
    private static MallMediaView secureMedia(String url, String alt) {
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
        String fulfilledFrom
) {
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
        List<MallPackItemView> packItems
) {
}
