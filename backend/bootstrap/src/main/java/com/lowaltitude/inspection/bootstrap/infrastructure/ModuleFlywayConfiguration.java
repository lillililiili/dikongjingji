package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

    static final ZoneId SEED_ZONE = ZoneId.of("Asia/Shanghai");

    // 活动、体验、内容、直播的开发种子把场次、发布时间、预告排在“执行当天”前后。可重复迁移只在校验和变化时重跑，
    // 所以把北京日期作为占位符写进种子：日期一变校验和就变，当天第一次启动时自动重排；后端一直开着时，
    // DevelopmentSeedDateRefreshJob 在北京时间零点用新日期再跑一遍。依赖顺序：内容提到活动和场馆，排在它们之后。
    static final List<String> DATED_DEVELOPMENT_SEEDS = List.of("activity", "experience", "content", "live");

    static Map<String, String> seedDatePlaceholder() {
        return seedDatePlaceholder(Clock.system(SEED_ZONE));
    }

    static Map<String, String> seedDatePlaceholder(Clock clock) {
        return Map.of(SEED_DATE_PLACEHOLDER, LocalDate.now(clock.withZone(SEED_ZONE)).toString());
    }

    // db/development/<module> 下按执行当天排日期的种子，历史表 flyway_history_<module>_development。
    static Flyway datedDevelopmentSeed(DataSource dataSource, String module, Map<String, String> placeholders) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/development/" + module)
                .placeholders(placeholders)
                .table("flyway_history_" + module + "_development")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
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
        return datedDevelopmentSeed(dataSource, "activity", seedDatePlaceholder());
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
        return datedDevelopmentSeed(dataSource, "experience", seedDatePlaceholder());
    }

    // 站内信只引用 identity_user；排在巡检之后只是为了让迁移顺序固定。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway notificationFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.notification(dataSource);
    }

    // 需求与报价（F1）的表引用 identity_user、identity_organization，排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    // 服务商资料在巡检模块，需求表不引用它。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway demandFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.demand(dataSource);
    }

    // 四类专业服务（F2）的表引用 identity_organization，排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    // 服务商资料在巡检模块，专业服务表不引用它。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway proserviceFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.proservice(dataSource);
    }

    // 示例专业服务挂在示例服务商名下：服务商机构和服务商资料由巡检的开发数据建（“示例机器人商家”的机构也由商城的开发数据建），
    // 所以排在两份开发数据之后。
    @Bean(initMethod = "migrate")
    @Profile("dev")
    @DependsOn({"proserviceFlyway", "inspectionDevelopmentDataFlyway", "mallDevelopmentDataFlyway"})
    Flyway proserviceDevelopmentDataFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/development/proservice")
                .table("flyway_history_proservice_development")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    // 设备租赁（F3）的表引用 identity_user、identity_organization，排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    // 商城供给、收货地址、交易订单只存 ID 和快照，不引用它们的表。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway rentalFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.rental(dataSource);
    }

    // 客服会话（G1）的表引用 identity_user、identity_organization，排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    // 站内信由通知模块自己的表记，这里不引用。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway supportFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.support(dataSource);
    }

    // 发现页内容（H1）的表引用 identity_organization，排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    // 提到的服务、商品、活动、场馆只存 ID，不引用它们的表。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway contentFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.content(dataSource);
    }

    // 示例内容的作者是商城、专业服务、活动开发数据建的机构，提到的对象指向这些开发数据，所以排在它们之后。
    // 发布时间和专题起止日期相对执行当天，用和活动一样的日期占位符让每天第一次启动重排。
    @Bean(initMethod = "migrate")
    @Profile("dev")
    @DependsOn({"contentFlyway", "proserviceDevelopmentDataFlyway", "activityDevelopmentDataFlyway",
            "experienceDevelopmentDataFlyway"})
    Flyway contentDevelopmentDataFlyway(DataSource dataSource) {
        return datedDevelopmentSeed(dataSource, "content", seedDatePlaceholder());
    }

    // 收藏（计划 08 S5-6）的表引用 identity_user，排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    // 收藏的对象只存类型 + ID，不引用商城、服务、活动、场馆、内容的表。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway favoriteFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.favorite(dataSource);
    }

    // 直播（M20）的表引用 identity_user、identity_organization，排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    // 讲解项只存类型 + ID，不引用商城、服务、活动、场馆的表。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway liveFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.live(dataSource);
    }

    // 示例直播间的主办机构由巡检、商城的开发数据建，dev-merchant 由交易的开发数据建（这里给它开通直播主办），
    // 所以排在它们之后。预告时间相对执行当天，用和活动一样的日期占位符让每天第一次启动重排。
    @Bean(initMethod = "migrate")
    @Profile("dev")
    @DependsOn({"liveFlyway", "identityDevelopmentDataFlyway", "inspectionDevelopmentDataFlyway",
            "mallDevelopmentDataFlyway", "tradeDevelopmentDataFlyway"})
    Flyway liveDevelopmentDataFlyway(DataSource dataSource) {
        return datedDevelopmentSeed(dataSource, "live", seedDatePlaceholder());
    }

    // 收货地址表引用 identity_user，所以排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway accountFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.account(dataSource);
    }

    // 交易表引用 identity_user、identity_organization，排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway tradeFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.trade(dataSource);
    }

    // 商城表引用 identity_organization，所以排在身份迁移之后；与巡检迁移保持同一条链，顺序固定。
    @Bean(initMethod = "migrate")
    @DependsOn("inspectionFlyway")
    Flyway mallFlyway(ModuleFlywayFactory factory, DataSource dataSource) {
        return factory.mall(dataSource);
    }

    // 示例商家和收费活动主办机构的收款账户，引用商城、活动示例数据建的机构，所以排在两份开发数据之后。
    @Bean(initMethod = "migrate")
    @Profile("dev")
    @DependsOn({"tradeFlyway", "mallDevelopmentDataFlyway", "activityDevelopmentDataFlyway"})
    Flyway tradeDevelopmentDataFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/development/trade")
                .table("flyway_history_trade_development")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
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
