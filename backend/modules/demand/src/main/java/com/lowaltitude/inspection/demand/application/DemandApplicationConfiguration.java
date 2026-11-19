package com.lowaltitude.inspection.demand.application;

import java.time.Clock;

import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 需求与报价（F1）。城市目录、服务商名录、站内信由启动模块接上（DemandPorts）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = DemandMapper.class)
public class DemandApplicationConfiguration {

    @Bean
    DemandService demandService(
            DemandMapper mapper,
            DemandPorts.CityDirectory cities,
            DemandPorts.ProviderDirectory providers,
            DemandPorts.Notifier notifier
    ) {
        return new DemandService(mapper, cities, providers, notifier, Clock.systemUTC());
    }
}
