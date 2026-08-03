package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class InspectionServiceDetailMapperSqlTest {

    @Test
    void catalogQueriesUseActiveMultiCityCoverage() throws Exception {
        assertSelect(
                "countActive",
                new Class<?>[]{String.class, String.class},
                "inspection_service_city",
                "#{cityCode}",
                "coverage.status = 'ACTIVE'");
        assertSelect(
                "findActivePage",
                new Class<?>[]{String.class, String.class, String.class, int.class, int.class},
                "inspection_service_city",
                "#{cityCode}",
                "coverage.status = 'ACTIVE'");
        assertSelect(
                "findActiveDetail",
                new Class<?>[]{UUID.class, String.class},
                "inspection_service_city",
                "#{cityCode}",
                "coverage.status = 'ACTIVE'");
    }

    @Test
    void detailQueriesAreParameterizedAndFilterVisibleRows() throws Exception {
        assertSelect(
                "findActiveDetail",
                new Class<?>[]{UUID.class, String.class},
                "#{serviceId}",
                "#{cityCode}",
                "service.status = 'ACTIVE'",
                "organization_type = 'PROVIDER'");
        assertSelect(
                "findBookingDetailItems",
                new Class<?>[]{UUID.class},
                "#{serviceId}",
                "status = 'ACTIVE'",
                "ORDER BY display_order, id");
        assertSelect(
                "findDetailItems",
                new Class<?>[]{UUID.class, String.class},
                "#{serviceId}",
                "#{sectionType}",
                "status = 'ACTIVE'",
                "ORDER BY display_order, id");
        assertSelect(
                "findReviewTags",
                new Class<?>[]{UUID.class},
                "#{serviceId}",
                "review.status = 'ACTIVE'",
                "count(*)");
        assertSelect(
                "findReviewPreview",
                new Class<?>[]{UUID.class, int.class},
                "#{serviceId}",
                "#{limit}",
                "status = 'ACTIVE'",
                "ORDER BY reviewed_at DESC, id");
        assertSelect(
                "findReviewImages",
                new Class<?>[]{UUID.class},
                "#{reviewId}",
                "status = 'ACTIVE'",
                "ORDER BY display_order, id");
    }

    private static void assertSelect(
            String methodName,
            Class<?>[] parameterTypes,
            String... fragments
    ) throws Exception {
        Method method = InspectionServiceCatalogMapper.class.getMethod(
                methodName,
                parameterTypes);
        Select annotation = method.getAnnotation(Select.class);
        assertThat(annotation).isNotNull();
        String sql = String.join("\n", annotation.value());
        assertThat(sql).contains(fragments);
        assertThat(Arrays.stream(method.getParameters()))
                .allSatisfy(parameter -> assertThat(parameter.getAnnotations())
                        .isNotEmpty());
    }
}
