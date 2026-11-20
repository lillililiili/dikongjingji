package com.lowaltitude.inspection.proservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.application.city.CityDirectoryReadService;
import com.lowaltitude.inspection.application.city.CityDirectoryReadService.CityView;
import com.lowaltitude.inspection.application.city.CityDirectoryReadService.DirectoryView;
import com.lowaltitude.inspection.application.city.CityDirectoryReadService.ProvinceView;
import com.lowaltitude.inspection.application.provider.InspectionProviderDirectory;
import com.lowaltitude.inspection.application.provider.InspectionProviderDirectory.ProviderCard;
import com.lowaltitude.inspection.application.provider.InspectionProviderDirectory.ServiceCard;
import org.junit.jupiter.api.Test;

/** 专业服务模块的三个端口（F2）：城市取自平台城市目录，服务商资料和巡检服务取自巡检模块的服务商名录。 */
class ProServicePortsConfigurationTest {

    private static final UUID PROVIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SERVICE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final ProServicePortsConfiguration configuration = new ProServicePortsConfiguration();
    private final InspectionProviderDirectory directory = mock(InspectionProviderDirectory.class);

    @Test
    void cityNamesComeFromThePlatformCityDirectory() {
        CityDirectoryReadService cities = mock(CityDirectoryReadService.class);
        when(cities.directory()).thenReturn(new DirectoryView(List.of(), List.of(new ProvinceView(
                "370000", "山东省", "shandong", "SD", List.of(
                        new CityView("370500", "东营市", "dongying", "DY", "370000", "山东省"))))));
        ProServicePorts.CityDirectory port = configuration.proServiceCityDirectory(cities);

        assertThat(port.cityName("370500")).contains("东营市");
        assertThat(port.cityName("370100")).isEmpty();
        assertThat(port.cityName(null)).isEmpty();
    }

    @Test
    void profilesAreTheInspectionProviderProfiles() {
        when(directory.find(List.of(PROVIDER))).thenReturn(List.of(
                new ProviderCard(PROVIDER, "云翼科技", "企业认证", "专业飞手团队", true)));
        ProServicePorts.ProviderProfiles port = configuration.proServiceProviderProfiles(directory);

        assertThat(port.find(PROVIDER)).contains(
                new ProServicePorts.Provider(PROVIDER, "云翼科技", "企业认证", "专业飞手团队", true));
        assertThat(port.find(UUID.randomUUID())).isEmpty();
        assertThat(port.find(null)).isEmpty();
    }

    @Test
    void inspectionOffersAreTheProvidersInspectionServicesAndCities() {
        when(directory.servicesOf(PROVIDER, "370500")).thenReturn(List.of(
                new ServiceCard(SERVICE, "电力输电线路无人机巡检", "高精度巡检", "电力", false, 69900L, "/公里起", "含报告")));
        when(directory.citiesServed(PROVIDER)).thenReturn(Set.of("370500"));
        ProServicePorts.InspectionOffers port = configuration.proServiceInspectionOffers(directory);

        assertThat(port.servicesOf(PROVIDER, "370500")).containsExactly(new ProServicePorts.InspectionService(
                SERVICE, "电力输电线路无人机巡检", "高精度巡检", "电力", false, 69900L, "/公里起", "含报告"));
        assertThat(port.citiesServed(PROVIDER)).containsExactly("370500");
    }
}
