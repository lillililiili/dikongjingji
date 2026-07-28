package com.lowaltitude.inspection.application.reservation;

import java.net.URI;
import java.util.List;
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
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(coverUrl);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null) {
            return null;
        }
        return new MediaAssetView(uri.toString(), name);
    }
}

interface InspectionServiceCatalogReadStore {

    long countActive(String cityCode);

    List<StoredInspectionServiceCard> findActivePage(
            String cityCode,
            int limit,
            int offset
    );
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
