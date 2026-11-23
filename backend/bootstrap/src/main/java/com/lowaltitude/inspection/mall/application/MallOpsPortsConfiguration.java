package com.lowaltitude.inspection.mall.application;

import java.nio.file.Path;

import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.bootstrap.infrastructure.LocalMallMediaStore;
import com.lowaltitude.inspection.bootstrap.infrastructure.S3ModuleFileStores;
import com.lowaltitude.inspection.bootstrap.infrastructure.storage.PlatformStorage;
import org.springframework.beans.factory.ObjectProvider;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运营端“商品与供给”（S3-1）的端口：城市接巡检模块的平台城市目录，商家接身份模块的机构端口（在用的经营机构），
 * 商品图片接对象存储。图片存储：platform.storage.provider=s3 时存对象存储公开桶（S6）；否则配置了 mall.media.local-dir
 * （只在 application-dev.yml 里有）时用本机目录；都没有时上传一律不可用（503）。
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
            @Value("${mall.media.public-base-url:}") String publicBaseUrl,
            ObjectProvider<PlatformStorage> platformStorage
    ) {
        PlatformStorage storage = platformStorage.getIfAvailable(PlatformStorage::local);
        if (storage.isS3()) {
            return new S3ModuleFileStores.MallMedia(storage);
        }
        if (localDirectory == null || localDirectory.isBlank() || publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return new LocalMallMediaStore.Unavailable();
        }
        return new LocalMallMediaStore(Path.of(localDirectory), publicBaseUrl);
    }
}
