package com.lowaltitude.inspection.ops.media;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运营端素材存储：配置了 ops.media.local-dir（只在 application-dev.yml 里有）时用本机目录模拟对象存储，
 * 地址前缀是 ops.media.public-base-url（必须是 https）；否则一律不可用（上传、预览返回 503），等接对象存储。
 */
@Configuration(proxyBeanMethods = false)
public class OpsMediaConfiguration {

    @Bean
    OpsMediaStore opsMediaStore(
            @Value("${ops.media.local-dir:}") String localDirectory,
            @Value("${ops.media.public-base-url:}") String publicBaseUrl
    ) {
        return localDirectory == null || localDirectory.isBlank()
                ? new OpsMediaStore.Unavailable()
                : new LocalOpsMediaStore(Path.of(localDirectory), publicBaseUrl);
    }

    @Bean
    OpsMediaService opsMediaService(OpsMediaStore store) {
        return new OpsMediaService(store);
    }
}
