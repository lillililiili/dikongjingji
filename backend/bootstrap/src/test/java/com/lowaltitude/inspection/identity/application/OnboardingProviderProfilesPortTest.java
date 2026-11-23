package com.lowaltitude.inspection.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.lowaltitude.inspection.application.provider.InspectionProviderProfiles;
import org.junit.jupiter.api.Test;

/** S6-01：身份模块的“服务商资料”端口接到巡检模块（只经巡检的应用服务，不读它的表），三种状态一一对应。 */
class OnboardingProviderProfilesPortTest {

    private static final UUID ORG = UUID.fromString("c1000000-0000-4000-8000-000000000099");

    @Test
    void delegatesToTheInspectionModule() {
        InspectionProviderProfiles profiles = mock(InspectionProviderProfiles.class);
        when(profiles.status(ORG)).thenReturn(InspectionProviderProfiles.Status.DISABLED);
        when(profiles.open(ORG)).thenReturn(InspectionProviderProfiles.Status.NOT_OPENED);

        OnboardingPorts.ProviderProfiles port = new OnboardingPortsConfiguration().onboardingProviderProfiles(profiles);

        assertThat(port.status(ORG)).isEqualTo(OnboardingPorts.ProviderProfileStatus.DISABLED);
        assertThat(port.open(ORG)).isEqualTo(OnboardingPorts.ProviderProfileStatus.NOT_OPENED);
        verify(profiles).open(ORG);
    }

    @Test
    void everyInspectionStatusHasAnOnboardingStatusOfTheSameName() {
        for (InspectionProviderProfiles.Status status : InspectionProviderProfiles.Status.values()) {
            assertThat(OnboardingPortsConfiguration.convert(status).name()).isEqualTo(status.name());
        }
        assertThat(OnboardingPorts.ProviderProfileStatus.values()).hasSameSizeAs(
                InspectionProviderProfiles.Status.values());
    }
}
