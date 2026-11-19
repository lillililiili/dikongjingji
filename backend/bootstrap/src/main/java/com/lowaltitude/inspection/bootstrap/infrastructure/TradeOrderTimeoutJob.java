package com.lowaltitude.inspection.bootstrap.infrastructure;

import com.lowaltitude.inspection.trade.application.TradeOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟关闭一批已过付款截止时间（下单后 30 分钟）、并且没有发起过支付的待付款订单；
 * 再把发货满 10 天还没确认收货的订单自动确认（E05）。
 * 发起过支付的由 {@link TradePaymentJob} 先向微信查单再关。测试里用 trade.timeout.enabled=false 关掉。
 */
@Component
@ConditionalOnProperty(prefix = "trade.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TradeOrderTimeoutJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradeOrderTimeoutJob.class);

    private final org.springframework.beans.factory.ObjectProvider<TradeOrderService> orders;

    public TradeOrderTimeoutJob(org.springframework.beans.factory.ObjectProvider<TradeOrderService> orders) {
        this.orders = orders;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void closeExpiredOrders() {
        TradeOrderService service = orders.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            int closed = service.closeExpired();
            if (closed > 0) {
                LOGGER.info("Closed {} unpaid orders past their payment deadline", closed);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Closing unpaid orders failed; will retry", failure);
        }
        try {
            int confirmed = service.autoConfirmReceipts();
            if (confirmed > 0) {
                LOGGER.info("Confirmed receipt of {} orders shipped 10 days ago", confirmed);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Confirming receipts failed; will retry", failure);
        }
    }
}
