package com.lowaltitude.inspection.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IdentityMigrationTest {

    @Test
    void v002AddsNullableDefaultOrganizationForeignKeyAndIndex() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/identity/V002__identity_default_organization.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("ADD COLUMN default_organization_id uuid")
                .doesNotContain("default_organization_id uuid NOT NULL")
                .contains("REFERENCES identity_organization(id)")
                .contains("CREATE INDEX idx_identity_user_default_organization");
    }
}
