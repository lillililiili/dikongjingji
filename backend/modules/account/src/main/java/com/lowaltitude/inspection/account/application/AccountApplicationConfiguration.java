package com.lowaltitude.inspection.account.application;

import java.time.Clock;

import com.lowaltitude.inspection.account.infrastructure.persistence.AccountAddressMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = AccountAddressMapper.class)
public class AccountApplicationConfiguration {

    @Bean
    AccountAddressService accountAddressService(AccountAddressMapper mapper, RegionDirectory regions) {
        return new AccountAddressService(mapper, regions, Clock.systemUTC());
    }
}
