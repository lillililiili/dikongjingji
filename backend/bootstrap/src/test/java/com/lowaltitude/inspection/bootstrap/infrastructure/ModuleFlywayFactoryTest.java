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

    @Test
    void keepsActivityVersionsInTheirOwnHistoryTable() {
        Flyway activity = new ModuleFlywayFactory().activity(mock(DataSource.class));

        assertThat(activity.getConfiguration().getTable()).isEqualTo("flyway_history_activity");
        assertThat(locationDescriptors(activity)).containsExactly("classpath:db/migration/activity");
        assertThat(activity.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(activity.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsExperienceVersionsInTheirOwnHistoryTable() {
        Flyway experience = new ModuleFlywayFactory().experience(mock(DataSource.class));

        assertThat(experience.getConfiguration().getTable()).isEqualTo("flyway_history_experience");
        assertThat(locationDescriptors(experience)).containsExactly("classpath:db/migration/experience");
        assertThat(experience.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(experience.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsNotificationVersionsInTheirOwnHistoryTable() {
        Flyway notification = new ModuleFlywayFactory().notification(mock(DataSource.class));

        assertThat(notification.getConfiguration().getTable()).isEqualTo("flyway_history_notification");
        assertThat(locationDescriptors(notification)).containsExactly("classpath:db/migration/notification");
        assertThat(notification.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(notification.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsMallVersionsInTheirOwnHistoryTable() {
        Flyway mall = new ModuleFlywayFactory().mall(mock(DataSource.class));

        assertThat(mall.getConfiguration().getTable()).isEqualTo("flyway_history_mall");
        assertThat(locationDescriptors(mall)).containsExactly("classpath:db/migration/mall");
        assertThat(mall.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(mall.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    private static String[] locationDescriptors(Flyway flyway) {
        return Arrays.stream(flyway.getConfiguration().getLocations())
                .map(Object::toString)
                .toArray(String[]::new);
    }
}
