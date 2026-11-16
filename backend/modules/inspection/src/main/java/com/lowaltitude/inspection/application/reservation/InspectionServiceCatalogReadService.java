package com.lowaltitude.inspection.application.reservation;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class InspectionServiceCatalogReadService {

    private static final int MAX_PAGE_SIZE = 50;
    /** 契约 InspectionServiceDetail.priceFactors 的 maxItems。 */
    private static final int MAX_PRICE_FACTORS = 10;
    /** 按项目报价且后台没有配置 price_note 时，列表卡由服务端派生的说明（契约 1.1.0）。 */
    private static final String PROJECT_QUOTE_PRICE_NOTE = "方案确认后定价";

    private final InspectionServiceCatalogReadStore store;

    public InspectionServiceCatalogReadService(
            InspectionServiceCatalogReadStore store
    ) {
        this.store = store;
    }

    public InspectionServicePageView search(
            String cityCode,
            String categoryCode,
            String sortCode,
            int page,
            int size
    ) {
        String normalizedCategory = categoryCode == null || categoryCode.isBlank()
                ? "ALL"
                : categoryCode.trim();
        if (cityCode == null
                || !cityCode.matches("^[A-Z0-9]{4,16}$")
                || !normalizedCategory.matches("^[A-Z0-9_]{2,64}$")
                || page < 1
                || size < 1
                || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("catalog query is invalid");
        }
        InspectionCatalogSort sort = InspectionCatalogSort.parse(sortCode);
        long total = store.countActive(cityCode, normalizedCategory);
        int offset = Math.multiplyExact(page - 1, size);
        List<InspectionServiceCardView> items =
                store.findActivePage(
                                cityCode,
                                normalizedCategory,
                                sort,
                                size,
                                offset)
                        .stream()
                        .map(this::toView)
                        .toList();
        return new InspectionServicePageView(
                page,
                size,
                total,
                (long) page * size < total,
                items);
    }

    public InspectionServiceDetailView findDetail(
            UUID serviceId,
            String cityCode
    ) {
        if (serviceId == null
                || cityCode == null
                || !cityCode.matches("^[A-Z0-9]{4,16}$")) {
            throw new IllegalArgumentException("service detail query is invalid");
        }
        StoredInspectionServiceDetail row =
                store.findActiveDetail(serviceId, cityCode)
                        .orElseThrow(InspectionServiceNotFoundException::new);
        List<String> priceFactors = store
                .findDetailItems(serviceId, InspectionDetailSectionType.PRICE_FACTOR)
                .stream()
                .map(StoredDetailItem::title)
                .limit(MAX_PRICE_FACTORS)
                .toList();
        List<CitySummaryView> serviceCities = store.findActiveCities(serviceId).stream()
                .map(city -> new CitySummaryView(
                        city.cityCode(),
                        city.cityName(),
                        city.provinceCode()))
                .toList();
        List<ReviewView> reviews = store.findReviewPreview(serviceId, 2).stream()
                .map(review -> new ReviewView(
                        review.id(),
                        review.maskedUserName(),
                        review.rating(),
                        review.date(),
                        review.content(),
                        store.findReviewImages(review.id()).stream()
                                .map(image -> new ReviewImageView(
                                        secureMedia(
                                                image.imageUrl(),
                                                row.name()),
                                        image.imageAssetCode()))
                                .toList()))
                .toList();
        ReviewSummaryView reviewSummary = new ReviewSummaryView(
                row.ratingAverage(),
                row.reviewCount(),
                row.positiveRate(),
                store.findReviewTags(serviceId).stream()
                        .map(tag -> new ReviewTagView(
                                tag.code(),
                                tag.label(),
                                tag.count()))
                        .toList(),
                reviews);
        return new InspectionServiceDetailView(
                row.serviceId(),
                row.serviceNo(),
                new CodeNameView(row.categoryCode(), row.categoryName()),
                row.name(),
                row.summary(),
                secureMedia(row.coverUrl(), row.name()),
                new ServicePriceView(
                        row.priceMode(),
                        row.startingAmountMinor(),
                        row.currency(),
                        row.unitCode(),
                        row.unitLabel()),
                blankToNull(row.priceNote()),
                priceFactors.isEmpty() ? null : priceFactors,
                blankToNull(row.cancellationNote()),
                serviceCities.isEmpty() ? null : serviceCities,
                row.availability(),
                row.primaryAction(),
                row.completedCount(),
                new ServiceProviderView(
                        row.providerId(),
                        row.providerName(),
                        secureMedia(row.providerLogoUrl(), row.providerName()),
                        row.providerLogoAssetCode(),
                        row.certificationLabel(),
                        row.teamDescription()),
                mapItems(store.findDetailItems(
                        serviceId, InspectionDetailSectionType.CAPABILITY)),
                mapItems(store.findBookingItems(serviceId)),
                mapItems(store.findDetailItems(
                        serviceId, InspectionDetailSectionType.PROCESS)),
                mapItems(store.findDetailItems(
                        serviceId, InspectionDetailSectionType.DELIVERABLE)),
                mapItems(store.findDetailItems(
                        serviceId, InspectionDetailSectionType.GUARANTEE)),
                reviewSummary);
    }

    private InspectionServiceCardView toView(StoredInspectionServiceCard row) {
        MediaAssetView cover = secureCover(row.coverUrl(), row.name());
        return new InspectionServiceCardView(
                row.serviceId(),
                row.serviceNo(),
                new CodeNameView(row.categoryCode(), row.categoryName()),
                row.name(),
                row.summary(),
                cover,
                row.features(),
                new ServicePriceView(
                        row.priceMode(),
                        row.startingAmountMinor(),
                        row.currency(),
                        row.unitCode(),
                        row.unitLabel()),
                cardPriceNote(row.priceMode(), row.priceNote()),
                row.availability(),
                row.primaryAction(),
                row.completedCount());
    }

    /**
     * 列表卡价格说明：后台配置优先；按项目报价且没有配置时派生固定文案，
     * 其他计价模式没有配置就返回 null，不编造说明。
     */
    private static String cardPriceNote(String priceMode, String configuredNote) {
        String configured = blankToNull(configuredNote);
        if (configured != null) {
            return configured;
        }
        return InspectionServicePriceMode.PROJECT_QUOTE.name().equals(priceMode)
                ? PROJECT_QUOTE_PRICE_NOTE
                : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static MediaAssetView secureCover(String coverUrl, String name) {
        return secureMedia(coverUrl, name);
    }

    private static List<DetailItemView> mapItems(
            List<StoredDetailItem> items
    ) {
        return items.stream()
                .map(item -> new DetailItemView(
                        item.code(),
                        item.title(),
                        item.description(),
                        item.iconKey(),
                        secureMedia(item.imageUrl(), item.title()),
                        item.imageAssetCode()))
                .toList();
    }

    private static MediaAssetView secureMedia(String url, String alt) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null) {
            return null;
        }
        return new MediaAssetView(uri.toString(), alt);
    }
}

