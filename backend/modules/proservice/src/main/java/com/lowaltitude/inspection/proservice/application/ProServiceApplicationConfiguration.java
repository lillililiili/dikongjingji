package com.lowaltitude.inspection.proservice.application;

import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceMapper;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProviderProfileMapper;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 四类专业服务（F2）。城市目录、服务商资料、巡检服务由启动模块接上（ProServicePorts）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = ProServiceMapper.class)
public class ProServiceApplicationConfiguration {

    @Bean
    ProServiceReadService proServiceReadService(
            ProServiceMapper mapper,
            ProServicePorts.CityDirectory cities,
            ProServicePorts.ProviderProfiles profiles,
            ProServicePorts.InspectionOffers inspection,
            ProviderProfileService providerProfiles
    ) {
        return new ProServiceReadService(mapper, cities, profiles, inspection, providerProfiles::published);
    }

    /** 服务商主页资料（S4-4）：服务商在工作台提交、平台运营审核。 */
    @Bean
    ProviderProfileService providerProfileService(
            ProviderProfileMapper mapper,
            ProServicePorts.ProviderProfiles profiles
    ) {
        return new ProviderProfileService(mapper, profiles);
    }

    /** 运营端“专业服务目录”（S3-1）：服务商经身份模块的机构端口校验，城市经平台城市目录端口校验。 */
    @Bean
    ProServiceOpsService proServiceOpsService(
            ProServiceOpsMapper mapper,
            IdentityOrganizationPort organizations,
            ProServicePorts.CityDirectory cities
    ) {
        return new ProServiceOpsService(mapper, organizations, cities);
    }

    /** 运营端首页“经营概览”的汇总数（只读）。 */
    @Bean
    ProServiceOpsStatsService proServiceOpsStatsService(com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsStatsMapper mapper) {
        return new ProServiceOpsStatsService(mapper);
    }
}
