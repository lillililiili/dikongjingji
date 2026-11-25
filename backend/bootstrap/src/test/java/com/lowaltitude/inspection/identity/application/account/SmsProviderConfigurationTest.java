package com.lowaltitude.inspection.identity.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SmsProviderConfigurationTest {
    @Test
    void fakeDeliveryRequiresBothDevProfileAndExplicitFakeProvider() {
        var configuration = new IdentityAccountPortsConfiguration();
        var mapper = mock(AccountMapper.class);
        var clock = Clock.systemUTC();
        var environment = new MockEnvironment();
        for (String profile : new String[]{"default", "prod", "staging"}) {
            environment.setActiveProfiles(profile);
            var sms = configuration.accountSmsGateway("fake", mapper, clock, environment);
            assertThatThrownBy(() -> sms.send("13800000000", "test"))
                    .isInstanceOf(AccountPorts.SmsUnavailableException.class);
        }
        verifyNoInteractions(mapper);
        environment.setActiveProfiles("dev");
        assertThat(configuration.accountSmsGateway("none", mapper, clock, environment))
                .isInstanceOf(FakeSmsGateway.Unavailable.class);
        assertThat(configuration.accountSmsGateway("fake", mapper, clock, environment)).isInstanceOf(FakeSmsGateway.class);
    }
}
