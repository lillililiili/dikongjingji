package com.lowaltitude.inspection.activity.application;

import java.time.Clock;

import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCatalogMapper;
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

    /** 收费报名的付款能力由启动模块接到交易模块（E09）；没接时收费报名一律“暂未开通在线支付”。 */
    @Bean
    ActivityEnrollmentService activityEnrollmentService(
            ActivityEnrollmentMapper mapper,
            ObjectProvider<ActivityPaymentPort> payments,
            PlatformTransactionManager transactionManager
    ) {
        return new ActivityEnrollmentService(
                mapper,
                Clock.systemUTC(),
                new ActivityTicketCodes(),
                payments.getIfAvailable(ActivityPaymentPort::unavailable),
                new TransactionTemplate(transactionManager));
    }
}
