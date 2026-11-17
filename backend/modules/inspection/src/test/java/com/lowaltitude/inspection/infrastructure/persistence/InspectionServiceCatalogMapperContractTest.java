package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class InspectionServiceCatalogMapperContractTest {

    @Test
    void pageQueryBindsEveryConditionAndKeepsAStableOrder() throws Exception {
        String sql = sqlOf("findMatchingPage");

        assertThat(sql)
                .contains("#{sort}")
                .contains("THEN service.completed_count END DESC")
                .contains("COALESCE(category.sort_order, 9999)")
                .contains("category.parent_code = #{categoryCode}")
                .contains("service.title ILIKE #{keyword} ESCAPE")
                .contains("searched.item_label ILIKE #{keyword} ESCAPE")
                .contains("#{methodCode}")
                .contains("#{availabilityCode}")
                .contains("#{priceMode}")
                .contains("AND service.includes_report")
                .contains("service.price_note,")
                .contains("service.id ASC")
                .doesNotContain("${");
    }

    @Test
    void distanceComesFromPostgisAndUnknownDistancesSortLast() throws Exception {
        String sql = sqlOf("findMatchingPage");

        assertThat(sql)
                .contains("ST_Distance(")
                .contains("ST_SetSRID(ST_MakePoint(#{longitude}, #{latitude}), 4326)::geography")
                .contains("LEFT JOIN inspection_service_coverage center")
                .contains("center.city_code = #{cityCode}")
                .contains("NULL::double precision distance_meters")
                .contains("distance_meters ASC NULLS LAST");
    }

    @Test
    void countAndPageShareTheSameConditions() throws Exception {
        assertThat(sqlOf("countMatching"))
                .contains(InspectionServiceCatalogMapper.VISIBLE_IN_CITY)
                .contains(InspectionServiceCatalogMapper.MATCHING_FILTERS)
                .doesNotContain("${");
        assertThat(sqlOf("findMatchingPage"))
                .contains(InspectionServiceCatalogMapper.VISIBLE_IN_CITY)
                .contains(InspectionServiceCatalogMapper.MATCHING_FILTERS);
    }

    @Test
    void everyCityLevelQueryOnlySeesBookableServicesOfThatCity() throws Exception {
        assertThat(InspectionServiceCatalogMapper.VISIBLE_IN_CITY)
                .contains("service.status = 'ACTIVE'")
                .contains("coverage.city_code = #{cityCode}")
                .contains("coverage.status = 'ACTIVE'")
                .contains("FROM inspection_service_booking_item item");
        for (String method : new String[] {
                "existsVisibleInCity",
                "findMostCompleted",
                "findServiceMethodsInCity",
                "findPriceModesInCity",
                "existsReportServiceInCity"}) {
            assertThat(sqlOf(method))
                    .as(method)
                    .contains(InspectionServiceCatalogMapper.VISIBLE_IN_CITY)
                    .doesNotContain("${");
        }
        assertThat(sqlOf("findMostCompleted"))
                .contains("ORDER BY service.completed_count DESC, service.id ASC")
                .contains("LIMIT #{limit}");
    }

    @Test
    void portalContentOfACityReplacesTheNationwideDefaultPerType() throws Exception {
        assertThat(sqlOf("findPortalContent"))
                .contains("content.status = 'ACTIVE'")
                .contains("content.city_code = #{cityCode}")
                .contains("content.city_code IS NULL")
                .contains("own.content_type = content.content_type")
                .doesNotContain("${");
        assertThat(sqlOf("findTopLevelCategories"))
                .contains("parent_code IS NULL")
                .contains("status = 'ACTIVE'")
                .contains("ORDER BY sort_order, code");
    }

    /** The queries are assembled from shared fragments; a missing line break glues two words. */
    @Test
    void assembledQueriesKeepTheirKeywordsApart() throws Exception {
        for (Method method : InspectionServiceCatalogMapper.class.getMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select == null) {
                continue;
            }
            assertThat(String.join("\n", select.value()))
                    .as(method.getName())
                    .doesNotContainPattern("[^\\s(>](FROM|WHERE|ORDER BY|LEFT JOIN|AND|LIMIT) ");
        }
    }

    private static String sqlOf(String methodName) {
        for (Method method : InspectionServiceCatalogMapper.class.getMethods()) {
            if (method.getName().equals(methodName)) {
                return String.join("\n", method.getAnnotation(Select.class).value());
            }
        }
        throw new IllegalArgumentException(methodName);
    }

    @Test
    void detailQueryReadsCancellationNote() throws Exception {
        Method method = InspectionServiceCatalogMapper.class.getMethod(
                "findActiveDetail",
                UUID.class,
                String.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql).contains("service.cancellation_note,");
    }

    @Test
    void serviceCityQueryResolvesNamesFromActivePlatformCityDirectory() throws Exception {
        Method method = InspectionServiceCatalogMapper.class.getMethod(
                "findActiveCities",
                UUID.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("FROM inspection_service_city coverage")
                .contains("JOIN platform_city city")
                .contains("city.status = 'ACTIVE'")
                .contains("coverage.status = 'ACTIVE'")
                .contains("#{serviceId}")
                .doesNotContain("${");
    }
}
