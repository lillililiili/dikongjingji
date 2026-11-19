package com.lowaltitude.inspection.proservice.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 专业服务模块用到的别的模块的能力，由启动模块接上（§23.3）：平台城市目录、巡检模块的服务商资料和巡检服务。
 * 本模块不读它们的表。
 */
public final class ProServicePorts {

    private ProServicePorts() {
    }

    public interface CityDirectory {

        /** 平台城市目录里这个城市的名称；不在目录里时为空。 */
        Optional<String> cityName(String cityCode);
    }

    public interface ProviderProfiles {

        /** 有在用的服务商资料的服务商；没有资料或已停用时为空。 */
        Optional<Provider> find(UUID providerId);
    }

    public interface InspectionOffers {

        /** 这家服务商在这个城市能被看到、能预约的巡检服务。 */
        List<InspectionService> servicesOf(UUID providerId, String cityCode);

        /** 这家服务商的巡检服务覆盖的城市。 */
        Set<String> citiesServed(UUID providerId);
    }

    public record Provider(UUID providerId, String name, String certificationLabel, String description) {
    }

    /**
     * 一项巡检服务。priceMode 为 PROJECT 时 amountMinor、unit 为空；unit 是展示用的单位，如“/公里起”。
     */
    public record InspectionService(
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
}
