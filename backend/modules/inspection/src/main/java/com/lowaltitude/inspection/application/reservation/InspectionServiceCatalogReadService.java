package com.lowaltitude.inspection.application.reservation;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class InspectionServiceCatalogReadService {

    private static final int MAX_PAGE_SIZE = 50;
    /** 契约 InspectionServiceDetail.priceFactors 的 maxItems。 */
    private static final int MAX_PRICE_FACTORS = 10;
    /** 按项目报价且后台没有配置 price_note 时，列表卡由服务端派生的说明（契约 1.1.0）。 */
    private static final String PROJECT_QUOTE_PRICE_NOTE = "方案确认后定价";
    private static final String ALL_CATEGORIES = "ALL";
    /** 契约 keyword 的 maxLength。 */
    private static final int MAX_KEYWORD_LENGTH = 50;
    /** 契约里各筛选数组的 maxItems。 */
    private static final int MAX_FILTER_VALUES = 10;
    private static final int HOT_SERVICE_LIMIT = 4;
    /** 目录排序栏提供的排序；SALES_DESC 接口仍接受，但不在排序栏里。 */
    private static final List<InspectionCatalogSort> OFFERED_SORTS = List.of(
            InspectionCatalogSort.COMPREHENSIVE,
            InspectionCatalogSort.DISTANCE_ASC,
            InspectionCatalogSort.PRICE_ASC,
            InspectionCatalogSort.PRICE_DESC);

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
        return search(new InspectionCatalogSearch(
                cityCode, null, categoryCode, null, null, null, null,
                sortCode, null, null, page, size));
    }

    /**
     * Searches the catalog of one city. Everything the customer chose is checked here, so the
     * store only ever sees whitelisted codes and a pattern with its wildcards escaped.
     */
    public InspectionServicePageView search(InspectionCatalogSearch search) {
        String cityCode = search.cityCode();
        String category = search.categoryCode() == null || search.categoryCode().isBlank()
                ? ALL_CATEGORIES
                : search.categoryCode().trim();
        if (!isCityCode(cityCode)
                || !category.matches("^[A-Z0-9_]{2,64}$")
                || search.page() < 1
                || search.size() < 1
                || search.size() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("catalog query is invalid");
        }
        InspectionCatalogSort sort = InspectionCatalogSort.parse(search.sort());
        boolean positioned = hasValidPosition(search.latitude(), search.longitude());
        if (sort == InspectionCatalogSort.DISTANCE_ASC && !positioned) {
            throw new IllegalArgumentException("distance sort needs a position");
        }
        InspectionCatalogCriteria criteria = new InspectionCatalogCriteria(
                cityCode,
                category,
                keywordPattern(search.keyword()),
                codes(search.serviceMethods(), InspectionServiceMethod.class),
                codes(search.availability(), InspectionServiceAvailability.class),
                codes(search.priceModes(), InspectionServicePriceMode.class),
                Boolean.TRUE.equals(search.includesReport()) ? Boolean.TRUE : null,
                positioned ? search.latitude() : null,
                positioned ? search.longitude() : null);

        long total = store.countActive(criteria);
        if (total == 0 && !store.existsVisibleInCity(cityCode)) {
            throw new InspectionCatalogCityNotEnabledException();
        }
        int offset = Math.multiplyExact(search.page() - 1, search.size());
        List<InspectionServiceCardView> items =
                store.findActivePage(criteria, sort, search.size(), offset)
                        .stream()
                        .map(this::toView)
                        .toList();
        return new InspectionServicePageView(
                search.page(),
                search.size(),
                total,
                (long) search.page() * search.size() < total,
                items);
    }

    /** What the "我要巡检" entry page shows for a city: headline, categories, hot services. */
    public InspectionPortalView findPortal(String cityCode) {
        CitySummaryView city = enabledCity(cityCode);
        List<StoredPortalContent> content = store.findPortalContent(cityCode);
        StoredPortalContent hero = content.stream()
                .filter(item -> item.type() == InspectionPortalContentType.HERO)
                .findFirst()
                .orElseThrow(InspectionPortalConfigUnavailableException::new);
        OffsetDateTime updatedAt = store.findCatalogConfigUpdatedAt()
                .orElseThrow(InspectionPortalConfigUnavailableException::new);
        return new InspectionPortalView(
                city,
                configVersion(updatedAt),
                updatedAt,
                new HeroContentView(
                        hero.title(),
                        blankToNull(hero.highlightedText()),
                        hero.subtitle() == null ? "" : hero.subtitle(),
                        List.of(),
                        secureMedia(hero.imageUrl(), hero.title()),
                        null,
                        PortalActionView.openCatalog(null)),
                categories(),
                infoItems(content, InspectionPortalContentType.GUARANTEE),
                store.findMostCompleted(cityCode, HOT_SERVICE_LIMIT).stream()
                        .map(this::toView)
                        .toList(),
                infoItems(content, InspectionPortalContentType.PROCESS));
    }

    /** The categories, filters and sorts the catalog of a city offers. */
    public InspectionFilterOptionsView findFilterOptions(String cityCode) {
        CitySummaryView city = enabledCity(cityCode);
        OffsetDateTime updatedAt = store.findCatalogConfigUpdatedAt()
                .orElseThrow(InspectionPortalConfigUnavailableException::new);
        return new InspectionFilterOptionsView(
                city,
                configVersion(updatedAt),
                categories(),
                // In the order the enum declares them (无人机, then 智能设备), not by code.
                store.findServiceMethodsInCity(cityCode).stream()
                        .map(InspectionServiceMethod::valueOf)
                        .sorted()
                        .map(method -> new CodeNameView(method.name(), method.label()))
                        .toList(),
                List.of(),
                store.findPriceModesInCity(cityCode).stream()
                        .map(InspectionServicePriceMode::valueOf)
                        .map(mode -> new CodeNameView(mode.name(), mode.label()))
                        .toList(),
                OFFERED_SORTS.stream().map(Enum::name).toList(),
                store.existsReportServiceInCity(cityCode));
    }

    private CitySummaryView enabledCity(String cityCode) {
        if (!isCityCode(cityCode)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        if (!store.existsVisibleInCity(cityCode)) {
            throw new InspectionCatalogCityNotEnabledException();
        }
        return store.findCity(cityCode)
                .map(city -> new CitySummaryView(
                        city.cityCode(), city.cityName(), city.provinceCode()))
                .orElseThrow(InspectionCatalogCityNotEnabledException::new);
    }

    private List<InspectionCategoryView> categories() {
        return store.findTopLevelCategories().stream()
                .map(category -> new InspectionCategoryView(
                        category.code(),
                        category.name(),
                        blankToNull(category.subtitle()),
                        null,
                        category.sortOrder(),
                        PortalActionView.openCatalog(category.code())))
                .toList();
    }

    private static List<PortalInfoItemView> infoItems(
            List<StoredPortalContent> content,
            InspectionPortalContentType type
    ) {
        return content.stream()
                .filter(item -> item.type() == type)
                .map(item -> new PortalInfoItemView(
                        item.code(),
                        item.title(),
                        blankToNull(item.subtitle()),
                        secureMedia(item.imageUrl(), item.title()),
                        item.sortOrder()))
                .toList();
    }

    private static String configVersion(OffsetDateTime updatedAt) {
        return Long.toString(updatedAt.toEpochSecond());
    }

    private static boolean isCityCode(String cityCode) {
        return cityCode != null && cityCode.matches("^[A-Z0-9]{4,16}$");
    }

    /** Both coordinates or neither; a half position or one outside the globe is a bad request. */
    private static boolean hasValidPosition(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return false;
        }
        if (latitude == null || longitude == null
                || latitude.isNaN() || longitude.isNaN()
                || latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("position is invalid");
        }
        return true;
    }

    /** A LIKE pattern for the keyword; the customer's own percent, underscore and backslash are literal. */
    private static String keywordPattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("keyword is too long");
        }
        return "%" + trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private static <E extends Enum<E>> List<String> codes(List<String> values, Class<E> type) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_FILTER_VALUES) {
            throw new IllegalArgumentException("too many filter values");
        }
        List<String> allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
        List<String> codes = values.stream().map(String::trim).distinct().toList();
        if (!allowed.containsAll(codes)) {
            throw new IllegalArgumentException("filter value is invalid");
        }
        return codes;
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
                        row.providerQualificationReviewed(),
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
                row.completedCount(),
                row.distanceMeters() == null ? null : Math.round(row.distanceMeters()));
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
    STARTING_PRICE("起步价"),
    PER_DISTANCE("按里程计价"),
    PER_VISIT("按次计价"),
    PROJECT_QUOTE("按项目报价");

    private final String label;

    InspectionServicePriceMode(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

/** inspection_service_method.method_code 的取值（V011 CHECK 约束）。 */
enum InspectionServiceMethod {
    UAV("无人机"),
    ROBOT("智能设备");

    private final String label;

    InspectionServiceMethod(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

/** inspection_service.availability 的取值（V003 CHECK 约束，契约 availability）。 */
enum InspectionServiceAvailability {
    AVAILABLE,
    RESERVABLE,
    UNAVAILABLE
}

/** inspection_portal_content.content_type 的取值（V011 CHECK 约束）。 */
enum InspectionPortalContentType {
    HERO,
    GUARANTEE,
    PROCESS
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
    PRICE_DESC,
    DISTANCE_ASC;

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

    long countActive(InspectionCatalogCriteria criteria);

    List<StoredInspectionServiceCard> findActivePage(
            InspectionCatalogCriteria criteria,
            InspectionCatalogSort sort,
            int limit,
            int offset
    );

    /** Whether the city has any service a customer can see; false means the city is not open. */
    default boolean existsVisibleInCity(String cityCode) {
        return false;
    }

    default List<StoredInspectionServiceCard> findMostCompleted(String cityCode, int limit) {
        return List.of();
    }

    default List<StoredInspectionCategory> findTopLevelCategories() {
        return List.of();
    }

    default List<StoredPortalContent> findPortalContent(String cityCode) {
        return List.of();
    }

    default Optional<OffsetDateTime> findCatalogConfigUpdatedAt() {
        return Optional.empty();
    }

    default Optional<StoredServiceCity> findCity(String cityCode) {
        return Optional.empty();
    }

    default List<String> findServiceMethodsInCity(String cityCode) {
        return List.of();
    }

    default List<String> findPriceModesInCity(String cityCode) {
        return List.of();
    }

    default boolean existsReportServiceInCity(String cityCode) {
        return false;
    }

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
        long completedCount,
        Double distanceMeters
) {
}

/** One catalog request as the customer sent it; the read service validates every field. */
record InspectionCatalogSearch(
        String cityCode,
        String keyword,
        String categoryCode,
        List<String> serviceMethods,
        List<String> availability,
        List<String> priceModes,
        Boolean includesReport,
        String sort,
        Double latitude,
        Double longitude,
        int page,
        int size
) {
}

/** The validated conditions handed to the store: whitelisted codes and an escaped LIKE pattern. */
record InspectionCatalogCriteria(
        String cityCode,
        String categoryCode,
        String keywordPattern,
        List<String> serviceMethods,
        List<String> availability,
        List<String> priceModes,
        Boolean includesReport,
        Double latitude,
        Double longitude
) {
}

record StoredInspectionCategory(String code, String name, String subtitle, int sortOrder) {
}

record StoredPortalContent(
        InspectionPortalContentType type,
        String code,
        String title,
        String subtitle,
        String highlightedText,
        String imageUrl,
        int sortOrder
) {
}

record PortalActionView(
        String type,
        String categoryCode,
        String keyword,
        UUID serviceId,
        String hostAction
) {

    /** 契约 PortalActionType.OPEN_CATALOG：打开目录，可带一个分类。 */
    static PortalActionView openCatalog(String categoryCode) {
        return new PortalActionView("OPEN_CATALOG", categoryCode, null, null, null);
    }
}

record InspectionCategoryView(
        String code,
        String name,
        String subtitle,
        MediaAssetView icon,
        int sortOrder,
        PortalActionView action
) {
}

record HeroContentView(
        String title,
        String highlightedText,
        String subtitle,
        List<String> sellingPoints,
        MediaAssetView image,
        String actionLabel,
        PortalActionView action
) {
}

record PortalInfoItemView(
        String code,
        String title,
        String subtitle,
        MediaAssetView icon,
        int sortOrder
) {
}

record InspectionPortalView(
        CitySummaryView city,
        String configVersion,
        OffsetDateTime updatedAt,
        HeroContentView hero,
        List<InspectionCategoryView> categories,
        List<PortalInfoItemView> guarantees,
        List<InspectionServiceCardView> hotServices,
        List<PortalInfoItemView> processSteps
) {
}

record InspectionFilterOptionsView(
        CitySummaryView city,
        String configVersion,
        List<InspectionCategoryView> categories,
        List<CodeNameView> serviceMethods,
        List<CodeNameView> availability,
        List<CodeNameView> priceModes,
        List<String> sorts,
        boolean supportsReportFilter
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
        long completedCount,
        Long distanceMeters
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
        boolean providerQualificationReviewed,
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
        boolean qualificationReviewed,
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
