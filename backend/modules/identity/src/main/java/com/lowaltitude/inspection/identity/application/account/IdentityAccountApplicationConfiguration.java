package com.lowaltitude.inspection.identity.application.account;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;

import com.lowaltitude.inspection.identity.domain.AccountStatePort;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手机号登录、登录设备、注销、个人信息导出和企业组织（S5-7、S5-8）。短信、导出存储、城市名称、JSON、别的模块的数据源
 * 和注销条件由启动模块接上（AccountPorts）；没有数据库时整组不装配，登录退回 FailClosedAccountStatePort。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class IdentityAccountApplicationConfiguration {

    @Bean
    SmsCodeService smsCodeService(AccountMapper mapper, AccountPorts.SmsGateway gateway, Clock identityClock) {
        return new SmsCodeService(mapper, gateway, identityClock, new SecureRandom());
    }

    @Bean
    LoginSessionService loginSessionService(AccountMapper mapper, AccountPorts.CityNames cities,
                                            com.lowaltitude.inspection.identity.domain.RefreshTokenPort refreshTokens,
                                            Clock identityClock) {
        return new LoginSessionService(mapper, cities, refreshTokens, identityClock);
    }

    @Bean
    PhoneAccountService phoneAccountService(AccountMapper mapper, SmsCodeService codes, LoginSessionService sessions,
                                            Clock identityClock) {
        return new PhoneAccountService(mapper, codes, sessions, identityClock, new SecureRandom());
    }

    @Bean
    EnterpriseService enterpriseService(AccountMapper mapper, AccountPorts.CityNames cities, Clock identityClock) {
        return new EnterpriseService(mapper, cities, identityClock, new SecureRandom());
    }

    @Bean
    AccountClosureService accountClosureService(
            AccountMapper mapper,
            ObjectProvider<AccountPorts.ClosureCondition> conditions,
            ObjectProvider<AccountPorts.PersonalDataEraser> erasers,
            EnterpriseService enterprises,
            LoginSessionService sessions,
            Clock identityClock
    ) {
        return new AccountClosureService(mapper, conditions.orderedStream().toList(),
                erasers.orderedStream().toList(), enterprises, sessions, identityClock);
    }

    @Bean
    PersonalDataExportService personalDataExportService(
            AccountMapper mapper,
            SmsCodeService codes,
            EnterpriseService enterprises,
            ObjectProvider<AccountPorts.PersonalDataSource> sources,
            AccountPorts.ExportFiles files,
            AccountPorts.JsonWriter json,
            Clock identityClock
    ) {
        List<AccountPorts.PersonalDataSource> ordered = sources.orderedStream().toList();
        return new PersonalDataExportService(mapper, codes, enterprises, ordered, files, json, identityClock,
                new SecureRandom());
    }

    /** 运营端用户查询（只读，打开详情留查看记录）。 */
    @Bean
    OpsUserLookupService opsUserLookupService(
            com.lowaltitude.inspection.identity.infrastructure.persistence.OpsUserLookupMapper mapper,
            Clock identityClock
    ) {
        return new OpsUserLookupService(mapper, identityClock);
    }

    @Bean
    AccountStatePort identityAccountStatePort(LoginSessionService sessions, EnterpriseService enterprises,
                                              AccountMapper mapper) {
        return new IdentityAccountState(sessions, enterprises, mapper);
    }
}