/** inspection_service.price_mode 的取值（V003 CHECK 约束，契约 PriceMode）。 */
enum InspectionServicePriceMode {
    STARTING_PRICE,
    PER_DISTANCE,
    PER_VISIT,
    PROJECT_QUOTE
}

/** inspection_service_detail_item.section_type 的取值（V004 CHECK 约束，V010 增加 PRICE_FACTOR）。 */
enum InspectionDetailSectionType {
    CAPABILITY,
    PROCESS,
    DELIVERABLE,
    GUARANTEE,
    PRICE_FACTOR
}

enum InspectionCatalogSort {
    COMPREHENSIVE,
    SALES_DESC,
    PRICE_ASC,
    PRICE_DESC;

    static InspectionCatalogSort parse(String value) {
        try {
            return value == null || value.isBlank()
                    ? COMPREHENSIVE
                    : valueOf(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("catalog sort is invalid");
        }
    }
}

interface InspectionServiceCatalogReadStore {

    long countActive(String cityCode, String categoryCode);

    List<StoredInspectionServiceCard> findActivePage(
            String cityCode,
            String categoryCode,
            InspectionCatalogSort sort,
            int limit,
            int offset
    );

    default Optional<StoredInspectionServiceDetail> findActiveDetail(
            UUID serviceId,
            String cityCode
    ) {
        return Optional.empty();
    }

    default List<StoredDetailItem> findBookingItems(UUID serviceId) {
        return List.of();
    }

    default List<StoredDetailItem> findDetailItems(
            UUID serviceId,
            InspectionDetailSectionType sectionType
    ) {
        return List.of();
    }

