package com.lowaltitude.inspection.experience.application;

import java.time.Clock;
import java.util.List;

import com.lowaltitude.inspection.experience.infrastructure.persistence.ExperienceCatalogMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = ExperienceCatalogMapper.class)
public class ExperienceApplicationConfiguration {

    /** 项目的 onlinePayment 要问场馆运营机构能不能在线收款：经付款端口问交易模块（E10），没接时一律不能。 */
    @Bean
    ExperienceCatalogReadService experienceCatalogReadService(
            ExperienceCatalogMapper mapper,
            ObjectProvider<VenuePaymentPort> payments
    ) {
        VenuePaymentPort port = payments.getIfAvailable(VenuePaymentPort::unavailable);
        return new ExperienceCatalogReadService(
                new PostgresExperienceCatalogReadStore(mapper),
                Clock.systemUTC(),
                port::merchantCanCollect);
    }

    /**
     * 到馆预约（E10）。付款端口由启动模块接到交易模块，参与人端口接到活动模块的参与人名单；
     * 都没接时只能约免费项目，并且没有参与人可选。预约成功、改期的通知由启动模块接到站内信（R6-5）。
     */
    @Bean
    VenueBookingService venueBookingService(
            VenueBookingMapper mapper,
            ObjectProvider<VenueParticipantPort> participants,
            ObjectProvider<VenuePaymentPort> payments,
            PlatformTransactionManager transactionManager,
            ObjectProvider<VenueBookingNotifier> notifier
    ) {
        return new VenueBookingService(
                mapper,
                participants.getIfAvailable(() -> (accountId, accountName) -> List.of()),
                payments.getIfAvailable(VenuePaymentPort::unavailable),
                new TransactionTemplate(transactionManager),
                Clock.systemUTC(),
                new VenueBookingCodes(),
                notifier.getIfAvailable(VenueBookingNotifier::none));
    }
}
