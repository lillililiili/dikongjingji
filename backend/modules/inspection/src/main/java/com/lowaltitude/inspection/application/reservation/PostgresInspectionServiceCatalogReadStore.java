package com.lowaltitude.inspection.application.reservation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    public long countActive(String cityCode, String categoryCode) {
        return mapper.countActive(cityCode, categoryCode);
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            String cityCode,
            String categoryCode,
            InspectionCatalogSort sort,
            int limit,
            int offset
    ) {
        return mapper.findActivePage(
                        cityCode,
                        categoryCode,
                        sort.name(),
                        limit,
                        offset)
                .stream()
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
                        row.priceNote(),
                        row.availability(),
                        row.primaryAction(),
                        row.completedCount()))
                .toList();
    }

    @Override
    public Optional<StoredInspectionServiceDetail> findActiveDetail(
            UUID serviceId,
            String cityCode
    ) {
        return mapper.findActiveDetail(serviceId, cityCode)
                .map(row -> new StoredInspectionServiceDetail(
                        row.serviceId(),
                        row.serviceNo(),
                        row.categoryCode(),
                        row.categoryName(),
                        row.name(),
                        row.summary(),
                        row.coverUrl(),
                        row.priceMode(),
                        row.startingAmountMinor(),
                        row.currency(),
                        row.unitCode(),
                        row.unitLabel(),
                        row.priceNote(),
                        row.cancellationNote(),
                        row.availability(),
                        row.primaryAction(),
                        row.completedCount(),
                        row.providerId(),
                        row.providerName(),
                        row.providerLogoUrl(),
                        row.providerLogoAssetCode(),
                        row.certificationLabel(),
                        row.teamDescription(),
                        row.ratingAverage(),
                        row.reviewCount(),
                        row.positiveRate()));
    }

    @Override
    public List<StoredDetailItem> findBookingItems(UUID serviceId) {
        return mapper.findBookingDetailItems(serviceId).stream()
                .map(PostgresInspectionServiceCatalogReadStore::toDetailItem)
                .toList();
    }

    @Override
    public List<StoredDetailItem> findDetailItems(
            UUID serviceId,
            InspectionDetailSectionType sectionType
    ) {
        return mapper.findDetailItems(serviceId, sectionType.name()).stream()
                .map(PostgresInspectionServiceCatalogReadStore::toDetailItem)
                .toList();
    }

    @Override
    public List<StoredServiceCity> findActiveCities(UUID serviceId) {
        return mapper.findActiveCities(serviceId).stream()
                .map(row -> new StoredServiceCity(
                        row.cityCode(),
                        row.cityName(),
                        row.provinceCode()))
                .toList();
    }

    @Override
    public List<StoredReviewTag> findReviewTags(UUID serviceId) {
        return mapper.findReviewTags(serviceId).stream()
                .map(row -> new StoredReviewTag(
                        row.code(),
                        row.label(),
                        row.tagCount()))
                .toList();
    }

    @Override
    public List<StoredReview> findReviewPreview(
            UUID serviceId,
            int limit
    ) {
        return mapper.findReviewPreview(serviceId, limit).stream()
                .map(row -> new StoredReview(
                        row.id(),
                        row.maskedUserName(),
                        row.rating(),
                        row.reviewDate(),
                        row.content()))
                .toList();
    }

    @Override
    public List<StoredReviewImage> findReviewImages(UUID reviewId) {
        return mapper.findReviewImages(reviewId).stream()
                .map(row -> new StoredReviewImage(
                        row.reviewId(),
                        row.imageUrl(),
                        row.imageAssetCode()))
                .toList();
    }

    private static StoredDetailItem toDetailItem(
            InspectionServiceCatalogMapper.DetailItemRow row
    ) {
        return new StoredDetailItem(
                row.code(),
                row.title(),
                row.description(),
                row.iconKey(),
                row.imageUrl(),
                row.imageAssetCode());
    }
}
