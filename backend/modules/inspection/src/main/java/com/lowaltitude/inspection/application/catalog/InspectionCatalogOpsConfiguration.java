package com.lowaltitude.inspection.application.catalog;

import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 运营端“巡检服务目录”（S3-1）。服务商经身份模块的机构端口校验，不读身份的表来做判断。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class InspectionCatalogOpsConfiguration {

    @Bean
    InspectionCatalogOpsService inspectionCatalogOpsService(InspectionCatalogOpsMapper mapper,
                                                            IdentityOrganizationPort organizations) {
        return new InspectionCatalogOpsService(mapper, organizations);
    }
}
