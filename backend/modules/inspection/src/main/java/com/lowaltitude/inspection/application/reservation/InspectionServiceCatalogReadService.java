package com.lowaltitude.inspection.application.reservation;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class InspectionServiceCatalogReadService {

    private static final int MAX_PAGE_SIZE = 50;

    private final InspectionServiceCatalogReadStore store;

    public InspectionServiceCatalogReadService(
            InspectionServiceCatalogReadStore store
    ) {
        this.store = store;
    }

    public InspectionServicePageView search(
            String cityCode,
            int page,
            int size
    ) {
        if (cityCode == null
                || !cityCode.matches("^[A-Z0-9]{4,16}$")
                || page < 1
                || size < 1
                || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("catalog query is invalid");
        }
        long total = store.countActive(cityCode);
        int offset = Math.multiplyExact(page - 1, size);
        List<InspectionServiceCardView> items =
                store.findActivePage(cityCode, size, offset).stream()
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
                row.priceNote(),
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
                mapItems(store.findDetailItems(serviceId, "CAPABILITY")),
                mapItems(store.findBookingItems(serviceId)),
                mapItems(store.findDetailItems(serviceId, "PROCESS")),
                mapItems(store.findDetailItems(serviceId, "DELIVERABLE")),
                mapItems(store.findDetailItems(serviceId, "GUARANTEE")),
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
                row.availability(),
                row.primaryAction());
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

interface InspectionServiceCatalogReadStore {

    long countActive(String cityCode);

    List<StoredInspectionServiceCard> findActivePage(
            String cityCode,
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
            String sectionType
    ) {
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
        String availability,
        String primaryAction
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
        String availability,
        String primaryAction
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
