package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ModuleFlywayFactoryTest {

    @Test
    void keepsIdentityAndInspectionVersionsInSeparateHistoryTables() {
        ModuleFlywayFactory factory = new ModuleFlywayFactory();
        DataSource dataSource = mock(DataSource.class);

        Flyway identity = factory.identity(dataSource);
        Flyway inspection = factory.inspection(dataSource);

        assertThat(identity.getConfiguration().getTable()).isEqualTo("flyway_history_identity");
        assertThat(locationDescriptors(identity)).containsExactly("classpath:db/migration/identity");
        assertThat(identity.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(inspection.getConfiguration().getTable()).isEqualTo("flyway_history_inspection");
        assertThat(locationDescriptors(inspection)).containsExactly("classpath:db/migration/inspection");
        assertThat(inspection.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(inspection.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    private static String[] locationDescriptors(Flyway flyway) {
        return Arrays.stream(flyway.getConfiguration().getLocations())
                .map(Object::toString)
                .toArray(String[]::new);
    }
}
