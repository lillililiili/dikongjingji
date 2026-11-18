package com.lowaltitude.inspection.activity.application;

import java.time.Clock;

import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCatalogMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = ActivityCatalogMapper.class)
public class ActivityApplicationConfiguration {

    @Bean
    ActivityCatalogReadService activityCatalogReadService(ActivityCatalogMapper mapper) {
        return new ActivityCatalogReadService(
                new PostgresActivityCatalogReadStore(mapper),
                Clock.systemUTC());
    }

    @Bean
    ActivityEnrollmentService activityEnrollmentService(ActivityEnrollmentMapper mapper) {
        return new ActivityEnrollmentService(mapper, Clock.systemUTC(), new ActivityTicketCodes());
    }
}
