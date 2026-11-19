package com.lowaltitude.inspection.bootstrap.infrastructure;

import com.lowaltitude.inspection.trade.application.TradeReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天对账（E06）：北京时间每小时 5 分和 35 分各一次，10 点前只补前天，10 点以后把昨天和前天还没导入成功的子商户账单补上；
 * 账单还在生成、渠道不通时记为失败，下一轮再试。没有配置支付渠道时什么也不做。
 * 测试里用 trade.reconciliation.enabled=false 关掉。
 */
@Component
@ConditionalOnProperty(prefix = "trade.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TradeReconciliationJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(TradeReconciliationJob.class);

    private final ObjectProvider<TradeReconciliationService> reconciliation;

    public TradeReconciliationJob(ObjectProvider<TradeReconciliationService> reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "0 5,35 * * * *", zone = "Asia/Shanghai")
    public void run() {
        TradeReconciliationService service = reconciliation.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            int imported = service.reconcileRecentDays();
            if (imported > 0) {
                LOGGER.info("Imported {} merchant bills for reconciliation", imported);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Reconciliation failed; will retry", failure);
        }
    }
}
