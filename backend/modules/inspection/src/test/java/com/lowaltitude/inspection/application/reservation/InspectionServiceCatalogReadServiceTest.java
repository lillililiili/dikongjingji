package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class InspectionServiceCatalogReadServiceTest {

    @Test
    void returnsFrozenCatalogShapeAndHidesNonHttpsCover() {
        UUID serviceId = UUID.randomUUID();
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(new InspectionServiceCatalogReadStore() {
                    @Override
                    public long countActive(String cityCode) {
                        return 1;
                    }

                    @Override
                    public List<StoredInspectionServiceCard> findActivePage(
                            String cityCode,
                            int limit,
                            int offset
                    ) {
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
                                "RESERVE"));
                    }
                });

        InspectionServicePageView page = service.search("370500", 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).singleElement().satisfies(card -> {
            assertThat(card.serviceId()).isEqualTo(serviceId);
            assertThat(card.coverImage()).isNull();
            assertThat(card.features()).containsExactly(
                    new CodeNameView("POWER_LINE", "输电线路"));
            assertThat(card.price().startingAmountMinor()).isEqualTo(120_000L);
        });
    }

    @Test
    void validatesCityAndPaginationBeforeQueryingStore() {
        InspectionServiceCatalogReadService service =
                new InspectionServiceCatalogReadService(new EmptyCatalogStore());

        assertThatThrownBy(() -> service.search("../bad", 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("370500", 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("370500", 1, 51))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

final class EmptyCatalogStore implements InspectionServiceCatalogReadStore {

    @Override
    public long countActive(String cityCode) {
        return 0;
    }

    @Override
    public List<StoredInspectionServiceCard> findActivePage(
            String cityCode,
            int limit,
            int offset
    ) {
        return List.of();
    }
}
