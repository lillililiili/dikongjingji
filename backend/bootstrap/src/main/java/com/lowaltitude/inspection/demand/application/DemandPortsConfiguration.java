package com.lowaltitude.inspection.demand.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.application.city.CityDirectoryReadService;
import com.lowaltitude.inspection.application.provider.InspectionProviderDirectory;
import com.lowaltitude.inspection.notification.application.NewNotification;
import com.lowaltitude.inspection.notification.application.NotificationCategory;
import com.lowaltitude.inspection.notification.application.NotificationService;
import com.lowaltitude.inspection.notification.application.NotificationTargetType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 需求模块的三个端口接到别的模块（F1，§22.3）：平台城市目录、巡检模块的服务商名录、站内信。需求模块不读它们的表。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class DemandPortsConfiguration {

    static final String QUOTE_RECEIVED = "DEMAND_QUOTE_RECEIVED";
    static final String CLOSED_NO_QUOTES = "DEMAND_CLOSED_NO_QUOTES";

    @Bean
    DemandPorts.CityDirectory demandCityDirectory(CityDirectoryReadService cities) {
        return cityCode -> {
            if (cityCode == null || cityCode.isBlank()) {
                return Optional.empty();
            }
            return cities.directory().provinces().stream()
                    .flatMap(province -> province.cities().stream())
                    .filter(city -> Objects.equals(city.code(), cityCode))
                    .findFirst()
                    .map(CityDirectoryReadService.CityView::name);
        };
    }

    @Bean
    DemandPorts.ProviderDirectory demandProviderDirectory(InspectionProviderDirectory directory) {
        return new DemandPorts.ProviderDirectory() {
            @Override
            public List<DemandPorts.Provider> providersIn(String cityCode) {
                return directory.providersIn(cityCode).stream().map(DemandPortsConfiguration::provider).toList();
            }

            @Override
            public List<DemandPorts.Provider> find(List<UUID> providerIds) {
                return directory.find(providerIds).stream().map(DemandPortsConfiguration::provider).toList();
            }

            @Override
            public Set<String> citiesServed(UUID providerId) {
                return directory.citiesServed(providerId);
            }
        };
    }

    @Bean
    DemandPorts.Notifier demandNotifier(NotificationService notifications) {
        return notifier(notifications, java.time.Clock.systemUTC());
    }

    /** 通知只写需求的标题和稳定 ID，不写服务商名称和报价内容。 */
    static DemandPorts.Notifier notifier(NotificationService notifications, java.time.Clock clock) {
        return new DemandPorts.Notifier() {
            @Override
            public void quoteReceived(UUID recipientId, UUID demandId, String demandTitle, String sourceKey) {
                notifications.record(new NewNotification(
                        recipientId,
                        NotificationCategory.DEMAND_QUOTE,
                        QUOTE_RECEIVED,
                        "收到新报价",
                        "你发布的" + demandTitle + "收到一份新报价，可以在“我的 → 需求与报价”里比选。",
                        NotificationTargetType.DEMAND,
                        demandId,
                        null,
                        sourceKey,
                        clock.instant()));
            }

            @Override
            public void closedWithoutQuotes(UUID recipientId, UUID demandId, String demandTitle) {
                notifications.record(new NewNotification(
                        recipientId,
                        NotificationCategory.DEMAND_QUOTE,
                        CLOSED_NO_QUOTES,
                        "需求已关闭",
                        "你发布的" + demandTitle + "在报价期内没有服务商报价，已自动关闭。可以修改后重新发布。",
                        NotificationTargetType.DEMAND,
                        demandId,
                        null,
                        "demand-closed:" + demandId,
                        clock.instant()));
            }
        };
    }

    private static DemandPorts.Provider provider(InspectionProviderDirectory.ProviderCard card) {
        return new DemandPorts.Provider(card.providerId(), card.name(), card.certificationLabel(), card.description());
    }
}
