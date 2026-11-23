package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * 开发种子的日期跟着北京的“今天”走：启动时按当天跑；后端一直开着时，北京时间零点用新日期把四份种子再跑一遍。
 */
class DevelopmentSeedDateRefreshJobTest {

    // 2026-11-22 16:30 UTC：纽约还是 11/22，北京已经是 11/23 00:30。
    private static final Clock AFTER_BEIJING_MIDNIGHT = Clock.fixed(Instant.parse("2026-11-22T16:30:00Z"), ZoneOffset.UTC);

    @Test
    void theSeedDateIsTheBeijingDayWhateverTheServerZone() {
        assertThat(ModuleFlywayConfiguration.seedDatePlaceholder(AFTER_BEIJING_MIDNIGHT))
                .containsExactlyEntriesOf(java.util.Map.of(ModuleFlywayConfiguration.SEED_DATE_PLACEHOLDER, "2026-11-23"));
        assertThat(ModuleFlywayConfiguration.seedDatePlaceholder(
                AFTER_BEIJING_MIDNIGHT.withZone(ZoneId.of("America/New_York"))))
                .containsEntry(ModuleFlywayConfiguration.SEED_DATE_PLACEHOLDER, "2026-11-23");
    }

    @Test
    void runsOnlyInDevWithADatabaseAtBeijingMidnight() throws Exception {
        assertThat(DevelopmentSeedDateRefreshJob.class.getAnnotation(Profile.class).value()).containsExactly("dev");
        ConditionalOnProperty condition = DevelopmentSeedDateRefreshJob.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.prefix()).isEqualTo("spring.datasource");
        assertThat(condition.name()).containsExactly("url");

        Scheduled scheduled = DevelopmentSeedDateRefreshJob.class.getMethod("run").getAnnotation(Scheduled.class);
        assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");
        ZonedDateTime evening = ZonedDateTime.of(LocalDateTime.parse("2026-11-22T21:00:00"), ZoneId.of("Asia/Shanghai"));
        assertThat(CronExpression.parse(scheduled.cron()).next(evening))
                .isEqualTo(ZonedDateTime.of(LocalDateTime.parse("2026-11-23T00:00:00"), ZoneId.of("Asia/Shanghai")));
    }

    // 和启动时的四个 Bean 是同样的位置和历史表，日期是新的一天；内容排在活动、体验之后。
    @Test
    void rebuildsTheFourDatedSeedsWithTheNewDayInDependencyOrder() {
        List<Flyway> seeds = new DevelopmentSeedDateRefreshJob(mock(DataSource.class), AFTER_BEIJING_MIDNIGHT).seeds();

        assertThat(seeds).extracting(seed -> seed.getConfiguration().getTable()).containsExactly(
                "flyway_history_activity_development", "flyway_history_experience_development",
                "flyway_history_content_development", "flyway_history_live_development");
        assertThat(seeds).extracting(seed -> Arrays.stream(seed.getConfiguration().getLocations())
                .map(Object::toString).toList()).containsExactly(
                List.of("classpath:db/development/activity"), List.of("classpath:db/development/experience"),
                List.of("classpath:db/development/content"), List.of("classpath:db/development/live"));
        assertThat(seeds).allSatisfy(seed -> {
            assertThat(seed.getConfiguration().getPlaceholders())
                    .containsEntry(ModuleFlywayConfiguration.SEED_DATE_PLACEHOLDER, "2026-11-23");
            assertThat(seed.getConfiguration().isBaselineOnMigrate()).isTrue();
        });
    }

    @Test
    void oneFailedSeedDoesNotStopTheOthers() {
        Flyway failing = seed("flyway_history_activity_development");
        when(failing.migrate()).thenThrow(new IllegalStateException("database unavailable"));
        Flyway next = seed("flyway_history_experience_development");
        MigrateResult reran = new MigrateResult();
        reran.migrationsExecuted = 1;
        when(next.migrate()).thenReturn(reran);
        DevelopmentSeedDateRefreshJob job = new DevelopmentSeedDateRefreshJob(mock(DataSource.class), AFTER_BEIJING_MIDNIGHT);

        assertThatCode(() -> job.refresh(List.of(failing, next))).doesNotThrowAnyException();
        verify(failing).migrate();
        verify(next).migrate();
    }

    private static Flyway seed(String table) {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(configuration.getTable()).thenReturn(table);
        when(flyway.getConfiguration()).thenReturn(configuration);
        return flyway;
    }
}
