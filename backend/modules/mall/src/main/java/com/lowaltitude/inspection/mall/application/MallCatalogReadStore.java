package com.lowaltitude.inspection.mall.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.mall.application.MallRows.OfferRow;
import com.lowaltitude.inspection.mall.application.MallRows.PackItemRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductCardRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductRow;
import com.lowaltitude.inspection.mall.application.MallRows.SpecRow;

interface MallCatalogReadStore {

    long countProducts(MallProductCriteria criteria);

    List<ProductCardRow> findProducts(MallProductCriteria criteria, String sort, int limit, int offset);

    Optional<ProductRow> findProduct(UUID productId);

    List<OfferRow> findOffers(UUID productId, String cityCode);

    List<SpecRow> findSpecs(UUID productId);

    List<PackItemRow> findPackItems(UUID productId);

    // 1.3.0：运营端录入的卖点、全部启用规格、图集和图文介绍（S3-1）。

    default List<String> findSellingPoints(UUID productId) {
        return List.of();
    }

    default List<MallRows.SkuRow> findSkus(UUID productId) {
        return List.of();
    }

    default List<String> findGallery(UUID productId) {
        return List.of();
    }

    default List<MallRows.IntroBlockRow> findIntroBlocks(UUID productId) {
        return List.of();
    }
}
