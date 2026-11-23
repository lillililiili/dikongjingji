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
    void keepsDemandVersionsInTheirOwnHistoryTable() {
        Flyway demand = new ModuleFlywayFactory().demand(mock(DataSource.class));

        assertThat(demand.getConfiguration().getTable()).isEqualTo("flyway_history_demand");
        assertThat(locationDescriptors(demand)).containsExactly("classpath:db/migration/demand");
        assertThat(demand.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(demand.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsProServiceVersionsInTheirOwnHistoryTable() {
        Flyway proservice = new ModuleFlywayFactory().proservice(mock(DataSource.class));

        assertThat(proservice.getConfiguration().getTable()).isEqualTo("flyway_history_proservice");
        assertThat(locationDescriptors(proservice)).containsExactly("classpath:db/migration/proservice");
        assertThat(proservice.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(proservice.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsRentalVersionsInTheirOwnHistoryTable() {
        Flyway rental = new ModuleFlywayFactory().rental(mock(DataSource.class));

        assertThat(rental.getConfiguration().getTable()).isEqualTo("flyway_history_rental");
        assertThat(locationDescriptors(rental)).containsExactly("classpath:db/migration/rental");
        assertThat(rental.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(rental.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsServiceOrderVersionsInTheirOwnHistoryTable() {
        Flyway serviceorder = new ModuleFlywayFactory().serviceorder(mock(DataSource.class));

        assertThat(serviceorder.getConfiguration().getTable()).isEqualTo("flyway_history_serviceorder");
        assertThat(locationDescriptors(serviceorder)).containsExactly("classpath:db/migration/serviceorder");
        assertThat(serviceorder.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(serviceorder.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsContentVersionsInTheirOwnHistoryTable() {
        Flyway content = new ModuleFlywayFactory().content(mock(DataSource.class));

        assertThat(content.getConfiguration().getTable()).isEqualTo("flyway_history_content");
        assertThat(locationDescriptors(content)).containsExactly("classpath:db/migration/content");
        assertThat(content.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(content.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsSupportVersionsInTheirOwnHistoryTable() {
        Flyway support = new ModuleFlywayFactory().support(mock(DataSource.class));

        assertThat(support.getConfiguration().getTable()).isEqualTo("flyway_history_support");
        assertThat(locationDescriptors(support)).containsExactly("classpath:db/migration/support");
        assertThat(support.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(support.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsCourseVersionsInTheirOwnHistoryTable() {
        Flyway course = new ModuleFlywayFactory().course(mock(DataSource.class));

        assertThat(course.getConfiguration().getTable()).isEqualTo("flyway_history_course");
        assertThat(locationDescriptors(course)).containsExactly("classpath:db/migration/course");
        assertThat(course.getConfiguration().isBaselineOnMigrate()).isTrue();
    }

    @Test
    void keepsLiveVersionsInTheirOwnHistoryTable() {
        Flyway live = new ModuleFlywayFactory().live(mock(DataSource.class));

        assertThat(live.getConfiguration().getTable()).isEqualTo("flyway_history_live");
        assertThat(locationDescriptors(live)).containsExactly("classpath:db/migration/live");
        assertThat(live.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(live.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsMallVersionsInTheirOwnHistoryTable() {
        Flyway mall = new ModuleFlywayFactory().mall(mock(DataSource.class));

        assertThat(mall.getConfiguration().getTable()).isEqualTo("flyway_history_mall");
        assertThat(locationDescriptors(mall)).containsExactly("classpath:db/migration/mall");
        assertThat(mall.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(mall.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    @Test
    void keepsAccountVersionsInTheirOwnHistoryTable() {
        Flyway account = new ModuleFlywayFactory().account(mock(DataSource.class));

        assertThat(account.getConfiguration().getTable()).isEqualTo("flyway_history_account");
        assertThat(locationDescriptors(account)).containsExactly("classpath:db/migration/account");
        assertThat(account.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(account.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    // 收货地址表引用 identity_user：账号迁移排在身份和巡检迁移之后，所有环境都跑。
    @Test
    void accountTablesMigrateAfterTheModulesTheyReference() {
        java.lang.reflect.Method migration = Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("accountFlyway"))
                .findFirst()
                .orElseThrow();

        assertThat(migration.getAnnotation(org.springframework.context.annotation.Profile.class)).isNull();
        assertThat(migration.getAnnotation(org.springframework.context.annotation.DependsOn.class).value())
                .containsExactly("inspectionFlyway");
        assertThat(migration.getAnnotation(org.springframework.context.annotation.Bean.class).initMethod())
                .isEqualTo("migrate");
    }

    @Test
    void keepsTradeVersionsInTheirOwnHistoryTable() {
        Flyway trade = new ModuleFlywayFactory().trade(mock(DataSource.class));

        assertThat(trade.getConfiguration().getTable()).isEqualTo("flyway_history_trade");
        assertThat(locationDescriptors(trade)).containsExactly("classpath:db/migration/trade");
        assertThat(trade.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(trade.getConfiguration().getBaselineVersion().getVersion()).isEqualTo("0");
    }

    // 交易表引用 identity_user、identity_organization：交易迁移排在身份和巡检迁移之后，所有环境都跑。
    @Test
    void tradeTablesMigrateAfterTheModulesTheyReference() {
        java.lang.reflect.Method migration = Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("tradeFlyway"))
                .findFirst()
                .orElseThrow();

        assertThat(migration.getAnnotation(org.springframework.context.annotation.Profile.class)).isNull();
        assertThat(migration.getAnnotation(org.springframework.context.annotation.DependsOn.class).value())
                .containsExactly("inspectionFlyway");
        assertThat(migration.getAnnotation(org.springframework.context.annotation.Bean.class).initMethod())
                .isEqualTo("migrate");
    }

    private static String[] locationDescriptors(Flyway flyway) {
        return Arrays.stream(flyway.getConfiguration().getLocations())
                .map(Object::toString)
                .toArray(String[]::new);
    }
}
