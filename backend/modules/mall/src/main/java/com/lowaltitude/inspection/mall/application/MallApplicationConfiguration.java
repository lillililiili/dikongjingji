package com.lowaltitude.inspection.mall.application;

import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = MallCatalogMapper.class)
public class MallApplicationConfiguration {

    @Bean
    MallCatalogReadService mallCatalogReadService(MallCatalogMapper mapper) {
        return new MallCatalogReadService(new PostgresMallCatalogReadStore(mapper));
    }
}
