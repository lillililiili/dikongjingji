package com.lowaltitude.inspection.identity.application;

import java.nio.file.Path;

import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.application.provider.InspectionProviderProfiles;
import com.lowaltitude.inspection.bootstrap.infrastructure.LocalQualificationFileStore;
import com.lowaltitude.inspection.bootstrap.infrastructure.S3ModuleFileStores;
import com.lowaltitude.inspection.bootstrap.infrastructure.storage.PlatformStorage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 机构入驻（S3-1）的端口接到巡检模块的平台城市目录、安全配置的 BCrypt、资质附件存储和巡检模块的服务商资料（S6-01）。
 * 附件存储：platform.storage.provider=s3 时存对象存储私有桶（S6）；否则配置了 identity.qualification-files.local-dir
 * （只在 application-dev.yml 里有）时用本机目录；都没有时一律不可用（上传、下载返回 503）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class OnboardingPortsConfiguration {

    @Bean
    OnboardingPorts.Cities onboardingCities(CityOperationsService cities) {
        return cities::cityName;
    }

    @Bean
    OnboardingPorts.PasswordHasher onboardingPasswordHasher(PasswordEncoder encoder) {
        return encoder::encode;
    }

    @Bean
    OnboardingPorts.QualificationFiles onboardingQualificationFiles(
            @Value("${identity.qualification-files.local-dir:}") String localDirectory,
            ObjectProvider<PlatformStorage> platformStorage
    ) {
        PlatformStorage storage = platformStorage.getIfAvailable(PlatformStorage::local);
        if (storage.isS3()) {
            return new S3ModuleFileStores.QualificationFiles(storage);
        }
        return localDirectory == null || localDirectory.isBlank()
                ? new LocalQualificationFileStore.Unavailable()
                : new LocalQualificationFileStore(Path.of(localDirectory));
    }

    /** 开通服务商资料（S6-01）：身份模块经这个端口让巡检模块建服务商资料，不读巡检的表。 */
    @Bean
    OnboardingPorts.ProviderProfiles onboardingProviderProfiles(InspectionProviderProfiles profiles) {
        return new OnboardingPorts.ProviderProfiles() {
            @Override
            public OnboardingPorts.ProviderProfileStatus status(java.util.UUID organizationId) {
                return convert(profiles.status(organizationId));
            }

            @Override
            public OnboardingPorts.ProviderProfileStatus open(java.util.UUID organizationId) {
                return convert(profiles.open(organizationId));
            }
        };
    }

    static OnboardingPorts.ProviderProfileStatus convert(InspectionProviderProfiles.Status status) {
        return switch (status) {
            case NOT_OPENED -> OnboardingPorts.ProviderProfileStatus.NOT_OPENED;
            case ACTIVE -> OnboardingPorts.ProviderProfileStatus.ACTIVE;
            case DISABLED -> OnboardingPorts.ProviderProfileStatus.DISABLED;
        };
    }
}
