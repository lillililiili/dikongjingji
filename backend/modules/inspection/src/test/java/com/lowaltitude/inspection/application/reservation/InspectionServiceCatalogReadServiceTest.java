package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
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
                    public long countActive(String cityCode, String categoryCode) {
                        assertThat(categoryCode).isEqualTo("POWER");
                        return 1;
                    }

                    @Override
                    public List<StoredInspectionServiceCard> findActivePage(
                            String cityCode,
                            String categoryCode,
                            InspectionCatalogSort sort,
                            int limit,
                            int offset
                    ) {
                        assertThat(categoryCode).isEqualTo("POWER");
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
                                8_560L));
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
                0L);
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
    public long countActive(String cityCode, String categoryCode) {
        return 0;
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            String cityCode,
            String categoryCode,
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
    public long countActive(String cityCode, String categoryCode) {
        return cards.size();
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            String cityCode,
            String categoryCode,
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
                "专业飞手团队",
                null,
                0L,
                null);
    }

    @Override
    public long countActive(String cityCode, String categoryCode) {
        return 0;
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            String cityCode,
            String categoryCode,
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
