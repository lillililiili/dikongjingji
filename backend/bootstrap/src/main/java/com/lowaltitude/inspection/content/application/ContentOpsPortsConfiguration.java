package com.lowaltitude.inspection.content.application;

import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.content.application.ops.ContentOpsPorts;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.mall.application.MallCatalogReadService;
import com.lowaltitude.inspection.mall.application.MallProductNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运营端“发现内容”（S3-1）的三个端口：作者、发布方、发布商家接身份模块（在用的机构和类型），城市接巡检模块的平台城市目录，
 * 专题商家内容的商品接商城的公开读取（这个城市有在售商家才算有）。内容模块不读它们的表。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ContentOpsPortsConfiguration {

    @Bean
    ContentOpsPorts.Organizations contentOpsOrganizations(IdentityOrganizationPort organizations) {
        return organizationId -> organizations.findActiveOrganization(organizationId)
                .map(found -> new ContentOpsPorts.Organization(found.organizationId(), found.name(),
                        found.organizationType()));
    }

    @Bean
    ContentOpsPorts.Cities contentOpsCities(CityOperationsService cities) {
        return cities::cityName;
    }

    @Bean
    ContentOpsPorts.Products contentOpsProducts(MallCatalogReadService catalog) {
        return (productId, cityCode) -> {
            try {
                catalog.findProduct(productId, cityCode);
                return true;
            } catch (MallProductNotFoundException notSold) {
                return false;
            }
        };
    }
}
