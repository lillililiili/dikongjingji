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

    long countProducts(String cityCode, String category);

    List<ProductCardRow> findProducts(String cityCode, String category, String sort, int limit, int offset);

    Optional<ProductRow> findProduct(UUID productId);

    List<OfferRow> findOffers(UUID productId, String cityCode);

    List<SpecRow> findSpecs(UUID productId);

    List<PackItemRow> findPackItems(UUID productId);
}
