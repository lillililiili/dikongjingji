package com.lowaltitude.inspection.mall.application;

import java.nio.file.Path;

import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.bootstrap.infrastructure.LocalMallMediaStore;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运营端“商品与供给”（S3-1）的端口：城市接巡检模块的平台城市目录，商家接身份模块的机构端口（在用的经营机构），
 * 商品图片接对象存储。图片存储：配置了 mall.media.local-dir（只在 application-dev.yml 里有）时用本机目录，
 * 否则上传一律不可用（503），等接对象存储的公共读桶或 CDN。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class MallOpsPortsConfiguration {

    @Bean
    MallOpsPorts.Cities mallOpsCities(CityOperationsService cities) {
        return cities::cityName;
    }

    @Bean
    MallOpsPorts.Merchants mallOpsMerchants(IdentityOrganizationPort organizations) {
        return organizationId -> organizations.findActiveOrganization(organizationId)
                .filter(organization -> "PROVIDER".equals(organization.organizationType()))
                .map(organization -> organization.name());
    }

    @Bean
    MallOpsPorts.MediaStore mallOpsMediaStore(
            @Value("${mall.media.local-dir:}") String localDirectory,
            @Value("${mall.media.public-base-url:}") String publicBaseUrl
    ) {
        if (localDirectory == null || localDirectory.isBlank() || publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return new LocalMallMediaStore.Unavailable();
        }
        return new LocalMallMediaStore(Path.of(localDirectory), publicBaseUrl);
    }
}
