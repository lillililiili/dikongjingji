package com.lowaltitude.inspection.application.reservation;

import java.time.Clock;
import com.lowaltitude.inspection.application.city.CityDirectoryReadService;
import com.lowaltitude.inspection.application.city.PostgresCityDirectoryStore;
import com.lowaltitude.inspection.domain.files.ObjectStoragePort;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionServiceCatalogMapper;
import com.lowaltitude.inspection.infrastructure.persistence.PlatformCityMapper;
import com.lowaltitude.inspection.infrastructure.persistence.ReservationPersistenceMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = ReservationPersistenceMapper.class)
public class ReservationApplicationConfiguration {
    @Bean ReservationApplicationService reservationApplicationService(ReservationPersistenceMapper mapper, @Value("${INSPECTION_DATA_ENCRYPTION_BASE64_KEY}") String key) {
        var store = new PostgresReservationStore(mapper);
        return new ReservationApplicationService(store, store, new AesGcmSensitiveDataCipher(key), Clock.systemUTC());
    }

    @Bean
    InspectionServiceCatalogReadService inspectionServiceCatalogReadService(
            InspectionServiceCatalogMapper mapper
    ) {
        return new InspectionServiceCatalogReadService(
                new PostgresInspectionServiceCatalogReadStore(mapper));
    }

    @Bean
    CustomerReservationQueryService customerReservationQueryService(
            ReservationPersistenceMapper mapper,
            @Value("${INSPECTION_DATA_ENCRYPTION_BASE64_KEY}") String key
    ) {
        return new CustomerReservationQueryService(
                new PostgresCustomerReservationQueryStore(mapper),
                new AesGcmSensitiveDataCipher(key));
    }

    @Bean
    CityDirectoryReadService cityDirectoryReadService(PlatformCityMapper mapper) {
        return new CityDirectoryReadService(new PostgresCityDirectoryStore(mapper));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "inspection.infrastructure.storage",
            name = "enabled",
            havingValue = "true")
    FileObjectApplicationService fileObjectApplicationService(
            ReservationPersistenceMapper mapper,
            ObjectStoragePort storage
    ) {
        return new FileObjectApplicationService(
                new PostgresReservationStore(mapper),
                storage,
                Clock.systemUTC());
    }
}
