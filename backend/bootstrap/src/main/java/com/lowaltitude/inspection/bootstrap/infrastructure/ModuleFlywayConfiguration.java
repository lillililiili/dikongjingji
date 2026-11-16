package com.lowaltitude.inspection.bootstrap.infrastructure;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ModuleFlywayConfiguration {

    @Bean
    ModuleFlywayFactory moduleFlywayFactory() {
        return new ModuleFlywayFactory();
    }

    @Bean(initMethod = "migrate")
    Flyway identityFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.identity(dataSource);
    }

    @Bean(initMethod = "migrate")
    @DependsOn("identityFlyway")
    Flyway inspectionFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.inspection(dataSource);
    }

    // 活动表引用 identity_organization，并使用巡检迁移里创建的 postgis 扩展，所以排在两者之后。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway activityFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.activity(dataSource);
    }

    @Bean(initMethod = "migrate")
    @Profile("dev")
    @DependsOn("activityFlyway")
    Flyway activityDevelopmentDataFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/development/activity")
                .table("flyway_history_activity_development")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    @Bean(initMethod = "migrate")
    @Profile("dev")
    @DependsOn("inspectionFlyway")
    Flyway inspectionDevelopmentDataFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/development/inspection")
                .table("flyway_history_inspection_development")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }
}
