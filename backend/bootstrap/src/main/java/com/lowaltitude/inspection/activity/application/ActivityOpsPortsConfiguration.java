package com.lowaltitude.inspection.activity.application;

import com.lowaltitude.inspection.activity.application.ops.ActivityOpsPorts;
import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运营端“活动与场次”（S3-1）的两个端口：主办 / 承办机构接身份模块（在用的机构和类型），城市接巡检模块的平台城市目录。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ActivityOpsPortsConfiguration {

    @Bean
    ActivityOpsPorts.Organizations activityOpsOrganizations(IdentityOrganizationPort organizations) {
        return organizationId -> organizations.findActiveOrganization(organizationId)
                .map(found -> new ActivityOpsPorts.Organization(found.organizationId(), found.name(),
                        found.organizationType(), found.listingSuspended()));
    }

    @Bean
    ActivityOpsPorts.Cities activityOpsCities(CityOperationsService cities) {
        return cities::cityName;
    }
}
