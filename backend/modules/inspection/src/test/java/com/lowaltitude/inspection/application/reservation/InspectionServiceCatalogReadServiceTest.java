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
            assertThat(card.completedCount()).isEqualTo(8_560L);
        });
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
                                "最终报价以现场评估为准",
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
                        reviewId));

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

final class DetailCatalogStore implements InspectionServiceCatalogReadStore {

    private final StoredInspectionServiceDetail detail;
    private final UUID reviewId;

    DetailCatalogStore(StoredInspectionServiceDetail detail, UUID reviewId) {
        this.detail = detail;
        this.reviewId = reviewId;
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
                "最终报价以现场评估为准",
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
            String sectionType
    ) {
        return switch (sectionType) {
            case "CAPABILITY" -> List.of(new StoredDetailItem(
                    "MODELING", "高精度建模", null, "checkbox", null, null));
            case "PROCESS" -> List.of(new StoredDetailItem(
                    "REQUEST", "提交需求", null, null, null, null));
            case "DELIVERABLE" -> List.of(new StoredDetailItem(
                    "IMAGES", "高清巡检影像", null, "image", null,
                    "POWER_LINE_IMAGE"));
            case "GUARANTEE" -> List.of(new StoredDetailItem(
                    "PILOT", "专业飞手", null, "staff", null, null));
            default -> List.of();
        };
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
