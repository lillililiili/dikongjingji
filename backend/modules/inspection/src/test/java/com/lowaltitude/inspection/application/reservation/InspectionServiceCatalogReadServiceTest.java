package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InspectionServiceCatalogReadServiceTest {

    @Test
    void returnsFrozenCatalogShapeAndHidesNonHttpsCover() {
        UUID serviceId = UUID.randomUUID();
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(new InspectionServiceCatalogReadStore() {
                    @Override
                    public long countActive(InspectionCatalogCriteria criteria) {
                        assertThat(criteria.categoryCode()).isEqualTo("POWER");
                        return 1;
                    }

                    @Override
                    public List<StoredInspectionServiceCard> findActivePage(
                            InspectionCatalogCriteria criteria,
                            InspectionCatalogSort sort,
                            int limit,
                            int offset
                    ) {
                        assertThat(criteria.categoryCode()).isEqualTo("POWER");
                        assertThat(sort).isEqualTo(InspectionCatalogSort.SALES_DESC);
                        return List.of(new StoredInspectionServiceCard(
                                serviceId,
                                "INS-SVC-001",
                                "POWER",
                                "电力巡检",
                                "输电线路巡检",
                                "无人机巡检线路与杆塔",
                                "http://unsafe.example/cover.jpg",
                                List.of(new CodeNameView(
                                        "POWER_LINE",
                                        "输电线路")),
                                "STARTING_PRICE",
                                120_000L,
                                "CNY",
                                "VISIT",
                                "次",
                                "含现场巡检和报告",
                                "RESERVABLE",
                                "RESERVE",
                                8_560L,
                                null));
                    }
                });

        InspectionServicePageView page = service.search(
                "370500", "POWER", "SALES_DESC", 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).singleElement().satisfies(card -> {
            assertThat(card.serviceId()).isEqualTo(serviceId);
            assertThat(card.coverImage()).isNull();
            assertThat(card.features()).containsExactly(
                    new CodeNameView("POWER_LINE", "输电线路"));
            assertThat(card.price().startingAmountMinor()).isEqualTo(120_000L);
            assertThat(card.priceNote()).isEqualTo("含现场巡检和报告");
            assertThat(card.completedCount()).isEqualTo(8_560L);
        });
    }

    @Test
    void derivesProjectQuotePriceNoteOnlyWhenNothingIsConfigured() {
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(new CardCatalogStore(List.of(
                        card("PROJECT_QUOTE", null, null),
                        card("PROJECT_QUOTE", null, "报价以现场评估为准"),
                        card("STARTING_PRICE", 69_900L, null),
                        card("STARTING_PRICE", 69_900L, "   "))));

        InspectionServicePageView page = service.search(
                "370500", "ALL", "COMPREHENSIVE", 1, 20);

        assertThat(page.items()).extracting(InspectionServiceCardView::priceNote)
                .containsExactly(
                        "方案确认后定价",
                        "报价以现场评估为准",
                        null,
                        null);
    }

    private static StoredInspectionServiceCard card(
            String priceMode,
            Long startingAmountMinor,
            String priceNote
    ) {
        return new StoredInspectionServiceCard(
                UUID.randomUUID(),
                "INS-SVC-001",
                "PARK",
                "园区",
                "园区综合巡检服务",
                "安防巡查",
                null,
                List.of(),
                priceMode,
                startingAmountMinor,
                "CNY",
                null,
                null,
                priceNote,
                "AVAILABLE",
                "REQUEST_QUOTE",
                0L,
                null);
    }

    @Test
    void validatesCityAndPaginationBeforeQueryingStore() {
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(new EmptyCatalogStore());

        assertThatThrownBy(() -> service.search(
                "../bad", "POWER", "COMPREHENSIVE", 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(
                "370500", "POWER", "COMPREHENSIVE", 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(
                "370500", "POWER", "COMPREHENSIVE", 1, 51))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(
                "370500", "POWER", "DROP TABLE", 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passesWhitelistedFiltersAndAnEscapedKeywordToTheStore() {
        RecordingCatalogStore store = new RecordingCatalogStore();
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(store);

        InspectionServicePageView page = service.search(new InspectionCatalogSearch(
                "370500",
                "  50%_线路\\ ",
                "POWER",
                List.of("UAV", "UAV", " ROBOT "),
                List.of("RESERVABLE"),
                List.of("PROJECT_QUOTE"),
                true,
                "DISTANCE_ASC",
                37.43,
                118.67,
                2,
                5));

        assertThat(store.criteria).isEqualTo(new InspectionCatalogCriteria(
                "370500",
                "POWER",
                "%50\\%\\_线路\\\\%",
                List.of("UAV", "ROBOT"),
                List.of("RESERVABLE"),
                List.of("PROJECT_QUOTE"),
                Boolean.TRUE,
                37.43,
                118.67));
        assertThat(store.sort).isEqualTo(InspectionCatalogSort.DISTANCE_ASC);
        assertThat(store.limit).isEqualTo(5);
        assertThat(store.offset).isEqualTo(5);
        assertThat(page.total()).isEqualTo(7);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).singleElement()
                .extracting(InspectionServiceCardView::distanceMeters)
                .isEqualTo(3_812L);
    }

    @Test
    void leavesOutEmptyConditions() {
        RecordingCatalogStore store = new RecordingCatalogStore();

        new InspectionServiceCatalogReadService(store).search(new InspectionCatalogSearch(
                "370500", "   ", null, null, List.of(), null, false,
                null, null, null, 1, 5));

        assertThat(store.criteria).isEqualTo(new InspectionCatalogCriteria(
                "370500", "ALL", null, List.of(), List.of(), List.of(), null, null, null));
        assertThat(store.sort).isEqualTo(InspectionCatalogSort.COMPREHENSIVE);
    }

    @Test
    void rejectsInvalidConditionsBeforeQueryingStore() {
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(new EmptyCatalogStore());

        List<InspectionCatalogSearch> invalid = List.of(
                search("x".repeat(51), null, null, null, "COMPREHENSIVE", null, null),
                search(null, List.of("PLANE"), null, null, "COMPREHENSIVE", null, null),
                search(null, null, List.of("SOON"), null, "COMPREHENSIVE", null, null),
                search(null, null, null, List.of("FREE"), "COMPREHENSIVE", null, null),
                search(null, null, null, null, "DISTANCE_ASC", null, null),
                search(null, null, null, null, "COMPREHENSIVE", 37.43, null),
                search(null, null, null, null, "COMPREHENSIVE", 91.0, 118.67),
                search(null, null, null, null, "COMPREHENSIVE", 37.43, 181.0),
                search(null, List.of("UAV", "UAV", "UAV", "UAV", "UAV", "UAV", "UAV", "UAV",
                        "UAV", "UAV", "UAV"), null, null, "COMPREHENSIVE", null, null));

        for (InspectionCatalogSearch one : invalid) {
            assertThatThrownBy(() -> service.search(one))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void tellsACityWithoutServicesFromASearchWithoutResults() {
        assertThatThrownBy(() -> new InspectionServiceCatalogReadService(
                new EmptyCatalogStore()).search("370700", "ALL", "COMPREHENSIVE", 1, 5))
                .isInstanceOf(InspectionCatalogCityNotEnabledException.class);

        InspectionServicePageView page = new InspectionServiceCatalogReadService(
                new PortalCatalogStore(true, true))
                .search("370500", "EQUIPMENT", "COMPREHENSIVE", 1, 5);
        assertThat(page.total()).isZero();
        assertThat(page.items()).isEmpty();
    }

    @Test
    void buildsThePortalFromConfiguredContent() {
        InspectionPortalView portal = new InspectionServiceCatalogReadService(
                new PortalCatalogStore(true, true)).findPortal("370500");

        assertThat(portal.city()).isEqualTo(new CitySummaryView("370500", "东营市", "370000"));
        assertThat(portal.configVersion()).isEqualTo("1794700800");
        assertThat(portal.updatedAt()).isEqualTo(PortalCatalogStore.UPDATED_AT);
        assertThat(portal.hero().title()).isEqualTo("专业巡检");
        assertThat(portal.hero().highlightedText()).isEqualTo("隐患早发现");
        assertThat(portal.hero().subtitle()).isEmpty();
        assertThat(portal.hero().image()).isNull();
        assertThat(portal.hero().action())
                .isEqualTo(new PortalActionView("OPEN_CATALOG", null, null, null, null));
        assertThat(portal.categories()).containsExactly(
                new InspectionCategoryView("POWER", "电力", null, null, 10,
                        new PortalActionView("OPEN_CATALOG", "POWER", null, null, null)),
                new InspectionCategoryView("PARK", "园区", "安防与设备", null, 50,
                        new PortalActionView("OPEN_CATALOG", "PARK", null, null, null)));
        assertThat(portal.guarantees()).isEmpty();
        assertThat(portal.processSteps()).containsExactly(
                new PortalInfoItemView("BOOK", "提交预约", "选服务和时间", null, 10),
                new PortalInfoItemView("PLAN", "确认方案", null, null, 20));
        assertThat(portal.hotServices()).extracting(InspectionServiceCardView::name)
                .containsExactly("园区综合巡检服务");
    }

    @Test
    void portalAndFiltersRefuseCitiesThatAreNotOpenAndMissingHeadlines() {
        InspectionServiceCatalogReadService closed =
                new InspectionServiceCatalogReadService(new PortalCatalogStore(false, true));
        assertThatThrownBy(() -> closed.findPortal("370700"))
                .isInstanceOf(InspectionCatalogCityNotEnabledException.class);
        assertThatThrownBy(() -> closed.findFilterOptions("370700"))
                .isInstanceOf(InspectionCatalogCityNotEnabledException.class);
        assertThatThrownBy(() -> closed.findPortal(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> closed.findFilterOptions("bad code"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new InspectionServiceCatalogReadService(
                new PortalCatalogStore(true, false)).findPortal("370500"))
                .isInstanceOf(InspectionPortalConfigUnavailableException.class);
    }

    @Test
    void listsTheFiltersTheCityOffers() {
        InspectionFilterOptionsView options = new InspectionServiceCatalogReadService(
                new PortalCatalogStore(true, true)).findFilterOptions("370500");

        assertThat(options.city().name()).isEqualTo("东营市");
        assertThat(options.categories()).extracting(InspectionCategoryView::code)
                .containsExactly("POWER", "PARK");
        assertThat(options.serviceMethods()).containsExactly(
                new CodeNameView("UAV", "无人机"),
                new CodeNameView("ROBOT", "智能设备"));
        assertThat(options.availability()).isEmpty();
        assertThat(options.priceModes()).containsExactly(
                new CodeNameView("PER_VISIT", "按次计价"),
                new CodeNameView("PROJECT_QUOTE", "按项目报价"));
        assertThat(options.sorts()).containsExactly(
                "COMPREHENSIVE", "DISTANCE_ASC", "PRICE_ASC", "PRICE_DESC");
        assertThat(options.supportsReportFilter()).isTrue();
    }

    private static InspectionCatalogSearch search(
            String keyword,
            List<String> serviceMethods,
            List<String> availability,
            List<String> priceModes,
            String sort,
            Double latitude,
            Double longitude
    ) {
        return new InspectionCatalogSearch(
                "370500", keyword, "ALL", serviceMethods, availability, priceModes, null,
                sort, latitude, longitude, 1, 5);
    }

    @Test
    void returnsCompleteServiceDetailWithAggregatedReviews() {
        UUID serviceId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(new DetailCatalogStore(
                        new StoredInspectionServiceDetail(
                                serviceId,
                                "INS-SVC-001",
                                "POWER",
                                "输电巡检",
                                "输电线路无人机巡检",
                                "杆塔精细巡检 · 导线通道检测 · 缺陷报告",
                                "https://cdn.example.com/power-cover.jpg",
                                "STARTING_PRICE",
                                69_900L,
                                "CNY",
                                "KM",
                                "公里",
                                "按线路公里计价，起步价含现场巡检和报告",
                                "方案确认前可取消，不收费；确认后按方案约定",
                                "RESERVABLE",
                                "RESERVE",
                                8_560L,
                                providerId,
                                "云翼科技",
                                null,
                                "FEIXIANG_APP_ICON",
                                "企业认证",
                                true,
                                "专业飞手团队",
                                4.9D,
                                328L,
                                98),
                        reviewId,
                        List.of(
                                new StoredDetailItem("LINE_LENGTH", "线路长度", null, null, null, null),
                                new StoredDetailItem("TOWER_COUNT", "杆塔数量", null, null, null, null),
                                new StoredDetailItem("SITE_CONDITION", "现场条件", null, null, null, null)),
                        List.of(
                                new StoredServiceCity("370500", "东营", "370000"),
                                new StoredServiceCity("370100", "济南", "370000"))));

        InspectionServiceDetailView detail =
                service.findDetail(serviceId, "370500");

        assertThat(detail.serviceId()).isEqualTo(serviceId);
        assertThat(detail.completedCount()).isEqualTo(8_560L);
        assertThat(detail.provider()).satisfies(provider -> {
            assertThat(provider.providerId()).isEqualTo(providerId);
            assertThat(provider.name()).isEqualTo("云翼科技");
            assertThat(provider.logoAssetCode()).isEqualTo("FEIXIANG_APP_ICON");
            assertThat(provider.certificationLabel()).isEqualTo("企业认证");
            assertThat(provider.qualificationReviewed()).isTrue();
        });
        assertThat(detail.inspectionItems()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("TOWER");
            assertThat(item.description()).isEqualTo("精细化巡检杆塔本体");
        });
        assertThat(detail.capabilities()).extracting(DetailItemView::code)
                .containsExactly("MODELING");
        assertThat(detail.processSteps()).extracting(DetailItemView::code)
                .containsExactly("REQUEST");
        assertThat(detail.deliverables()).extracting(DetailItemView::code)
                .containsExactly("IMAGES");
        assertThat(detail.guarantees()).extracting(DetailItemView::code)
                .containsExactly("PILOT");
        assertThat(detail.priceNote()).isEqualTo("按线路公里计价，起步价含现场巡检和报告");
        assertThat(detail.priceFactors()).containsExactly("线路长度", "杆塔数量", "现场条件");
        assertThat(detail.cancellationNote())
                .isEqualTo("方案确认前可取消，不收费；确认后按方案约定");
        assertThat(detail.serviceCities()).containsExactly(
                new CitySummaryView("370500", "东营", "370000"),
                new CitySummaryView("370100", "济南", "370000"));
        assertThat(detail.reviews()).satisfies(reviews -> {
            assertThat(reviews.average()).isEqualTo(4.9D);
            assertThat(reviews.total()).isEqualTo(328L);
            assertThat(reviews.positiveRate()).isEqualTo(98);
            assertThat(reviews.tags()).containsExactly(
                    new ReviewTagView("PROFESSIONAL", "巡检专业", 126L));
            assertThat(reviews.items()).singleElement().satisfies(review -> {
                assertThat(review.id()).isEqualTo(reviewId);
                assertThat(review.imageAssets()).containsExactly(
                        new ReviewImageView(null, "POWER_LINE_IMAGE"));
            });
        });
    }

    @Test
    void returnsEmptyReviewSummaryWhenServiceHasNoReviews() {
        UUID serviceId = UUID.randomUUID();
        StoredInspectionServiceDetail stored =
                DetailCatalogStore.detailWithoutReviews(serviceId);
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(
                        new DetailCatalogStore(stored, null));

        InspectionServiceDetailView detail =
                service.findDetail(serviceId, "370500");

        assertThat(detail.reviews()).isEqualTo(
                new ReviewSummaryView(null, 0L, null, List.of(), List.of()));
    }

    @Test
    void returnsNullPricingFieldsWhenNothingIsConfigured() {
        UUID serviceId = UUID.randomUUID();
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(new DetailCatalogStore(
                        DetailCatalogStore.detailWithoutReviews(serviceId), null));

        InspectionServiceDetailView detail =
                service.findDetail(serviceId, "370500");

        assertThat(detail.priceNote()).isNull();
        assertThat(detail.priceFactors()).isNull();
        assertThat(detail.cancellationNote()).isNull();
        assertThat(detail.serviceCities()).isNull();
    }

    @Test
    void capsPriceFactorsAtContractLimit() {
        UUID serviceId = UUID.randomUUID();
        List<StoredDetailItem> factors = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(no -> new StoredDetailItem(
                        "FACTOR_" + no, "因素" + no, null, null, null, null))
                .toList();
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(new DetailCatalogStore(
                        DetailCatalogStore.detailWithoutReviews(serviceId),
                        null,
                        factors,
                        List.of()));

        InspectionServiceDetailView detail =
                service.findDetail(serviceId, "370500");

        assertThat(detail.priceFactors()).hasSize(10).startsWith("因素1").endsWith("因素10");
    }

    @Test
    void validatesDetailLookupAndRejectsMissingService() {
        UUID serviceId = UUID.randomUUID();
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(new EmptyCatalogStore());

        assertThatThrownBy(() -> service.findDetail(serviceId, "../bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findDetail(serviceId, "370500"))
                .isInstanceOf(InspectionServiceNotFoundException.class);
    }
}

final class EmptyCatalogStore implements InspectionServiceCatalogReadStore {

    @Override
    public long countActive(InspectionCatalogCriteria criteria) {
        return 0;
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            InspectionCatalogCriteria criteria,
            InspectionCatalogSort sort,
            int limit,
            int offset
    ) {
        return List.of();
    }
}

final class CardCatalogStore implements InspectionServiceCatalogReadStore {

    private final List<StoredInspectionServiceCard> cards;

    CardCatalogStore(List<StoredInspectionServiceCard> cards) {
        this.cards = cards;
    }

    @Override
    public long countActive(InspectionCatalogCriteria criteria) {
        return cards.size();
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            InspectionCatalogCriteria criteria,
            InspectionCatalogSort sort,
            int limit,
            int offset
    ) {
        return cards;
    }
}

final class DetailCatalogStore implements InspectionServiceCatalogReadStore {

    private final StoredInspectionServiceDetail detail;
    private final UUID reviewId;
    private final List<StoredDetailItem> priceFactors;
    private final List<StoredServiceCity> cities;

    DetailCatalogStore(StoredInspectionServiceDetail detail, UUID reviewId) {
        this(detail, reviewId, List.of(), List.of());
    }

    DetailCatalogStore(
            StoredInspectionServiceDetail detail,
            UUID reviewId,
            List<StoredDetailItem> priceFactors,
            List<StoredServiceCity> cities
    ) {
        this.detail = detail;
        this.reviewId = reviewId;
        this.priceFactors = priceFactors;
        this.cities = cities;
    }

    static StoredInspectionServiceDetail detailWithoutReviews(UUID serviceId) {
        return new StoredInspectionServiceDetail(
                serviceId,
                "INS-SVC-001",
                "POWER",
                "输电巡检",
                "输电线路无人机巡检",
                "杆塔精细巡检",
                null,
                "PROJECT_QUOTE",
                null,
                "CNY",
                null,
                null,
                null,
                null,
                "AVAILABLE",
                "REQUEST_QUOTE",
                0L,
                UUID.randomUUID(),
                "云翼科技",
                null,
                "FEIXIANG_APP_ICON",
                "企业认证",
                false,
                "专业飞手团队",
                null,
                0L,
                null);
    }

    @Override
    public long countActive(InspectionCatalogCriteria criteria) {
        return 0;
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            InspectionCatalogCriteria criteria,
            InspectionCatalogSort sort,
            int limit,
            int offset
    ) {
        return List.of();
    }

    @Override
    public Optional<StoredInspectionServiceDetail> findActiveDetail(
            UUID serviceId,
            String cityCode
    ) {
        return detail.serviceId().equals(serviceId)
                ? Optional.of(detail)
                : Optional.empty();
    }

    @Override
    public List<StoredDetailItem> findBookingItems(UUID serviceId) {
        return List.of(new StoredDetailItem(
                "TOWER",
                "杆塔精细巡检",
                "精细化巡检杆塔本体",
                "tower",
                null,
                null));
    }

    @Override
    public List<StoredDetailItem> findDetailItems(
            UUID serviceId,
            InspectionDetailSectionType sectionType
    ) {
        return switch (sectionType) {
            case CAPABILITY -> List.of(new StoredDetailItem(
                    "MODELING", "高精度建模", null, "checkbox", null, null));
            case PROCESS -> List.of(new StoredDetailItem(
                    "REQUEST", "提交需求", null, null, null, null));
            case DELIVERABLE -> List.of(new StoredDetailItem(
                    "IMAGES", "高清巡检影像", null, "image", null,
                    "POWER_LINE_IMAGE"));
            case GUARANTEE -> List.of(new StoredDetailItem(
                    "PILOT", "专业飞手", null, "staff", null, null));
            case PRICE_FACTOR -> priceFactors;
        };
    }

    @Override
    public List<StoredServiceCity> findActiveCities(UUID serviceId) {
        return cities;
    }

    @Override
    public List<StoredReviewTag> findReviewTags(UUID serviceId) {
        return reviewId == null
                ? List.of()
                : List.of(new StoredReviewTag(
                        "PROFESSIONAL", "巡检专业", 126L));
    }

    @Override
    public List<StoredReview> findReviewPreview(
            UUID serviceId,
            int limit
    ) {
        return reviewId == null
                ? List.of()
                : List.of(new StoredReview(
                        reviewId,
                        "李**",
                        5,
                        LocalDate.of(2026, 7, 26),
                        "飞手很专业，报告中的缺陷标注得很清楚。"));
    }

    @Override
    public List<StoredReviewImage> findReviewImages(UUID reviewId) {
        return List.of(new StoredReviewImage(
                reviewId,
                null,
                "POWER_LINE_IMAGE"));
    }
}

final class RecordingCatalogStore implements InspectionServiceCatalogReadStore {

    InspectionCatalogCriteria criteria;
    InspectionCatalogSort sort;
    int limit;
    int offset;

    @Override
    public long countActive(InspectionCatalogCriteria criteria) {
        this.criteria = criteria;
        return 7;
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            InspectionCatalogCriteria criteria,
            InspectionCatalogSort sort,
            int limit,
            int offset
    ) {
        assertThat(criteria).isEqualTo(this.criteria);
        this.sort = sort;
        this.limit = limit;
        this.offset = offset;
        return List.of(PortalCatalogStore.parkCard(3_811.6));
    }
}

final class PortalCatalogStore implements InspectionServiceCatalogReadStore {

    static final OffsetDateTime UPDATED_AT = OffsetDateTime.parse("2026-11-15T08:00:00+08:00");

    private final boolean cityOpen;
    private final boolean hasHeadline;

    PortalCatalogStore(boolean cityOpen, boolean hasHeadline) {
        this.cityOpen = cityOpen;
        this.hasHeadline = hasHeadline;
    }

    static StoredInspectionServiceCard parkCard(Double distanceMeters) {
        return new StoredInspectionServiceCard(
                UUID.fromString("77777777-7777-7777-7777-777777777777"),
                "INS-SVC-DEV-PARK",
                "PARK",
                "园区",
                "园区综合巡检服务",
                "安防巡查",
                null,
                List.of(),
                "PROJECT_QUOTE",
                null,
                "CNY",
                null,
                null,
                null,
                "AVAILABLE",
                "REQUEST_QUOTE",
                0L,
                distanceMeters);
    }

    @Override
    public long countActive(InspectionCatalogCriteria criteria) {
        return 0;
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            InspectionCatalogCriteria criteria,
            InspectionCatalogSort sort,
            int limit,
            int offset
    ) {
        return List.of();
    }

    @Override
    public boolean existsVisibleInCity(String cityCode) {
        return cityOpen;
    }

    @Override
    public List<StoredInspectionServiceCard> findMostCompleted(String cityCode, int limit) {
        assertThat(limit).isEqualTo(4);
        return List.of(parkCard(null));
    }

    @Override
    public List<StoredInspectionCategory> findTopLevelCategories() {
        return List.of(
                new StoredInspectionCategory("POWER", "电力", " ", 10),
                new StoredInspectionCategory("PARK", "园区", "安防与设备", 50));
    }

    @Override
    public List<StoredPortalContent> findPortalContent(String cityCode) {
        List<StoredPortalContent> steps = List.of(
                new StoredPortalContent(
                        InspectionPortalContentType.PROCESS,
                        "BOOK", "提交预约", "选服务和时间", null, "http://unsafe.example/a.png", 10),
                new StoredPortalContent(
                        InspectionPortalContentType.PROCESS,
                        "PLAN", "确认方案", null, null, null, 20));
        if (!hasHeadline) {
            return steps;
        }
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(new StoredPortalContent(
                        InspectionPortalContentType.HERO,
                        "DEFAULT", "专业巡检", null, "隐患早发现", null, 0)),
                steps.stream()).toList();
    }

    @Override
    public Optional<OffsetDateTime> findCatalogConfigUpdatedAt() {
        return Optional.of(UPDATED_AT);
    }

    @Override
    public Optional<StoredServiceCity> findCity(String cityCode) {
        return Optional.of(new StoredServiceCity("370500", "东营市", "370000"));
    }

    @Override
    public List<String> findServiceMethodsInCity(String cityCode) {
        return List.of("ROBOT", "UAV");
    }

    @Override
    public List<String> findPriceModesInCity(String cityCode) {
        return List.of("PER_VISIT", "PROJECT_QUOTE");
    }

    @Override
    public boolean existsReportServiceInCity(String cityCode) {
        return true;
    }
}
