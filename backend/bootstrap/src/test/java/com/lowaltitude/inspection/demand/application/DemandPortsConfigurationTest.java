package com.lowaltitude.inspection.demand.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.application.city.CityDirectoryReadService;
import com.lowaltitude.inspection.application.city.CityDirectoryReadService.CityView;
import com.lowaltitude.inspection.application.city.CityDirectoryReadService.DirectoryView;
import com.lowaltitude.inspection.application.city.CityDirectoryReadService.ProvinceView;
import com.lowaltitude.inspection.application.provider.InspectionProviderDirectory;
import com.lowaltitude.inspection.application.provider.InspectionProviderDirectory.ProviderCard;
import com.lowaltitude.inspection.notification.application.NewNotification;
import com.lowaltitude.inspection.notification.application.NotificationCategory;
import com.lowaltitude.inspection.notification.application.NotificationService;
import com.lowaltitude.inspection.notification.application.NotificationTargetType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 需求模块的三个端口（F1）：城市取自平台城市目录，服务商取自巡检的服务商名录，站内信只写需求标题和 ID。 */
class DemandPortsConfigurationTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEMAND = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-19T02:00:00Z");

    private final DemandPortsConfiguration configuration = new DemandPortsConfiguration();

    @Test
    void cityNamesComeFromThePlatformCityDirectory() {
        CityDirectoryReadService cities = mock(CityDirectoryReadService.class);
        when(cities.directory()).thenReturn(new DirectoryView(List.of(), List.of(new ProvinceView(
                "370000", "山东省", "shandong", "SD", List.of(
                        new CityView("370500", "东营市", "dongying", "DY", "370000", "山东省"),
                        new CityView("370100", "济南市", "jinan", "JN", "370000", "山东省"))))));
        DemandPorts.CityDirectory directory = configuration.demandCityDirectory(cities);

        assertThat(directory.cityName("370100")).contains("济南市");
        assertThat(directory.cityName("999999")).isEmpty();
        assertThat(directory.cityName(" ")).isEmpty();
    }

    @Test
    void providersComeFromTheInspectionProviderDirectory() {
        InspectionProviderDirectory inspection = mock(InspectionProviderDirectory.class);
        ProviderCard card = new ProviderCard(PROVIDER, "云翼科技", "企业认证", "专业飞手团队");
        when(inspection.providersIn("370500")).thenReturn(List.of(card));
        when(inspection.find(List.of(PROVIDER))).thenReturn(List.of(card));
        when(inspection.citiesServed(PROVIDER)).thenReturn(Set.of("370500", "370100"));
        DemandPorts.ProviderDirectory directory = configuration.demandProviderDirectory(inspection);

        DemandPorts.Provider expected = new DemandPorts.Provider(PROVIDER, "云翼科技", "企业认证", "专业飞手团队");
        assertThat(directory.providersIn("370500")).containsExactly(expected);
        assertThat(directory.find(List.of(PROVIDER))).containsExactly(expected);
        assertThat(directory.citiesServed(PROVIDER)).containsExactlyInAnyOrder("370500", "370100");
    }

    // 收到新报价、无人报价关闭：分类 DEMAND_QUOTE、跳到这份需求；正文只有需求标题，没有服务商和报价内容。
    @Test
    void notificationsCarryOnlyTheDemandTitleAndItsId() {
        NotificationService notifications = mock(NotificationService.class);
        DemandPorts.Notifier notifier = DemandPortsConfiguration.notifier(notifications, Clock.fixed(NOW, ZoneOffset.UTC));

        notifier.quoteReceived(ACCOUNT, DEMAND, "巡检需求", "demand-quote:q:1");
        notifier.closedWithoutQuotes(ACCOUNT, DEMAND, "航拍需求");

        ArgumentCaptor<NewNotification> sent = ArgumentCaptor.forClass(NewNotification.class);
        verify(notifications, org.mockito.Mockito.times(2)).record(sent.capture());
        NewNotification quote = sent.getAllValues().get(0);
        NewNotification closed = sent.getAllValues().get(1);
        assertThat(quote).isEqualTo(new NewNotification(ACCOUNT, NotificationCategory.DEMAND_QUOTE,
                "DEMAND_QUOTE_RECEIVED", "收到新报价",
                "你发布的巡检需求收到一份新报价，可以在“我的 → 需求与报价”里比选。",
                NotificationTargetType.DEMAND, DEMAND, null, "demand-quote:q:1", NOW));
        assertThat(closed).isEqualTo(new NewNotification(ACCOUNT, NotificationCategory.DEMAND_QUOTE,
                "DEMAND_CLOSED_NO_QUOTES", "需求已关闭",
                "你发布的航拍需求在报价期内没有服务商报价，已自动关闭。可以修改后重新发布。",
                NotificationTargetType.DEMAND, DEMAND, null, "demand-closed:" + DEMAND, NOW));
        for (NewNotification notification : sent.getAllValues()) {
            assertThat(notification.body()).doesNotContain("云翼").doesNotContain("¥").doesNotContain("元");
            assertThat(notification.sourceKey().length()).isLessThanOrEqualTo(120);
        }
    }
}
