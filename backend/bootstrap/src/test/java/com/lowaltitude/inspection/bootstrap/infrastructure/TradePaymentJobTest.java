package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lowaltitude.inspection.trade.application.TradePaymentService;
import com.lowaltitude.inspection.trade.application.TradeRefundService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 支付的定时兜底只是把调用转给服务：先查单补漏掉的回调，再处理有支付单的过期订单，最后重提、查询退款（E05）；
 * 没有支付服务（没有数据库）时什么都不做；一步失败不影响另一步，也不影响下一轮。
 */
class TradePaymentJobTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<TradePaymentService> provider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TradeRefundService> refundProvider = mock(ObjectProvider.class);
    private final TradeRefundService refunds = mock(TradeRefundService.class);
    private final TradePaymentJob job = new TradePaymentJob(provider, refundProvider);

    {
        when(refundProvider.getIfAvailable()).thenReturn(refunds);
    }

    @Test
    void withoutAPaymentServiceTheJobDoesNothing() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(job::run).doesNotThrowAnyException();
        verify(provider).getIfAvailable();
        verifyNoInteractions(refundProvider, refunds);
    }

    @Test
    void everyRunPollsOpenPaymentsAndThenClosesExpiredOrders() {
        TradePaymentService service = mock(TradePaymentService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.pollOpenPayments()).thenReturn(2, 0);
        when(service.closeExpiredWithPayments()).thenReturn(0, 1);

        when(refunds.pollRefunds()).thenReturn(1, 0);

        job.run();
        job.run();

        InOrder order = inOrder(service, refunds);
        order.verify(service).pollOpenPayments();
        order.verify(service).closeExpiredWithPayments();
        order.verify(refunds).pollRefunds();
        order.verify(service).pollOpenPayments();
        order.verify(service).closeExpiredWithPayments();
        order.verify(refunds).pollRefunds();
    }

    @Test
    void aFailedPollDoesNotStopClosingAndAFailedCloseDoesNotStopTheNextRun() {
        TradePaymentService service = mock(TradePaymentService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.pollOpenPayments()).thenThrow(new IllegalStateException("database is down")).thenReturn(0);
        when(service.closeExpiredWithPayments()).thenThrow(new IllegalStateException("database is down"))
                .thenReturn(0);
        when(refunds.pollRefunds()).thenThrow(new IllegalStateException("database is down")).thenReturn(0);

        assertThatCode(job::run).doesNotThrowAnyException();
        assertThatCode(job::run).doesNotThrowAnyException();

        verify(service, times(2)).pollOpenPayments();
        verify(service, times(2)).closeExpiredWithPayments();
        // 前两步都失败的那一轮，退款照样处理。
        verify(refunds, times(2)).pollRefunds();
    }

    @Test
    void withoutARefundServiceOnlyThePaymentStepsRun() {
        TradePaymentService service = mock(TradePaymentService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(refundProvider.getIfAvailable()).thenReturn(null);

        assertThatCode(job::run).doesNotThrowAnyException();

        verify(service).pollOpenPayments();
        verify(service).closeExpiredWithPayments();
        verifyNoInteractions(refunds);
    }

    // 每分钟一次，启动 45 秒后开始（错开超时关单的 30 秒）；trade.payment-jobs.enabled=false 时不装配。
    @Test
    void theJobRunsEveryMinuteAndCanBeSwitchedOff() throws Exception {
        Scheduled schedule = TradePaymentJob.class.getMethod("run").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = TradePaymentJob.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(schedule.fixedDelay()).isEqualTo(60_000);
        assertThat(schedule.initialDelay()).isEqualTo(45_000);
        assertThat(condition.prefix()).isEqualTo("trade.payment-jobs");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}
