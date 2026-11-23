package com.lowaltitude.inspection.bootstrap.security;

import com.lowaltitude.inspection.identity.domain.AccountStatePort;
import com.lowaltitude.inspection.identity.domain.IdentityAccountPort;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.identity.domain.RefreshTokenPort;
import com.lowaltitude.inspection.identity.domain.TokenVersionPort;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedAccountStatePort;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedIdentityAccountPort;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedIdentityOrganizationPort;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedIdentityProfilePort;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedRefreshTokenPort;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedTokenVersionPort;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Supplies fail-closed ports only when a persistence adapter has not been registered.
 * As an auto-configuration it is evaluated after application and test bean definitions.
 */
@AutoConfiguration
public class IdentityFallbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdentityAccountPort.class)
    IdentityAccountPort failClosedIdentityAccountPort() {
        return new FailClosedIdentityAccountPort();
    }

    @Bean
    @ConditionalOnMissingBean(IdentityProfilePort.class)
    IdentityProfilePort failClosedIdentityProfilePort() {
        return new FailClosedIdentityProfilePort();
    }

    @Bean
    @ConditionalOnMissingBean(IdentityOrganizationPort.class)
    IdentityOrganizationPort failClosedIdentityOrganizationPort() {
        return new FailClosedIdentityOrganizationPort();
    }

    @Bean
    @ConditionalOnMissingBean(TokenVersionPort.class)
    TokenVersionPort failClosedTokenVersionPort() {
        return new FailClosedTokenVersionPort();
    }

    @Bean
    @ConditionalOnMissingBean(RefreshTokenPort.class)
    RefreshTokenPort failClosedRefreshTokenPort() {
        return new FailClosedRefreshTokenPort();
    }

    @Bean
    @ConditionalOnMissingBean(AccountStatePort.class)
    AccountStatePort failClosedAccountStatePort() {
        return new FailClosedAccountStatePort();
    }
}
