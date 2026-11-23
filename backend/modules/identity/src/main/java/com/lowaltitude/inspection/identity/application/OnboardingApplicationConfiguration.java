package com.lowaltitude.inspection.identity.application;

import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 运营端“机构入驻与资质审核”（S3-1）。城市目录、口令摘要、附件存储由启动模块接上（OnboardingPorts）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class OnboardingApplicationConfiguration {

    @Bean
    OrganizationOnboardingService organizationOnboardingService(
            OrganizationOnboardingMapper mapper,
            OnboardingPorts.Cities cities,
            OnboardingPorts.PasswordHasher hasher,
            OnboardingPorts.QualificationFiles files
    ) {
        return new OrganizationOnboardingService(mapper, cities, hasher, files);
    }
}
