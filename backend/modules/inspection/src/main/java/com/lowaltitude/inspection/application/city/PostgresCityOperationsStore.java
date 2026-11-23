package com.lowaltitude.inspection.application.city;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.infrastructure.persistence.PlatformCityMapper;
import com.lowaltitude.inspection.infrastructure.persistence.PlatformCityMapper.OpsCityRow;

public final class PostgresCityOperationsStore implements CityOperationsService.Store {

    private final PlatformCityMapper mapper;

    public PostgresCityOperationsStore(PlatformCityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long count(String keyword, String serviceStatus, String provinceCode) {
        return mapper.countOpsCities(keyword, serviceStatus, provinceCode);
    }

    @Override
    public List<CityOperationsService.Row> page(String keyword, String serviceStatus, String provinceCode, int limit,
                                                int offset) {
        return mapper.findOpsCities(keyword, serviceStatus, provinceCode, limit, offset).stream()
                .map(PostgresCityOperationsStore::row)
                .toList();
    }

    @Override
    public Optional<CityOperationsService.Row> find(String cityCode) {
        return Optional.ofNullable(mapper.findOpsCity(cityCode)).map(PostgresCityOperationsStore::row);
    }

    @Override
    public int updateStatus(String cityCode, String serviceStatus, int version, OffsetDateTime at) {
        return mapper.updateServiceStatus(cityCode, serviceStatus, version, at);
    }

    @Override
    public void insertChange(String cityCode, String fromStatus, String toStatus, String note, UUID operatorId,
                             String operatorName, OffsetDateTime at) {
        if (mapper.insertServiceStatusChange(cityCode, fromStatus, toStatus, note, operatorId, operatorName, at) != 1) {
            throw new IllegalStateException("city status change was not recorded");
        }
    }

    @Override
    public List<CityOperationsService.ChangeRow> changes(String cityCode, int limit) {
        return mapper.findServiceStatusChanges(cityCode, limit).stream()
                .map(row -> new CityOperationsService.ChangeRow(row.id(), row.fromStatus(), row.toStatus(), row.note(),
                        row.operatorName(), row.changedAt()))
                .toList();
    }

    private static CityOperationsService.Row row(OpsCityRow row) {
        return new CityOperationsService.Row(row.cityCode(), row.cityName(), row.provinceCode(), row.provinceName(),
                row.serviceStatus(), row.serviceStatusChangedAt(), row.version());
    }
}
