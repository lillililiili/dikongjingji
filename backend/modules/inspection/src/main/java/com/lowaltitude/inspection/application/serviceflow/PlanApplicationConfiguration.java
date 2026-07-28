package com.lowaltitude.inspection.application.serviceflow;

import java.time.Clock;

import com.lowaltitude.inspection.application.reservation.AesGcmSensitiveDataCipher;
import com.lowaltitude.inspection.domain.files.ObjectStoragePort;
import com.lowaltitude.inspection.infrastructure.persistence.ServiceFlowPersistenceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PlanApplicationConfiguration {

    @Bean
    PlanStore planStore(
            ServiceFlowPersistenceMapper mapper,
            @Value("${INSPECTION_DATA_ENCRYPTION_BASE64_KEY}") String encryptionKey
    ) {
        return new PostgresPlanStore(
                mapper,
                new AesGcmSensitiveDataCipher(encryptionKey));
    }

    @Bean
    PlanApplicationService planApplicationService(PlanStore store) {
        return new PlanApplicationService(store, Clock.systemUTC());
    }

    @Bean
    OrderApplicationService orderApplicationService(PlanStore store) {
        return new OrderApplicationService(store, Clock.systemUTC());
    }

    @Bean
    DeliveryStore deliveryStore(ServiceFlowPersistenceMapper mapper) {
        return new PostgresDeliveryStore(mapper);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "inspection.infrastructure.storage",
            name = "enabled",
            havingValue = "true")
    DeliveryApplicationService deliveryApplicationService(
            PlanStore orderStore,
            DeliveryStore deliveryStore,
            ObjectStoragePort storage,
            @Value("${INSPECTION_DATA_ENCRYPTION_BASE64_KEY}") String encryptionKey
    ) {
        return new DeliveryApplicationService(
                orderStore,
                deliveryStore,
                storage,
                new AesGcmSensitiveDataCipher(encryptionKey),
                Clock.systemUTC());
    }
}
