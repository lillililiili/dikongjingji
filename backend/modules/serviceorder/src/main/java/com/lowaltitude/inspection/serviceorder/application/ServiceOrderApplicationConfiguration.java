package com.lowaltitude.inspection.serviceorder.application;

import java.time.Clock;

import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 通用服务订单（S4-3）。交易付款、账号显示名和资质、交付文件存储由启动模块接上（ServiceOrderPorts）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = ServiceOrderMapper.class)
public class ServiceOrderApplicationConfiguration {

    @Bean
    ServiceOrderService serviceOrderService(
            ServiceOrderMapper mapper,
            ServiceOrderPorts.Payments payments,
            ServiceOrderPorts.People people,
            ServiceOrderPorts.Files files,
            PlatformTransactionManager transactionManager
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return new ServiceOrderService(mapper, payments, people, files,
                new ServiceOrderService.Transactions() {
                    @Override
                    public <T> T inTransaction(java.util.function.Supplier<T> work) {
                        return template.execute(status -> work.get());
                    }
                },
                Clock.systemUTC());
    }
}
