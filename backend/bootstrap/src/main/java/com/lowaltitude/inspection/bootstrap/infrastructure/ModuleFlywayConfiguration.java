package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

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

    static final String SEED_DATE_PLACEHOLDER = "devSeedDate";

    // 活动和体验的开发种子把场次排在“执行当天”前后。可重复迁移只在校验和变化时重跑，
    // 所以把北京日期作为占位符写进种子：日期一变校验和就变，当天第一次启动时自动重排场次。
    static Map<String, String> seedDatePlaceholder() {
        return Map.of(SEED_DATE_PLACEHOLDER, LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
    }

    @Bean
    ModuleFlywayFactory moduleFlywayFactory() {
        return new ModuleFlywayFactory();
    }

    @Bean(initMethod = "migrate")
    Flyway identityFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.identity(dataSource);
    }

    // 本地验收和联调用的两个示例账号，密码写在种子里；只有 dev 环境加载。
    @Bean(initMethod = "migrate")
    @Profile("dev")
    @DependsOn("identityFlyway")
    Flyway identityDevelopmentDataFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/development/identity")
                .table("flyway_history_identity_development")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
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
                .placeholders(seedDatePlaceholder())
                .table("flyway_history_activity_development")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    // 场馆表引用 identity_organization，并使用巡检迁移里创建的 postgis 扩展，所以排在两者之后。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway experienceFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.experience(dataSource);
    }

    @Bean(initMethod = "migrate")
    @Profile("dev")
    @DependsOn("experienceFlyway")
    Flyway experienceDevelopmentDataFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/development/experience")
                .placeholders(seedDatePlaceholder())
                .table("flyway_history_experience_development")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    // 站内信只引用 identity_user；排在巡检之后只是为了让迁移顺序固定。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway notificationFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.notification(dataSource);
    }

    // 收货地址表引用 identity_user，所以排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway accountFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.account(dataSource);
    }

    // 商城表引用 identity_organization，所以排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway mallFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.mall(dataSource);
    }

    @Bean(initMethod = "migrate")
    @Profile("dev")
    @DependsOn("mallFlyway")
    Flyway mallDevelopmentDataFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/development/mall")
                .table("flyway_history_mall_development")
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
