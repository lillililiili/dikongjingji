package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lowaltitude.inspection.rental.application.RentalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

/** 租赁询价单到期（F3）的定时任务只是把调用转给服务：没有租赁服务时什么都不做，一次失败不影响下一次。 */
class RentalDeadlineJobTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RentalService> provider = mock(ObjectProvider.class);
    private final RentalDeadlineJob job = new RentalDeadlineJob(provider);

    @Test
    void withoutARentalServiceTheJobDoesNothing() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(job::closeLapsedInquiries).doesNotThrowAnyException();
    }

    @Test
    void aFailedRunIsLoggedAndTheNextRunTriesAgain() {
        RentalService service = mock(RentalService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.closeLapsed()).thenThrow(new IllegalStateException("database is down")).thenReturn(2);

        assertThatCode(job::closeLapsedInquiries).doesNotThrowAnyException();
        job.closeLapsedInquiries();

        verify(service, times(2)).closeLapsed();
    }

    @Test
    void runsEveryMinuteAndCanBeSwitchedOff() throws Exception {
        Scheduled scheduled = RentalDeadlineJob.class.getMethod("closeLapsedInquiries").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = RentalDeadlineJob.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(scheduled.fixedDelay()).isEqualTo(60_000);
        assertThat(condition.prefix()).isEqualTo("rental.deadline");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}
