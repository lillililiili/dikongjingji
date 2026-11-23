package com.lowaltitude.inspection.application.city;

import java.util.List;

import com.lowaltitude.inspection.infrastructure.persistence.PlatformCityMapper;

public final class PostgresCityDirectoryStore
        implements CityDirectoryReadService.CityDirectoryStore {

    private final PlatformCityMapper mapper;

    public PostgresCityDirectoryStore(PlatformCityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<CityDirectoryReadService.StoredCity> findActiveCities() {
        return mapper.findActiveCities().stream()
                .map(row -> new CityDirectoryReadService.StoredCity(
                        row.cityCode(),
                        row.cityName(),
                        row.cityPinyin(),
                        row.cityInitials(),
                        row.provinceCode(),
                        row.provinceName(),
                        row.provincePinyin(),
                        row.provinceInitials(),
                        row.searchCount(),
                        row.serviceStatus() == null ? null : CityServiceStatus.valueOf(row.serviceStatus())))
                .toList();
    }

    @Override
    public int incrementSearchCount(String cityCode) {
        return mapper.incrementSearchCount(cityCode);
    }
}
