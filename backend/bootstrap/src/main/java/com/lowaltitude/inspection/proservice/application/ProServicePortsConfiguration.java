package com.lowaltitude.inspection.proservice.application;

import java.util.Objects;
import java.util.Optional;

import com.lowaltitude.inspection.application.city.CityDirectoryReadService;
import com.lowaltitude.inspection.application.provider.InspectionProviderDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 专业服务模块的三个端口接到别的模块（F2，§23.3）：平台城市目录，巡检模块的服务商资料和巡检服务。专业服务模块不读它们的表。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ProServicePortsConfiguration {

    @Bean
    ProServicePorts.CityDirectory proServiceCityDirectory(CityDirectoryReadService cities) {
        return new ProServicePorts.CityDirectory() {
            @Override
            public Optional<String> cityName(String cityCode) {
                if (cityCode == null || cityCode.isBlank()) {
                    return Optional.empty();
                }
                return cities.directory().provinces().stream()
                        .flatMap(province -> province.cities().stream())
                        .filter(city -> Objects.equals(city.code(), cityCode))
                        .findFirst()
                        .map(CityDirectoryReadService.CityView::name);
            }

            // 运营端（S3-1）一次校验多个城市：只读一次目录。
            @Override
            public java.util.Map<String, String> cityNames(java.util.Collection<String> cityCodes) {
                java.util.Map<String, String> all = new java.util.HashMap<>();
                cities.directory().provinces().forEach(province -> province.cities()
                        .forEach(city -> all.put(city.code(), city.name())));
                java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
                for (String cityCode : cityCodes) {
                    if (all.containsKey(cityCode)) {
                        names.put(cityCode, all.get(cityCode));
                    }
                }
                return names;
            }
        };
    }

    @Bean
    ProServicePorts.ProviderProfiles proServiceProviderProfiles(InspectionProviderDirectory directory) {
        return providerId -> providerId == null
                ? Optional.empty()
                : directory.find(java.util.List.of(providerId)).stream()
                        .findFirst()
                        .map(card -> new ProServicePorts.Provider(card.providerId(), card.name(),
                                card.certificationLabel(), card.description(), card.qualificationReviewed()));
    }

    @Bean
    ProServicePorts.InspectionOffers proServiceInspectionOffers(InspectionProviderDirectory directory) {
        return new ProServicePorts.InspectionOffers() {
            @Override
            public java.util.List<ProServicePorts.InspectionService> servicesOf(java.util.UUID providerId,
                                                                               String cityCode) {
                return directory.servicesOf(providerId, cityCode).stream()
                        .map(card -> new ProServicePorts.InspectionService(card.serviceId(), card.title(),
                                card.summary(), card.categoryName(), card.projectPrice(), card.amountMinor(),
                                card.unit(), card.note()))
                        .toList();
            }

            @Override
            public java.util.Set<String> citiesServed(java.util.UUID providerId) {
                return directory.citiesServed(providerId);
            }
        };
    }
}
