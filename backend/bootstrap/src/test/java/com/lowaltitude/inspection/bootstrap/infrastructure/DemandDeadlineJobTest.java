package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lowaltitude.inspection.demand.application.DemandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

/** 需求报价截止（F1）的定时任务只是把调用转给服务：没有需求服务时什么都不做，一次失败不影响下一次。 */
class DemandDeadlineJobTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<DemandService> provider = mock(ObjectProvider.class);
    private final DemandDeadlineJob job = new DemandDeadlineJob(provider);

    @Test
    void withoutADemandServiceTheJobDoesNothing() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(job::closeExpiredDemands).doesNotThrowAnyException();
        verify(provider).getIfAvailable();
    }

    @Test
    void aFailedRunIsLoggedAndTheNextRunTriesAgain() {
        DemandService service = mock(DemandService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.closeExpired()).thenThrow(new IllegalStateException("database is down")).thenReturn(2);

        assertThatCode(job::closeExpiredDemands).doesNotThrowAnyException();
        job.closeExpiredDemands();

        verify(service, times(2)).closeExpired();
    }

    // 每分钟跑一次；测试配置里用 demand.deadline.enabled=false 关掉，没写时默认开着。
    @Test
    void runsEveryMinuteAndCanBeSwitchedOff() throws Exception {
        Scheduled scheduled = DemandDeadlineJob.class.getMethod("closeExpiredDemands").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = DemandDeadlineJob.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(scheduled.fixedDelay()).isEqualTo(60_000);
        assertThat(condition.prefix()).isEqualTo("demand.deadline");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}
