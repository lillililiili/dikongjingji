package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class PlatformCityMigrationTest {

    @Test
    void createsVersionedCityDirectoryWithZeroProductionPopularity() throws Exception {
        String schema = resource("/db/migration/inspection/V006__platform_city_directory.sql");
        String reference = resource("/db/migration/inspection/R__platform_city_reference.sql");

        assertThat(schema)
                .contains("CREATE TABLE platform_city")
                .contains("search_count bigint NOT NULL DEFAULT 0")
                .contains("CHECK (search_count >= 0)")
                .contains("idx_platform_city_hot");
        assertThat(reference)
                .contains("'370100', '济南市'")
                .contains("'370200', '青岛市'")
                .contains("'370500', '东营市'")
                .doesNotContain("search_count");
    }

    @Test
    void providesNationwideSixDigitCityReferenceRows() throws Exception {
        String reference = resource("/db/migration/inspection/R__platform_city_reference.sql");
        Matcher rows = Pattern.compile("\\('(?<cityCode>\\d{6})',.*?\\R\\s*'(?<provinceCode>\\d{6})'", Pattern.DOTALL)
                .matcher(reference);
        var cityCodes = new HashSet<String>();
        var provinceCodes = new HashSet<String>();

        while (rows.find()) {
            cityCodes.add(rows.group("cityCode"));
            provinceCodes.add(rows.group("provinceCode"));
        }

        assertThat(cityCodes).hasSizeGreaterThanOrEqualTo(330);
        assertThat(provinceCodes).hasSizeGreaterThanOrEqualTo(34);
    }

    @Test
    void excludesMunicipalCountyGroupRowsThatAreNotSelectableCities() throws Exception {
        String reference = resource("/db/migration/inspection/R__platform_city_reference.sql");

        assertThat(reference)
                .doesNotContain("'110100', '市辖区'")
                .doesNotContain("'120100', '市辖区'")
                .doesNotContain("'310100', '市辖区'")
                .doesNotContain("'500100', '市辖区'")
                .doesNotContain("'500200', '县'");
    }

    @Test
    void usesAsciiPinyinForUmlautedSyllables() throws Exception {
        String reference = resource("/db/migration/inspection/R__platform_city_reference.sql");

        assertThat(reference).contains("'141100', '吕梁市', 'lvliangshi', 'lls'");
    }

    private String resource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
