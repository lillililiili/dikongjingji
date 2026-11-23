package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoriteTargetType;
import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import org.junit.jupiter.api.Test;

/** 收藏列表里的巡检服务（计划 08 S5-6）：已下架、这个城市不提供或城市没开通都是读不到，别的故障照常抛出。 */
class InspectionFavoriteTargetsTest {

    private static final UUID SERVICE = UUID.fromString("b1000000-0000-4000-8000-000000000001");

    @Test
    void aServiceTheCityDoesNotOfferIsNotFound() {
        InspectionServiceCatalogReadService catalog = mock(InspectionServiceCatalogReadService.class);
        when(catalog.findDetail(SERVICE, "JINAN")).thenThrow(new InspectionServiceNotFoundException());
        when(catalog.findDetail(SERVICE, "ZIBO")).thenThrow(new InspectionCatalogCityNotEnabledException());
        FavoritePorts.TargetReader reader = InspectionFavoriteTargets.reader(catalog);

        assertThat(reader.type()).isEqualTo(FavoriteTargetType.INSPECTION_SERVICE);
        assertThat(reader.find(SERVICE, "JINAN")).isEmpty();
        assertThat(reader.find(SERVICE, "ZIBO")).isEmpty();
    }
}
