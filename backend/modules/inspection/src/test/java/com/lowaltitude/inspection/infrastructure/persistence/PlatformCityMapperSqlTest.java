package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class PlatformCityMapperSqlTest {

    @Test
    void selectsActiveCitiesInStableProvinceAndCityOrder() throws Exception {
        Method method = PlatformCityMapper.class.getMethod("findActiveCities");
        String source = String.join(" ", method.getAnnotation(Select.class).value());

        assertThat(source)
                .contains("ORDER BY province_initials, province_pinyin")
                .contains("city_initials, city_pinyin, city_code")
                .contains("WHERE status = 'ACTIVE'");
    }

    @Test
    void atomicallyIncrementsOnlyActiveCityPopularity() throws Exception {
        Method method = PlatformCityMapper.class.getMethod("incrementSearchCount", String.class);
        String source = String.join(" ", method.getAnnotation(Update.class).value());

        assertThat(source)
                .contains("SET search_count = search_count + 1")
                .contains("version = version + 1")
                .contains("WHERE city_code = #{cityCode}")
                .contains("AND status = 'ACTIVE'");
    }
}
