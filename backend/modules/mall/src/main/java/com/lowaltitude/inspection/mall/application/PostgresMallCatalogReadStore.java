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
    public long countProducts(MallProductCriteria criteria) {
        return mapper.countProducts(
                criteria.cityCode(),
                criteria.category(),
                criteria.keyword(),
                criteria.saleModes(),
                criteria.experienceBadges(),
                criteria.merchantKinds(),
                criteria.minPriceMinor(),
                criteria.maxPriceMinor());
    }

    @Override
    public List<ProductCardRow> findProducts(
            MallProductCriteria criteria, String sort, int limit, int offset
    ) {
        return mapper.findProducts(
                criteria.cityCode(),
                criteria.category(),
                criteria.keyword(),
                criteria.saleModes(),
                criteria.experienceBadges(),
                criteria.merchantKinds(),
                criteria.minPriceMinor(),
                criteria.maxPriceMinor(),
                sort,
                limit,
                offset);
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

    @Override
    public List<String> findSellingPoints(UUID productId) {
        return mapper.findSellingPoints(productId);
    }

    @Override
    public List<MallRows.SkuRow> findSkus(UUID productId) {
        return mapper.findSkus(productId);
    }

    @Override
    public List<String> findGallery(UUID productId) {
        return mapper.findGallery(productId);
    }

    @Override
    public List<MallRows.IntroBlockRow> findIntroBlocks(UUID productId) {
        return mapper.findIntroBlocks(productId);
    }
}