    default List<StoredServiceCity> findActiveCities(UUID serviceId) {
        return List.of();
    }

    default List<StoredReviewTag> findReviewTags(UUID serviceId) {
        return List.of();
    }

    default List<StoredReview> findReviewPreview(
            UUID serviceId,
            int limit
    ) {
        return List.of();
    }

    default List<StoredReviewImage> findReviewImages(UUID reviewId) {
        return List.of();
    }
}

record StoredInspectionServiceCard(
        UUID serviceId,
        String serviceNo,
        String categoryCode,
        String categoryName,
        String name,
        String summary,
        String coverUrl,
        List<CodeNameView> features,
        String priceMode,
        Long startingAmountMinor,
        String currency,
        String unitCode,
        String unitLabel,
        String priceNote,
        String availability,
        String primaryAction,
        long completedCount
) {
}

record InspectionServicePageView(
        int page,
        int size,
        long total,
        boolean hasMore,
        List<InspectionServiceCardView> items
) {
}

record InspectionServiceCardView(
        UUID serviceId,
        String serviceNo,
        CodeNameView category,
        String name,
        String summary,
        MediaAssetView coverImage,
        List<CodeNameView> features,
        ServicePriceView price,
        String priceNote,
        String availability,
        String primaryAction,
        long completedCount
) {
}

record StoredInspectionServiceDetail(
        UUID serviceId,
        String serviceNo,
        String categoryCode,
        String categoryName,
        String name,
        String summary,
        String coverUrl,
        String priceMode,
        Long startingAmountMinor,
        String currency,
        String unitCode,
        String unitLabel,
        String priceNote,
        String cancellationNote,
        String availability,
        String primaryAction,
        long completedCount,
        UUID providerId,
        String providerName,
        String providerLogoUrl,
        String providerLogoAssetCode,
        String certificationLabel,
        String teamDescription,
        Double ratingAverage,
        long reviewCount,
        Integer positiveRate
) {
}

record StoredDetailItem(
        String code,
        String title,
        String description,
        String iconKey,
        String imageUrl,
        String imageAssetCode
) {
}

record StoredServiceCity(
        String cityCode,
        String cityName,
        String provinceCode
) {
}

record StoredReviewTag(String code, String label, long count) {
}

record StoredReview(
        UUID id,
        String maskedUserName,
        int rating,
        LocalDate date,
        String content
) {
}

record StoredReviewImage(
        UUID reviewId,
        String imageUrl,
        String imageAssetCode
) {
}

record InspectionServiceDetailView(
        UUID serviceId,
        String serviceNo,
        CodeNameView category,
        String name,
        String summary,
        MediaAssetView coverImage,
        ServicePriceView price,
        String priceNote,
        List<String> priceFactors,
        String cancellationNote,
        List<CitySummaryView> serviceCities,
        String availability,
        String primaryAction,
        long completedCount,
        ServiceProviderView provider,
        List<DetailItemView> capabilities,
        List<DetailItemView> inspectionItems,
        List<DetailItemView> processSteps,
        List<DetailItemView> deliverables,
        List<DetailItemView> guarantees,
        ReviewSummaryView reviews
) {
}

record ServiceProviderView(
        UUID providerId,
        String name,
        MediaAssetView logo,
        String logoAssetCode,
        String certificationLabel,
        String teamDescription
) {
}

record DetailItemView(
        String code,
        String name,
        String description,
        String iconKey,
        MediaAssetView image,
        String imageAssetCode
) {
}

record ReviewSummaryView(
        Double average,
        long total,
        Integer positiveRate,
        List<ReviewTagView> tags,
        List<ReviewView> items
) {
}

record ReviewTagView(String code, String name, long count) {
}

record ReviewView(
        UUID id,
        String maskedUserName,
        int rating,
        LocalDate date,
        String content,
        List<ReviewImageView> imageAssets
) {
}

record ReviewImageView(MediaAssetView image, String assetCode) {
}

record CodeNameView(String code, String name) {
}

record CitySummaryView(String code, String name, String provinceCode) {
}

record MediaAssetView(String url, String alt) {
}

record ServicePriceView(
        String mode,
        Long startingAmountMinor,
        String currency,
        String unitCode,
        String unitLabel
) {
}
