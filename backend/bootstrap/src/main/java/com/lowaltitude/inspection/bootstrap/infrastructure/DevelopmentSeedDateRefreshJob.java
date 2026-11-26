package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 仅开发环境：北京时间每天零点用当天日期重跑活动、体验、内容、直播的开发种子（ModuleFlywayConfiguration.DATED_DEVELOPMENT_SEEDS）。
 * 启动时这几份种子已按当天跑过；后端一直开着跨过零点时，不用重启，场次、发布时间、直播预告也跟着换到新的一天。
 * 日期占位符变了，校验和就变，Flyway 把这几份可重复迁移再执行一次；当天再重启时校验和一致，不会再跑。
 * 一份失败（例如数据库暂时连不上）只记日志，其余照跑；下次启动会补上。
 */
@Component
@Profile("dev")
@DemoDataEnabled
@ConditionalOnBooleanProperty(prefix = "platform.demo-data", name = "refresh-at-midnight")
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class DevelopmentSeedDateRefreshJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(DevelopmentSeedDateRefreshJob.class);

    private final DataSource dataSource;
    private final Clock clock;

    // Two constructors (the other takes a test clock): Spring must be told which one to use.
    @Autowired
    public DevelopmentSeedDateRefreshJob(DataSource dataSource) {
        this(dataSource, Clock.system(ModuleFlywayConfiguration.SEED_ZONE));
    }

    DevelopmentSeedDateRefreshJob(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
    public void run() {
        refresh(seeds());
    }

    // 按依赖顺序给出今天要跑的几份种子：内容提到活动和场馆，排在它们之后。
    List<Flyway> seeds() {
        Map<String, String> placeholders = ModuleFlywayConfiguration.seedDatePlaceholder(clock);
        return ModuleFlywayConfiguration.DATED_DEVELOPMENT_SEEDS.stream()
                .map(module -> ModuleFlywayConfiguration.datedDevelopmentSeed(dataSource, module, placeholders))
                .toList();
    }

    void refresh(List<Flyway> seeds) {
        for (Flyway seed : seeds) {
            try {
                int executed = seed.migrate().migrationsExecuted;
                if (executed > 0) {
                    LOGGER.info("Re-ran {} development seed(s) in {} for the new day", executed,
                            seed.getConfiguration().getTable());
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("Development seed refresh failed for {}; the next start will retry: {}",
                        seed.getConfiguration().getTable(), failure.toString());
            }
        }
    }
}
