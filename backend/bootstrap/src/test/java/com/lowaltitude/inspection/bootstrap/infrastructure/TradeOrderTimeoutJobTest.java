package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.lowaltitude.inspection.trade.application.TradeOrderService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时关单和自动确认收货（E05）只是把调用转给服务：没有交易服务（没有数据库）时什么都不做；
 * 关单失败不影响确认收货，一次失败不影响下一次。
 */
class TradeOrderTimeoutJobTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<TradeOrderService> provider = mock(ObjectProvider.class);
    private final TradeOrderTimeoutJob job = new TradeOrderTimeoutJob(provider);

    @Test
    void withoutATradeServiceTheJobDoesNothing() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatCode(job::closeExpiredOrders).doesNotThrowAnyException();
        verify(provider).getIfAvailable();
    }

    @Test
    void everyRunClosesExpiredOrdersAndThenConfirmsDueReceipts() {
        TradeOrderService service = mock(TradeOrderService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.closeExpired()).thenReturn(3, 0);
        when(service.autoConfirmReceipts()).thenReturn(0, 2);

        job.closeExpiredOrders();
        job.closeExpiredOrders();

        InOrder order = inOrder(service);
        order.verify(service).closeExpired();
        order.verify(service).autoConfirmReceipts();
        order.verify(service).closeExpired();
        order.verify(service).autoConfirmReceipts();
        verifyNoMoreInteractions(service);
    }

    @Test
    void aFailedCloseDoesNotStopTheReceiptsAndTheNextRunTriesAgain() {
        TradeOrderService service = mock(TradeOrderService.class);
        when(provider.getIfAvailable()).thenReturn(service);
        when(service.closeExpired()).thenThrow(new IllegalStateException("database is down")).thenReturn(1);
        when(service.autoConfirmReceipts()).thenThrow(new IllegalStateException("database is down")).thenReturn(0);

        assertThatCode(job::closeExpiredOrders).doesNotThrowAnyException();
        assertThatCode(job::closeExpiredOrders).doesNotThrowAnyException();

        verify(service, times(2)).closeExpired();
        verify(service, times(2)).autoConfirmReceipts();
    }

    // 每分钟一次，启动 30 秒后开始；trade.timeout.enabled=false 时不装配。
    @Test
    void theJobRunsEveryMinuteAndCanBeSwitchedOff() throws Exception {
        Scheduled schedule = TradeOrderTimeoutJob.class.getMethod("closeExpiredOrders").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = TradeOrderTimeoutJob.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(schedule.fixedDelay()).isEqualTo(60_000);
        assertThat(schedule.initialDelay()).isEqualTo(30_000);
        assertThat(condition.prefix()).isEqualTo("trade.timeout");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}
