package com.lowaltitude.inspection.experience.application;

import java.time.Clock;
import java.util.List;

import com.lowaltitude.inspection.experience.application.ops.VenueOpsPorts;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsService;
import com.lowaltitude.inspection.experience.infrastructure.persistence.ExperienceCatalogMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.ExperienceRecordMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueOpsMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper;
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

    /**
     * 项目的 onlinePayment 要问场馆运营机构能不能在线收款：经付款端口问交易模块（E10），没接时一律不能。
     * 馆内设备的商品价格和在售家数经设备商品端口问商城（S4-4），没接时设备不带商品。
     */
    @Bean
    ExperienceCatalogReadService experienceCatalogReadService(
            ExperienceCatalogMapper mapper,
            ObjectProvider<VenuePaymentPort> payments,
            ObjectProvider<VenueDeviceOfferPort> deviceOffers
    ) {
        VenuePaymentPort port = payments.getIfAvailable(VenuePaymentPort::unavailable);
        return new ExperienceCatalogReadService(
                new PostgresExperienceCatalogReadStore(mapper),
                Clock.systemUTC(),
                port::merchantCanCollect,
                deviceOffers.getIfAvailable(VenueDeviceOfferPort::none));
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

    /** S5-4 线上体验记录：仿真成绩、软件试用和课程试听。只记登录账号自己的。 */
    @Bean
    ExperienceRecordService experienceRecordService(
            ExperienceRecordMapper mapper,
            PlatformTransactionManager transactionManager
    ) {
        return new ExperienceRecordService(mapper, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    /** 核销工作台（S3-3）里到馆预约的一半；和活动的一半由启动模块的工作台接口合在一起。 */
    @Bean
    VenueCheckinDesk venueCheckinDesk(VenueCheckinMapper mapper) {
        return new VenueCheckinDesk(mapper, Clock.systemUTC());
    }

    /**
     * 运营端“场馆与设备”（S3-1）。城市目录、运营机构、商品三个端口由启动模块接（VenueOpsPortsConfiguration）；
     * 城市和机构没接时一律当作不存在（建不了场馆），商品没接时只校验 ID 格式。
     */
    @Bean
    VenueOpsService venueOpsService(
            VenueOpsMapper mapper,
            ObjectProvider<VenueOpsPorts.Cities> cities,
            ObjectProvider<VenueOpsPorts.Operators> operators,
            ObjectProvider<VenueOpsPorts.Products> products
    ) {
        return new VenueOpsService(
                mapper,
                cities.getIfAvailable(() -> cityCode -> java.util.Optional.empty()),
                operators.getIfAvailable(() -> organizationId -> java.util.Optional.empty()),
                products.getIfAvailable(VenueOpsPorts.Products::unchecked));
    }

    /** 运营端首页“经营概览”的汇总数（只读）。 */
    @Bean
    ExperienceOpsStatsService experienceOpsStatsService(com.lowaltitude.inspection.experience.infrastructure.persistence.ExperienceOpsStatsMapper mapper) {
        return new ExperienceOpsStatsService(mapper);
    }
}
