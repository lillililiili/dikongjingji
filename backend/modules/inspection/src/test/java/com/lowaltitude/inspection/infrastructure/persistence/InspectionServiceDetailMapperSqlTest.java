package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class InspectionServiceDetailMapperSqlTest {

    @Test
    void catalogQueriesUseActiveMultiCityCoverage() throws Exception {
        assertSelect(
                "countMatching",
                new Class<?>[]{
                        String.class, String.class, String.class,
                        List.class, List.class, List.class, Boolean.class},
                "inspection_service_city",
                "#{cityCode}",
                "coverage.status = 'ACTIVE'");
        assertSelect(
                "findMatchingPage",
                new Class<?>[]{
                        String.class, String.class, String.class,
                        List.class, List.class, List.class, Boolean.class,
                        Double.class, Double.class, String.class, int.class, int.class},
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
                "organization_type = 'PROVIDER'",
                // R6-4：资质审核和服务商名称在同一个查询里读，来自机构的审核通过时间。
                "organization.qualification_reviewed_at IS NOT NULL provider_qualification_reviewed");
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
                "JOIN inspection_published_review review",
                "count(*)");
        assertSelect(
                "findReviewPreview",
                new Class<?>[]{UUID.class, int.class},
                "#{serviceId}",
                "#{limit}",
                "FROM inspection_published_review",
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
