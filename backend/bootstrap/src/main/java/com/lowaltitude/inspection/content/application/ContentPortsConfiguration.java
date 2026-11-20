package com.lowaltitude.inspection.content.application;

import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 视频评论（1.2.0）的显示名接到身份模块。举报记进客服反馈的那个端口在客服模块的包里接
 * （support.application.SupportContentReportsConfiguration），因为客服的反馈结果类型只在它自己的包里可见。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ContentPortsConfiguration {

    @Bean
    ContentPorts.Profiles contentProfiles(IdentityProfilePort profiles) {
        return accountId -> profiles.findProfile(accountId).map(IdentityProfile::displayName);
    }
}
