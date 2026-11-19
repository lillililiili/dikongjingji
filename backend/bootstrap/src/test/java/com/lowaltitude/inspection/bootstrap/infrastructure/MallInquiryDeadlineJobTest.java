package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lowaltitude.inspection.mall.application.MallInquiryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

/** 商城询价单到期（F4）的定时任务只是把调用转给服务：没有询价服务时什么都不做，一次失败不影响下一次。 */
class MallInquiryDeadlineJobTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MallInquiryService> provider = mock(ObjectProvider.class);
    private final MallInquiryDeadlineJob job = new MallInquiryDeadlineJob(provider);

    @Test
    void withoutAMallInquiryServiceTheJobDoesNothing() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(job::closeLapsedInquiries).doesNotThrowAnyException();
    }

    @Test
    void aFailedRunIsLoggedAndTheNextRunTriesAgain() {
        MallInquiryService service = mock(MallInquiryService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.closeLapsed()).thenThrow(new IllegalStateException("database is down")).thenReturn(2);

        assertThatCode(job::closeLapsedInquiries).doesNotThrowAnyException();
        job.closeLapsedInquiries();

        verify(service, times(2)).closeLapsed();
    }

    @Test
    void runsEveryMinuteAndCanBeSwitchedOff() throws Exception {
        Scheduled scheduled = MallInquiryDeadlineJob.class.getMethod("closeLapsedInquiries").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = MallInquiryDeadlineJob.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(scheduled.fixedDelay()).isEqualTo(60_000);
        assertThat(condition.prefix()).isEqualTo("mall.inquiry-deadline");
        assertThat(condition.matchIfMissing()).isTrue();
        assertThat(scheduled.initialDelay()).isEqualTo(50_000);
    }

    @Test
    void theTestConfigurationSwitchesItOff() throws Exception {
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            String yaml = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(yaml).containsPattern("mall:\\s*\\n\\s+inquiry-deadline:\\s*\\n\\s+enabled: false");
        }
    }
}
