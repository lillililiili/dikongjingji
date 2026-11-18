package com.lowaltitude.inspection.application.outbox;

import java.time.Clock;

import com.lowaltitude.inspection.infrastructure.persistence.InspectionOutboxMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class InspectionOutboxConfiguration {

    // 没有订阅方时不建转发器：没人消费的事件不能被标成已发布。
    @Bean
    InspectionOutboxRelayHolder inspectionOutboxRelay(
            InspectionOutboxMapper mapper,
            PlatformTransactionManager transactionManager,
            ObjectProvider<InspectionProgressEventSink> sink
    ) {
        InspectionProgressEventSink subscriber = sink.getIfAvailable();
        return new InspectionOutboxRelayHolder(subscriber == null
                ? null
                : new InspectionOutboxRelay(
                        new PostgresInspectionOutboxStore(mapper),
                        subscriber,
                        new TransactionTemplate(transactionManager),
                        Clock.systemUTC()));
    }

    /** 转发器可能不存在（没有订阅方）；定时任务通过它取。 */
    public record InspectionOutboxRelayHolder(InspectionOutboxRelay relay) {
    }
}
