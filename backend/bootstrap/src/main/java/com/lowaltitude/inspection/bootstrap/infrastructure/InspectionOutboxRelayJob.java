package com.lowaltitude.inspection.bootstrap.infrastructure;

import com.lowaltitude.inspection.application.outbox.InspectionOutboxConfiguration.InspectionOutboxRelayHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时把巡检发件箱里的事件转发出去（现在的订阅方是站内信）。
 * 上一轮结束后隔几秒再跑下一轮，不会重叠；可用 inspection.outbox.relay.enabled=false 关掉。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "inspection.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InspectionOutboxRelayJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(InspectionOutboxRelayJob.class);

    private final ObjectProvider<InspectionOutboxRelayHolder> holder;

    public InspectionOutboxRelayJob(ObjectProvider<InspectionOutboxRelayHolder> holder) {
        this.holder = holder;
    }

    @Scheduled(
            initialDelayString = "${inspection.outbox.relay.initial-delay-ms:5000}",
            fixedDelayString = "${inspection.outbox.relay.delay-ms:3000}")
    public void relay() {
        InspectionOutboxRelayHolder current = holder.getIfAvailable();
        if (current == null || current.relay() == null) {
            return;
        }
        try {
            int published = current.relay().relayPending();
            if (published > 0) {
                LOGGER.info("relayed {} inspection events", published);
            }
        } catch (RuntimeException failure) {
            // 这一轮整体失败（例如数据库暂时连不上），下一轮再试。
            LOGGER.warn("inspection outbox relay failed: {}", failure.toString());
        }
    }
}
