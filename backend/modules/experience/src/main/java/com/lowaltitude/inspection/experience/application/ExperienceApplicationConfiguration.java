package com.lowaltitude.inspection.experience.application;

import java.time.Clock;

import com.lowaltitude.inspection.experience.infrastructure.persistence.ExperienceCatalogMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = ExperienceCatalogMapper.class)
public class ExperienceApplicationConfiguration {

    @Bean
    ExperienceCatalogReadService experienceCatalogReadService(ExperienceCatalogMapper mapper) {
        return new ExperienceCatalogReadService(
                new PostgresExperienceCatalogReadStore(mapper),
                Clock.systemUTC());
    }
}
