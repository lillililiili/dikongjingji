package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.trade.application.TradeReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * 定时对账（E06）只是把调用转给服务：没有对账服务（没有数据库）时什么都不做；一次失败不影响下一次。
 * 补哪几天由服务按北京时间决定（TradeReconciliationServiceTest 里测）。
 */
class TradeReconciliationJobTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<TradeReconciliationService> provider = mock(ObjectProvider.class);
    private final TradeReconciliationJob job = new TradeReconciliationJob(provider);

    @Test
    void withoutAReconciliationServiceTheJobDoesNothing() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(job::run).doesNotThrowAnyException();
        verify(provider).getIfAvailable();
    }

    @Test
    void everyRunCatchesUpOnTheRecentDays() {
        TradeReconciliationService service = mock(TradeReconciliationService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.reconcileRecentDays()).thenReturn(2, 0);

        job.run();
        job.run();

        verify(service, times(2)).reconcileRecentDays();
        verifyNoMoreInteractions(service);
    }

    // 渠道没配（服务抛 CHANNEL_UNAVAILABLE）、数据库不通：记日志，不往外抛，下一轮再试。
    @Test
    void aFailedRunIsSwallowedAndTheNextRunTriesAgain() {
        TradeReconciliationService service = mock(TradeReconciliationService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.reconcileRecentDays())
                .thenThrow(new IllegalStateException("database is down"))
                .thenThrow(new TradeException(TradeException.Error.CHANNEL_UNAVAILABLE))
                .thenReturn(1);

        assertThatCode(job::run).doesNotThrowAnyException();
        assertThatCode(job::run).doesNotThrowAnyException();
        assertThatCode(job::run).doesNotThrowAnyException();

        verify(service, times(3)).reconcileRecentDays();
    }

    // 北京时间每小时的 5 分和 35 分；trade.reconciliation.enabled=false 时不装配（测试配置里关着）。
    @Test
    void theJobRunsTwiceAnHourInBeijingAndCanBeSwitchedOff() throws Exception {
        Scheduled schedule = TradeReconciliationJob.class.getMethod("run").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = TradeReconciliationJob.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(schedule.cron()).isEqualTo("0 5,35 * * * *");
        assertThat(schedule.zone()).isEqualTo("Asia/Shanghai");
        assertThat(TradeReconciliationJob.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(condition.prefix()).isEqualTo("trade.reconciliation");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();

        // 10 点前后各跑的时刻：9:35 只补前天，10:05 是当天第一次能补昨天的。
        CronExpression cron = CronExpression.parse(schedule.cron());
        ZoneId beijing = ZoneId.of(schedule.zone());
        List<LocalDateTime> runs = new ArrayList<>();
        ZonedDateTime at = ZonedDateTime.of(2026, 11, 18, 9, 0, 0, 0, beijing);
        for (int i = 0; i < 4; i++) {
            at = cron.next(at);
            runs.add(at.toLocalDateTime());
        }
        assertThat(runs).containsExactly(
                LocalDateTime.parse("2026-11-18T09:05:00"),
                LocalDateTime.parse("2026-11-18T09:35:00"),
                LocalDateTime.parse("2026-11-18T10:05:00"),
                LocalDateTime.parse("2026-11-18T10:35:00"));
    }

    // 测试配置把定时对账关掉，免得上下文测试里真的跑。
    @Test
    void theTestConfigurationSwitchesTheJobOff() throws Exception {
        String yaml = new String(TradeReconciliationJobTest.class.getResourceAsStream("/application.yml").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        java.util.Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(yaml);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> trade = (java.util.Map<String, Object>) root.get("trade");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> reconciliation = (java.util.Map<String, Object>) trade.get("reconciliation");

        assertThat(reconciliation.get("enabled")).isEqualTo(false);
    }
}
