package com.lowaltitude.inspection.mall.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.mall.application.MallRows.OfferRow;
import com.lowaltitude.inspection.mall.application.MallRows.PackItemRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductCardRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductRow;
import com.lowaltitude.inspection.mall.application.MallRows.SpecRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogMapper;

final class PostgresMallCatalogReadStore implements MallCatalogReadStore {

    private final MallCatalogMapper mapper;

    PostgresMallCatalogReadStore(MallCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long countProducts(String cityCode, String category) {
        return mapper.countProducts(cityCode, category);
    }

    @Override
    public List<ProductCardRow> findProducts(
            String cityCode, String category, String sort, int limit, int offset
    ) {
        return mapper.findProducts(cityCode, category, sort, limit, offset);
    }

    @Override
    public Optional<ProductRow> findProduct(UUID productId) {
        return mapper.findProduct(productId);
    }

    @Override
    public List<OfferRow> findOffers(UUID productId, String cityCode) {
        return mapper.findOffers(productId, cityCode);
    }

    @Override
    public List<SpecRow> findSpecs(UUID productId) {
        return mapper.findSpecs(productId);
    }

    @Override
    public List<PackItemRow> findPackItems(UUID productId) {
        return mapper.findPackItems(productId);
    }
}
