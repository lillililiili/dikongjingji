package com.lowaltitude.inspection.application.reservation;

import java.util.List;

import com.lowaltitude.inspection.infrastructure.persistence.InspectionServiceCatalogMapper;

final class PostgresInspectionServiceCatalogReadStore
        implements InspectionServiceCatalogReadStore {

    private final InspectionServiceCatalogMapper mapper;

    PostgresInspectionServiceCatalogReadStore(
            InspectionServiceCatalogMapper mapper
    ) {
        this.mapper = mapper;
    }

    @Override
    public long countActive(String cityCode) {
        return mapper.countActive(cityCode);
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            String cityCode,
            int limit,
            int offset
    ) {
        return mapper.findActivePage(cityCode, limit, offset).stream()
                .map(row -> new StoredInspectionServiceCard(
                        row.serviceId(),
                        row.serviceNo(),
                        row.categoryCode(),
                        row.categoryName(),
                        row.name(),
                        row.summary(),
                        row.coverUrl(),
                        mapper.findFeatures(row.serviceId()).stream()
                                .map(feature -> new CodeNameView(
                                        feature.code(),
                                        feature.name()))
                                .toList(),
                        row.priceMode(),
                        row.startingAmountMinor(),
                        row.currency(),
                        row.unitCode(),
                        row.unitLabel(),
                        row.availability(),
                        row.primaryAction()))
                .toList();
    }
}
