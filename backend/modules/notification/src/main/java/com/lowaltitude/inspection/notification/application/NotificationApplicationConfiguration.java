package com.lowaltitude.inspection.notification.application;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowaltitude.inspection.notification.infrastructure.persistence.NotificationMapper;
import com.lowaltitude.inspection.notification.infrastructure.persistence.PushDeviceMapper;
import com.lowaltitude.inspection.notification.infrastructure.persistence.SystemNoticeMapper;
import com.lowaltitude.inspection.notification.infrastructure.push.DisabledPushGateway;
import com.lowaltitude.inspection.notification.infrastructure.push.FakePushGateway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = NotificationMapper.class)
public class NotificationApplicationConfiguration {

    /**
     * 推送服务（S5-9）：服务商未定。dev profile 并且 notification.push.provider=fake 时是本机模拟推送，
     * 其他情况一律不推送（站内信照记）。模拟推送不会出现在 dev 以外的环境。
     */
    @Bean
    PushGateway pushGateway(
            Environment environment,
            PushDeviceMapper mapper,
            @Value("${notification.push.provider:none}") String provider
    ) {
        return choosePushGateway(environment, mapper, provider);
    }

    static PushGateway choosePushGateway(Environment environment, PushDeviceMapper mapper, String provider) {
        if ("fake".equals(provider) && environment.acceptsProfiles(Profiles.of("dev"))) {
            return new FakePushGateway(mapper, Clock.systemUTC());
        }
        return new DisabledPushGateway();
    }

    @Bean
    PushDispatcher pushDispatcher(PushGateway gateway, PushDeviceMapper mapper) {
        return new PushDispatcher(gateway, new PostgresPushStore(mapper), Clock.systemUTC());
    }

    @Bean
    NotificationService notificationService(NotificationMapper mapper, PushDispatcher pushes) {
        return new NotificationService(new PostgresNotificationStore(mapper), Clock.systemUTC(),
                notification -> AfterCommit.run("notification push", () -> pushes.toRecipient(notification)));
    }

    @Bean
    NotificationPreferenceService notificationPreferenceService(PushDeviceMapper mapper) {
        return new NotificationPreferenceService(new PostgresPushStore(mapper), Clock.systemUTC());
    }

    @Bean
    PushDeviceService pushDeviceService(PushDeviceMapper mapper) {
        return new PushDeviceService(new PostgresPushStore(mapper), Clock.systemUTC());
    }

    @Bean
    SystemNoticeService systemNoticeService(SystemNoticeMapper mapper, PushDispatcher pushes) {
        return new SystemNoticeService(new PostgresSystemNoticeStore(mapper), pushes, new ObjectMapper(),
                Clock.systemUTC());
    }
}
