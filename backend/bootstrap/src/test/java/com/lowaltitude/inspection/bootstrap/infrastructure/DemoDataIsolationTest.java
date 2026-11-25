package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DemoDataIsolationTest {
    // Inspect the actual conditional bean graph without initializing the migration beans against a mock database.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModuleFlywayConfiguration.class, DevelopmentSeedDateRefreshJob.class)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost/test")
            .withInitializer(context -> context.addBeanFactoryPostProcessor(factory -> {
                for (String name : factory.getBeanDefinitionNames()) {
                    factory.getBeanDefinition(name).setLazyInit(true);
                }
            }));

    @Test
    void dailyWorkspaceRegistersOnlySchemaMigrationsEvenIfRefreshWasRequested() {
        runner.withPropertyValues("spring.profiles.active=dev", "platform.demo-data.refresh-at-midnight=true")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.containsBean("inspectionFlyway")).isTrue();
                    assertThat(Arrays.stream(context.getBeanDefinitionNames()))
                            .noneMatch(name -> name.endsWith("DevelopmentDataFlyway"));
                    assertThat(context.getBeanNamesForType(DevelopmentSeedDateRefreshJob.class)).isEmpty();
                });
    }

    @Test
    void isolatedDemoCanLoadFixturesWithoutRollingDatesDuringATest() {
        runner.withPropertyValues("spring.profiles.active=dev", "platform.demo-data.enabled=true")
                .run(context -> {
                    assertThat(context.containsBean("activityDevelopmentDataFlyway")).isTrue();
                    assertThat(context.containsBean("identityOperatorDevelopmentDataFlyway")).isTrue();
                    assertThat(context.getBeanNamesForType(DevelopmentSeedDateRefreshJob.class)).isEmpty();
                });
    }

    @Test
    void dailyRefreshNeedsBothDemoSettingsAndTheDevelopmentProfile() {
        var demo = runner.withPropertyValues("platform.demo-data.enabled=true", "platform.demo-data.refresh-at-midnight=true");
        demo.withPropertyValues("spring.profiles.active=dev").run(context ->
                assertThat(context.getBeanNamesForType(DevelopmentSeedDateRefreshJob.class)).hasSize(1));
        demo.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context.containsBean("activityDevelopmentDataFlyway")).isFalse();
            assertThat(context.getBeanNamesForType(DevelopmentSeedDateRefreshJob.class)).isEmpty();
        });
    }
}
