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

    private static final String PROJECT_QUOTE = "PROJECT_QUOTE";
    private static final String STARTING_PRICE = "STARTING_PRICE";

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

    /**
     * 这家服务商在这个城市能被看到、能预约的巡检服务（服务商主页用）。按项目报价的没有金额和单位；
     * 起步价的单位写成“/公里起”，按次、按距离计价的写成“/次”“/公里”。
     */
    @Transactional(readOnly = true)
    public List<ServiceCard> servicesOf(UUID providerId, String cityCode) {
        if (providerId == null || cityCode == null || cityCode.isBlank()) {
            return List.of();
        }
        return mapper.findServicesOf(providerId, cityCode.trim()).stream()
                .map(InspectionProviderDirectory::toServiceCard)
                .toList();
    }

    private static ServiceCard toServiceCard(InspectionProviderDirectoryMapper.ServiceRow row) {
        boolean project = PROJECT_QUOTE.equals(row.priceMode()) || row.startingAmountMinor() == null;
        String unit = null;
        if (!project && row.unitLabel() != null && !row.unitLabel().isBlank()) {
            unit = "/" + row.unitLabel().trim() + (STARTING_PRICE.equals(row.priceMode()) ? "起" : "");
        }
        return new ServiceCard(row.serviceId(), row.title(), row.summary(), row.categoryName(), project,
                project ? null : row.startingAmountMinor(), unit, blankToNull(row.priceNote()));
    }

    private static ProviderCard toCard(InspectionProviderDirectoryMapper.ProviderRow row) {
        return new ProviderCard(row.providerId(), row.name(), blankToNull(row.certificationLabel()),
                blankToNull(row.description()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 一项巡检服务的卡片。projectPrice 为 true 时 amountMinor、unit 为空。 */
    public record ServiceCard(
            UUID serviceId,
            String title,
            String summary,
            String categoryName,
            boolean projectPrice,
            Long amountMinor,
            String unit,
            String note
    ) {
    }

    public record ProviderCard(UUID providerId, String name, String certificationLabel, String description) {
    }
}
