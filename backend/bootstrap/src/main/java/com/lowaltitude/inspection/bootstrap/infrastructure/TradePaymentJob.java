package com.lowaltitude.inspection.bootstrap.infrastructure;

import com.lowaltitude.inspection.trade.application.TradePaymentService;
import com.lowaltitude.inspection.trade.application.TradeRefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付的定时兜底，每分钟一次：
 * <ol>
 *   <li>发起支付 1 分钟后还没有结果的，向微信查单，付了就入账（回调丢了也不会漏单）；</li>
 *   <li>过了付款截止时间、还有进行中支付单的订单，先查单，没付就在微信关单再关订单；</li>
 *   <li>退款：还没提交成功的用同一个退款单号重新提交，处理中和异常的去查询（E05）。</li>
 * </ol>
 * 没有配置支付渠道时什么也不做。测试里用 trade.payment-jobs.enabled=false 关掉。
 */
@Component
@ConditionalOnProperty(prefix = "trade.payment-jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TradePaymentJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradePaymentJob.class);

    private final ObjectProvider<TradePaymentService> payments;
    private final ObjectProvider<TradeRefundService> refunds;

    public TradePaymentJob(ObjectProvider<TradePaymentService> payments, ObjectProvider<TradeRefundService> refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void run() {
        TradePaymentService service = payments.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            int applied = service.pollOpenPayments();
            if (applied > 0) {
                LOGGER.info("Polling found {} paid orders whose notification had not arrived", applied);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Polling open payments failed; will retry", failure);
        }
        try {
            int closed = service.closeExpiredWithPayments();
            if (closed > 0) {
                LOGGER.info("Closed {} unpaid orders after checking their payments", closed);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Closing expired orders with payments failed; will retry", failure);
        }
        TradeRefundService refundService = refunds.getIfAvailable();
        if (refundService == null) {
            return;
        }
        try {
            int changed = refundService.pollRefunds();
            if (changed > 0) {
                LOGGER.info("Polling settled {} refunds", changed);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Polling refunds failed; will retry", failure);
        }
    }
}
