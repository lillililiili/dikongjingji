package com.lowaltitude.inspection.bootstrap.infrastructure;

import com.lowaltitude.inspection.demand.application.DemandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟关闭一批过了报价截止时间（提交后 72 小时）还没有有效报价的需求（F1，§22.2 第 2 条），并给发布人发站内信。
 * 有报价的需求截止后不再收新报价，由服务在报价时判断，这里不动。测试里用 demand.deadline.enabled=false 关掉。
 */
@Component
@ConditionalOnProperty(prefix = "demand.deadline", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemandDeadlineJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemandDeadlineJob.class);

    private final ObjectProvider<DemandService> demands;

    public DemandDeadlineJob(ObjectProvider<DemandService> demands) {
        this.demands = demands;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 40_000)
    public void closeExpiredDemands() {
        DemandService service = demands.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            int closed = service.closeExpired();
            if (closed > 0) {
                LOGGER.info("Closed {} demands that got no quote before their deadline", closed);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Closing demands past their quote deadline failed; will retry", failure);
        }
    }
}
