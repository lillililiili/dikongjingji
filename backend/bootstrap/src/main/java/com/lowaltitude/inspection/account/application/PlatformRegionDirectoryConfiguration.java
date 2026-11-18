package com.lowaltitude.inspection.account.application;

import java.util.Objects;
import java.util.Optional;

import com.lowaltitude.inspection.application.city.CityDirectoryReadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 地址里的省、市取自平台城市目录（只到地级市）。目录几百条，每次保存地址时现读一遍，不另做缓存。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PlatformRegionDirectoryConfiguration {

    @Bean
    RegionDirectory platformRegionDirectory(CityDirectoryReadService cities) {
        return cityCode -> {
            if (cityCode == null || cityCode.isBlank()) {
                return Optional.empty();
            }
            return cities.directory().provinces().stream()
                    .flatMap(province -> province.cities().stream())
                    .filter(city -> Objects.equals(city.code(), cityCode))
                    .findFirst()
                    .map(city -> new RegionDirectory.Region(
                            city.provinceCode(), city.provinceName(), city.code(), city.name()));
        };
    }
}
