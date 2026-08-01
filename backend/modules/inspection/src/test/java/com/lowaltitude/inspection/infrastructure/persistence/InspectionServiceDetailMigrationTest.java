package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class InspectionServiceDetailMigrationTest {

    @Test
    void productionMigrationDefinesDetailReadTablesWithoutSeedRows() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/inspection/V004__inspection_service_detail.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("CREATE TABLE inspection_provider_profile")
                .contains("REFERENCES identity_organization(id)")
                .contains("CREATE TABLE inspection_service_detail_item")
                .contains("'CAPABILITY', 'PROCESS', 'DELIVERABLE', 'GUARANTEE'")
                .contains("CREATE TABLE inspection_service_review")
                .contains("CREATE TABLE inspection_service_review_tag")
                .contains("CREATE TABLE inspection_service_review_image")
                .doesNotContain("INSERT INTO");

        String completionSql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/inspection/V005__inspection_service_completion_count.sql")) {
            assertThat(stream).isNotNull();
            completionSql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(completionSql)
                .contains("completed_count bigint")
                .doesNotContain("INSERT INTO");
    }
}
