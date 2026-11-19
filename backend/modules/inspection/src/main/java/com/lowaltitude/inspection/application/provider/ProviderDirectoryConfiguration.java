package com.lowaltitude.inspection.application.provider;

import com.lowaltitude.inspection.infrastructure.persistence.InspectionProviderDirectoryMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 服务商名录的 mapper 和巡检其他 mapper 在同一个包里，由 ReservationApplicationConfiguration 扫描。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ProviderDirectoryConfiguration {

    @Bean
    InspectionProviderDirectory inspectionProviderDirectory(InspectionProviderDirectoryMapper mapper) {
        return new InspectionProviderDirectory(mapper);
    }
}
