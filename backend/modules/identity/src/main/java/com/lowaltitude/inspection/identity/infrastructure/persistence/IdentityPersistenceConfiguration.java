package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.Clock;

import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.identity.domain.TokenVersionPort;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = IdentityPersistenceMapper.class)
public class IdentityPersistenceConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(IdentityAccountPort.class)
    IdentityAccountPort postgresIdentityAccountPort(IdentityPersistenceMapper mapper, Clock clock) {
        return new PostgresIdentityAccountAdapter(mapper, clock);
    }

    @Bean
    @ConditionalOnMissingBean(IdentityProfilePort.class)
    IdentityProfilePort postgresIdentityProfilePort(IdentityPersistenceMapper mapper) {
        return new PostgresIdentityProfileAdapter(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(IdentityOrganizationPort.class)
    IdentityOrganizationPort postgresIdentityOrganizationPort(IdentityPersistenceMapper mapper) {
        return new PostgresIdentityOrganizationAdapter(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(TokenVersionPort.class)
    TokenVersionPort postgresTokenVersionPort(IdentityPersistenceMapper mapper) {
        return new PostgresTokenVersionAdapter(mapper);
    }
}
