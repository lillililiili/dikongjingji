package com.lowaltitude.inspection.identity.application;

import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationImportMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.OrganizationOnboardingMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.PlatformOperatorBootstrapMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 运营端“机构入驻与资质审核”（S3-1）。城市目录、口令摘要、附件存储、服务商资料由启动模块接上（OnboardingPorts）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class OnboardingApplicationConfiguration {

    @Bean
    OrganizationOnboardingService organizationOnboardingService(
            OrganizationOnboardingMapper mapper,
            OnboardingPorts.Cities cities,
            OnboardingPorts.PasswordHasher hasher,
            OnboardingPorts.QualificationFiles files,
            OnboardingPorts.ProviderProfiles providerProfiles
    ) {
        return new OrganizationOnboardingService(mapper, cities, hasher, files, providerProfiles);
    }

    /** 商家自助入驻（契约 openapi-organization-signup.yaml）：建机构、资质和第一个账号沿用机构入驻，验证码沿用账号的短信服务。 */
    @Bean
    OrganizationSignupService organizationSignupService(
            OrganizationOnboardingMapper mapper,
            OrganizationOnboardingService onboarding,
            OnboardingPorts.Cities cities,
            OnboardingPorts.PasswordHasher hasher,
            com.lowaltitude.inspection.identity.application.account.SmsCodeService smsCodes
    ) {
        return new OrganizationSignupService(mapper, onboarding, cities, hasher, smsCodes, java.time.Clock.systemUTC());
    }

    /** 批量导入经营主体和操作员账号（S2-3）：口令生成和摘要沿用机构入驻。 */
    @Bean
    OrganizationImportService organizationImportService(
            OrganizationImportMapper imports,
            OrganizationOnboardingMapper mapper,
            OrganizationOnboardingService onboarding,
            OnboardingPorts.PasswordHasher hasher
    ) {
        return new OrganizationImportService(imports, mapper, onboarding, hasher, java.time.Clock.systemUTC());
    }

    /** 首个平台运营账号的一次性引导（S6）：由启动模块在设了登录名时调用；建号和授角色沿用机构入驻的写法。 */
    @Bean
    PlatformOperatorBootstrapService platformOperatorBootstrapService(
            OrganizationOnboardingMapper mapper,
            PlatformOperatorBootstrapMapper bootstrap,
            OnboardingPorts.PasswordHasher hasher
    ) {
        return new PlatformOperatorBootstrapService(mapper, bootstrap, hasher, java.time.Clock.systemUTC());
    }
}
