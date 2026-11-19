package com.lowaltitude.inspection.content.application;

import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 发现页图文内容和专题（H1）。只读本模块的表，不接别的模块。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = ContentMapper.class)
public class ContentApplicationConfiguration {

    @Bean
    ContentReadService contentReadService(ContentMapper mapper) {
        return new ContentReadService(mapper);
    }
}
