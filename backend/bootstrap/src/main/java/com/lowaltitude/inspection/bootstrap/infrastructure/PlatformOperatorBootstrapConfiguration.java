package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.nio.file.Path;

import com.lowaltitude.inspection.identity.application.PlatformOperatorBootstrapService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 首个平台运营账号的一次性引导（S6）：platform.bootstrap.operator.*（环境变量 PLATFORM_BOOTSTRAP_OPERATOR_*），
 * 登录名为空时 {@link PlatformOperatorBootstrapRunner} 什么都不做。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PlatformOperatorBootstrapConfiguration {

    @Bean
    PlatformOperatorBootstrapRunner platformOperatorBootstrapRunner(
            PlatformOperatorBootstrapService service,
            @Value("${platform.bootstrap.operator.login:}") String login,
            @Value("${platform.bootstrap.operator.password:}") String password,
            @Value("${platform.bootstrap.operator.password-file:}") String passwordFile,
            @Value("${platform.bootstrap.operator.display-name:}") String displayName
    ) {
        return new PlatformOperatorBootstrapRunner(service, new PlatformOperatorBootstrapRunner.Settings(
                login, password, passwordFile == null || passwordFile.isBlank() ? null : Path.of(passwordFile.strip()),
                displayName));
    }
}
