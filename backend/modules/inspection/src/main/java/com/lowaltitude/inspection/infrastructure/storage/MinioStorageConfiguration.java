package com.lowaltitude.inspection.infrastructure.storage;

import java.time.Clock;

import com.lowaltitude.inspection.domain.files.ObjectStoragePort;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "inspection.infrastructure.storage",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(MinioStorageProperties.class)
public class MinioStorageConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MinioClient minioClient(MinioStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint().toString())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    MinioGateway minioGateway(MinioClient client) {
        return new SdkMinioGateway(client);
    }

    @Bean
    @ConditionalOnMissingBean(ObjectStoragePort.class)
    ObjectStoragePort minioObjectStoragePort(
            MinioGateway gateway,
            MinioStorageProperties properties
    ) {
        return new MinioObjectStorageAdapter(
                gateway,
                properties,
                new InspectionObjectKeyFactory(),
                Clock.systemUTC());
    }
}
