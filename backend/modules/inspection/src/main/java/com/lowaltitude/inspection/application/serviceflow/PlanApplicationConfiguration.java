package com.lowaltitude.inspection.application.serviceflow;

import java.time.Clock;

import com.lowaltitude.inspection.application.reservation.AesGcmSensitiveDataCipher;
import com.lowaltitude.inspection.infrastructure.persistence.ServiceFlowPersistenceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PlanApplicationConfiguration {

    @Bean
    PlanApplicationService planApplicationService(
            ServiceFlowPersistenceMapper mapper,
            @Value("${INSPECTION_DATA_ENCRYPTION_BASE64_KEY}") String encryptionKey
    ) {
        return new PlanApplicationService(
                new PostgresPlanStore(
                        mapper,
                        new AesGcmSensitiveDataCipher(encryptionKey)),
                Clock.systemUTC());
    }
}
