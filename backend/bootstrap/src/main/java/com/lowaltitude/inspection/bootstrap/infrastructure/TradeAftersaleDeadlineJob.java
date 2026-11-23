package com.lowaltitude.inspection.bootstrap.infrastructure;

import com.lowaltitude.inspection.trade.application.TradeAftersaleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟推进到了处理时限的售后单（S4-2）：商家 48 小时不处理自动同意；同意后 7 天不填运单自动关闭；
 * 运单提交 10 天商家不处理按申请金额自动原路退款。测试里用 trade.timeout.enabled=false 关掉（和订单超时同一个开关）。
 */
@Component
@ConditionalOnProperty(prefix = "trade.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TradeAftersaleDeadlineJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradeAftersaleDeadlineJob.class);

    private final ObjectProvider<TradeAftersaleService> aftersales;

    public TradeAftersaleDeadlineJob(ObjectProvider<TradeAftersaleService> aftersales) {
        this.aftersales = aftersales;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 40_000)
    public void advanceDueAftersales() {
        TradeAftersaleService service = aftersales.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            int changed = service.processDeadlines();
            if (changed > 0) {
                LOGGER.info("Advanced {} aftersales past their deadline", changed);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Advancing aftersales failed; will retry", failure);
        }
    }
}
