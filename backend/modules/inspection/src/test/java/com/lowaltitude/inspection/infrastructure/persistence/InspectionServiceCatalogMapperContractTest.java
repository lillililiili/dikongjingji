package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class InspectionServiceCatalogMapperContractTest {

    @Test
    void usesBoundSortWhitelistAndStablePowerOrdering() throws Exception {
        Method method = InspectionServiceCatalogMapper.class.getMethod(
                "findActivePage",
                String.class,
                String.class,
                String.class,
                int.class,
                int.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("#{sort}")
                .contains("THEN completed_count END DESC")
                .contains("category_code IN")
                .contains("ORDER BY")
                .contains("id ASC")
                .doesNotContain("${");
    }
}
