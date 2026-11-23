package com.lowaltitude.inspection.activity.application;

import java.time.Clock;

import com.lowaltitude.inspection.activity.application.ops.ActivityOpsPorts;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsService;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCatalogMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityChangeMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityResultMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOpsMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = ActivityCatalogMapper.class)
public class ActivityApplicationConfiguration {

    /** price.onlinePayment 要问主办机构能不能在线收款：经付款端口问交易模块（E09），没接时一律不能。 */
    @Bean
    ActivityCatalogReadService activityCatalogReadService(
            ActivityCatalogMapper mapper,
            ObjectProvider<ActivityPaymentPort> payments
    ) {
        ActivityPaymentPort port = payments.getIfAvailable(ActivityPaymentPort::unavailable);
        return new ActivityCatalogReadService(
                new PostgresActivityCatalogReadStore(mapper),
                Clock.systemUTC(),
                port::merchantCanCollect);
    }

    /**
     * 收费报名的付款能力由启动模块接到交易模块（E09）；没接时收费报名一律“暂未开通在线支付”。
     * 报名成功、候补转正、改期、结果记录的通知由启动模块接到站内信（R6-5、S5-3）；没接时不通知。
     */
    @Bean
    ActivityEnrollmentService activityEnrollmentService(
            ActivityEnrollmentMapper mapper,
            ActivityChangeMapper changes,
            ActivityWaitlistQueue waitlistQueue,
            ObjectProvider<ActivityPaymentPort> payments,
            PlatformTransactionManager transactionManager,
            ObjectProvider<ActivityEnrollmentNotifier> notifier
    ) {
        return new ActivityEnrollmentService(
                mapper,
                Clock.systemUTC(),
                new ActivityTicketCodes(),
                payments.getIfAvailable(ActivityPaymentPort::unavailable),
                new TransactionTemplate(transactionManager),
                notifier.getIfAvailable(ActivityEnrollmentNotifier::none),
                changes,
                waitlistQueue);
    }

    /** 名额放回来时给候补转正（S5-3）。 */
    @Bean
    ActivityWaitlistQueue activityWaitlistQueue(
            ActivityWaitlistMapper mapper,
            ObjectProvider<ActivityEnrollmentNotifier> notifier
    ) {
        return new ActivityWaitlistQueue(mapper, notifier.getIfAvailable(ActivityEnrollmentNotifier::none));
    }

    /** 候补（S5-3）。 */
    @Bean
    ActivityWaitlistService activityWaitlistService(
            ActivityEnrollmentMapper enrollments,
            ActivityWaitlistMapper mapper,
            ActivityEnrollmentService enrollmentService,
            ActivityWaitlistQueue waitlistQueue,
            ObjectProvider<ActivityPaymentPort> payments,
            PlatformTransactionManager transactionManager
    ) {
        return new ActivityWaitlistService(
                enrollments,
                mapper,
                enrollmentService,
                waitlistQueue,
                payments.getIfAvailable(ActivityPaymentPort::unavailable),
                new TransactionTemplate(transactionManager),
                Clock.systemUTC());
    }

    /** 机构工作台的“活动结果”（S5-3）。 */
    @Bean
    ActivityResultService activityResultService(
            ActivityResultMapper mapper,
            ActivityEnrollmentMapper enrollments,
            ObjectProvider<ActivityEnrollmentNotifier> notifier
    ) {
        return new ActivityResultService(
                mapper, enrollments, notifier.getIfAvailable(ActivityEnrollmentNotifier::none), Clock.systemUTC());
    }

    /** 核销工作台（S3-3）里活动凭证的一半；和场馆的一半由启动模块的工作台接口合在一起。 */
    @Bean
    ActivityCheckinDesk activityCheckinDesk(ActivityCheckinMapper mapper) {
        return new ActivityCheckinDesk(mapper, Clock.systemUTC());
    }

    /** 运营端“活动与场次”（S3-1）：机构和城市两个端口由启动模块接（ActivityOpsPortsConfiguration）。 */
    @Bean
    ActivityOpsService activityOpsService(
            ActivityOpsMapper mapper,
            ActivityOpsPorts.Organizations organizations,
            ActivityOpsPorts.Cities cities
    ) {
        return new ActivityOpsService(mapper, organizations, cities);
    }
}
