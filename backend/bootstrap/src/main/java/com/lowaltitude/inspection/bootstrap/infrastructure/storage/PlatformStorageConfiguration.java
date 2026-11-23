package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 选平台文件存储（S6）。platform.storage.provider 是 s3 时先跑 {@link PlatformStoragePreflight}：有一项不通过就不启动，
 * 一次列出缺什么（不打印密钥）；是 local 时什么都不连，各模块照旧用开发配置的本机目录或回 503。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlatformStorageProperties.class)
public class PlatformStorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformStorageConfiguration.class);

    @Bean
    PlatformStorage platformStorage(PlatformStorageProperties properties) {
        for (PlatformStoragePreflight.Item warning : PlatformStoragePreflight.requireStartable(properties)) {
            log.warn("platform storage preflight {}: {}", warning.code(), warning.message());
        }
        if (!properties.s3Selected()) {
            log.info("platform storage provider is local; files are only available where a local directory is configured");
            return PlatformStorage.local();
        }
        PlatformStorageProperties.S3 s3 = properties.s3();
        S3ObjectClient client = new S3ObjectClient(
                new S3ObjectClient.Settings(s3.endpoint(), s3.region(),
                        new S3SigV4Signer.Credentials(s3.accessKey().trim(), s3.secretKey().trim()),
                        s3.pathStyle(), s3.requestTimeout()),
                S3ObjectClient.httpClient(s3.connectTimeout()),
                Clock.systemUTC());
        log.info("platform storage provider is s3; private bucket {}, public bucket {}", s3.privateBucket(),
                s3.publicBucket());
        return PlatformStorage.s3(client, s3.privateBucket(), s3.publicBucket(), s3.publicBaseUrl().toString());
    }
}
