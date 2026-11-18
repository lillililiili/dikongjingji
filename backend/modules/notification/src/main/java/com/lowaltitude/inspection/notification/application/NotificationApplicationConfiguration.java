package com.lowaltitude.inspection.notification.application;

import java.time.Clock;

import com.lowaltitude.inspection.notification.infrastructure.persistence.NotificationMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = NotificationMapper.class)
public class NotificationApplicationConfiguration {

    @Bean
    NotificationService notificationService(NotificationMapper mapper) {
        return new NotificationService(new PostgresNotificationStore(mapper), Clock.systemUTC());
    }
}
