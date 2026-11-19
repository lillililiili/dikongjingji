package com.lowaltitude.inspection.application.provider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.infrastructure.persistence.InspectionProviderDirectoryMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务商名录：给需求报价（F1）用的只读入口。其他模块不直接读巡检的表，由启动模块把它接到自己的端口上。
 */
public class InspectionProviderDirectory {

    private final InspectionProviderDirectoryMapper mapper;

    public InspectionProviderDirectory(InspectionProviderDirectoryMapper mapper) {
        this.mapper = mapper;
    }

    /** 服务这个城市、有服务商资料的服务商，按名称排。 */
    @Transactional(readOnly = true)
    public List<ProviderCard> providersIn(String cityCode) {
        if (cityCode == null || cityCode.isBlank()) {
            return List.of();
        }
        return mapper.findProvidersIn(cityCode.trim()).stream().map(InspectionProviderDirectory::toCard).toList();
    }

    /** 这些机构里有服务商资料的；不认识的不返回。 */
    @Transactional(readOnly = true)
    public List<ProviderCard> find(List<UUID> providerIds) {
        List<UUID> ids = providerIds == null
                ? List.of()
                : providerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return mapper.findProviders(ids).stream().map(InspectionProviderDirectory::toCard).toList();
    }

    @Transactional(readOnly = true)
    public Set<String> citiesServed(UUID providerId) {
        if (providerId == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(mapper.findCitiesServed(providerId));
    }

    private static ProviderCard toCard(InspectionProviderDirectoryMapper.ProviderRow row) {
        return new ProviderCard(row.providerId(), row.name(), blankToNull(row.certificationLabel()),
                blankToNull(row.description()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ProviderCard(UUID providerId, String name, String certificationLabel, String description) {
    }
}
