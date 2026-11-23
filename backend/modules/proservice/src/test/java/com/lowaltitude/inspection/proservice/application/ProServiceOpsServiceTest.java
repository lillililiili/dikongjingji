package com.lowaltitude.inspection.proservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.proservice.application.ProServiceOpsService.LineDraft;
import com.lowaltitude.inspection.proservice.application.ProServiceOpsService.ServiceDraft;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsMapper;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsMapper.DetailRow;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsMapper.ItemRow;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsMapper.ServiceFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 运营端“专业服务目录”的业务规则（S3-1）：价格组合、筛选项归属、上架必填、版本号、改动记录。 */
class ProServiceOpsServiceTest {

    private static final UUID OPERATOR = UUID.fromString("90000000-0000-4000-8000-0000000000b1");
    private static final UUID PROVIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SERVICE = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-23T02:00:00Z");

    private final ProServiceOpsMapper mapper = mock(ProServiceOpsMapper.class);
    private final IdentityOrganizationPort organizations = mock(IdentityOrganizationPort.class);
    private final ProServicePorts.CityDirectory cities = new ProServicePorts.CityDirectory() {
        @Override
        public Optional<String> cityName(String cityCode) {
            return cityCode.startsWith("37") ? Optional.of("城市" + cityCode) : Optional.empty();
        }

        @Override
        public Map<String, String> cityNames(Collection<String> cityCodes) {
            Map<String, String> names = new LinkedHashMap<>();
            cityCodes.forEach(code -> cityName(code).ifPresent(name -> names.put(code, name)));
            return names;
        }
    };
    private final ProServiceOpsService service = new ProServiceOpsService(mapper, organizations, cities,
            Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(organizations.findActiveOrganization(PROVIDER)).thenReturn(Optional.of(
                new IdentityOrganization(PROVIDER, "鹰眼智能巡检", "PROVIDER", null)));
        when(mapper.insertService(any(), any(), any())).thenReturn(1);
        when(mapper.updateService(any(), any(), anyInt(), any())).thenReturn(1);
        when(mapper.updateStatus(any(), anyString(), anyInt(), any())).thenReturn(1);
        when(mapper.insertChange(any(), anyString(), anyString(), any(), anyString(), any())).thenReturn(1);
    }

    @Test
    void newServicesStartUnpublishedWithTheirItemsAndARecord() {
        when(mapper.findService(any())).thenAnswer(invocation -> row(invocation.getArgument(0), "UNPUBLISHED", 0));

        service.create(draft("AERIAL", List.of("VIDEO_FOOTAGE", "PHOTOS"), List.of("370500")), OPERATOR, "运营");

        ArgumentCaptor<ServiceFields> fields = ArgumentCaptor.forClass(ServiceFields.class);
        verify(mapper).insertService(any(), fields.capture(), eq(NOW));
        assertThat(fields.getValue().priceAmountMinor()).isEqualTo(180000L);
        assertThat(fields.getValue().priceUnit()).isEqualTo("/次起");
        verify(mapper).activateCity(any(), eq("370500"), eq(NOW));
        verify(mapper).deleteItems(any());
        verify(mapper).insertItem(any(), eq(new ItemRow("FACET", "VIDEO_FOOTAGE", "视频素材", null)), eq(10), eq(NOW));
        verify(mapper).insertItem(any(), eq(new ItemRow("FACET", "PHOTOS", "照片", null)), eq(20), eq(NOW));
        verify(mapper).insertItem(any(), eq(new ItemRow("OUTPUT", null, "成片", "1080P")), eq(10), eq(NOW));
        verify(mapper).insertItem(any(), eq(new ItemRow("STEP", null, "沟通需求", null)), eq(10), eq(NOW));
        verify(mapper).insertChange(any(), eq("CREATE"), eq("新建航拍服务“文旅航拍”（未上架）"), eq(OPERATOR), eq("运营"),
                eq(NOW));
    }

    @Test
    void inputsMustFitTheTypeAndThePriceMode() {
        assertThatThrownBy(() -> service.create(draft("AERIAL", List.of("ON_SITE"), List.of()), OPERATOR, "运营"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(draft("AERIAL", List.of(), List.of("110100")), OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error()).isEqualTo(ProServiceOpsError.CITY_UNKNOWN);
        ServiceDraft noUnit = new ServiceDraft(PROVIDER, "AERIAL", "TOURISM", "文旅航拍", "景区宣传片", "PRICE_FROM",
                180000L, null, "含 1 架次", null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(noUnit, OPERATOR, "运营")).isInstanceOf(IllegalArgumentException.class);
        ServiceDraft unknownProvider = new ServiceDraft(UUID.randomUUID(), "AERIAL", "TOURISM", "文旅航拍", "景区宣传片",
                "PROJECT", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(unknownProvider, OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(ProServiceOpsError.PROVIDER_UNKNOWN);
        verify(mapper, never()).insertService(any(), any(), any());
    }

    @Test
    void publishingNeedsCitiesFacetsAndSteps() {
        when(mapper.findService(SERVICE)).thenReturn(row(SERVICE, "UNPUBLISHED", 2));
        assertThatThrownBy(() -> service.publish(SERVICE, 2, OPERATOR, "运营"))
                .satisfies(failure -> assertThat(((OpsException) failure).details().get("missing"))
                        .isEqualTo(List.of("cities", "facets", "steps")));

        when(mapper.findCities(SERVICE)).thenReturn(List.of("370500"));
        when(mapper.findItems(SERVICE)).thenReturn(List.of(new ItemRow("FACET", "PHOTOS", "照片", null),
                new ItemRow("STEP", null, "沟通需求", null)));
        service.publish(SERVICE, 2, OPERATOR, "运营");
        verify(mapper).updateStatus(SERVICE, "PUBLISHED", 2, NOW);
        verify(mapper).insertChange(eq(SERVICE), eq("PUBLISH"), eq("上架“文旅航拍”"), eq(OPERATOR), eq("运营"), eq(NOW));

        assertThatThrownBy(() -> service.offline(SERVICE, 1, OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error()).isEqualTo(ProServiceOpsError.VERSION_CONFLICT);
        assertThat(ProServiceOpsService.missing(false, 1, 1, 1)).containsExactly("provider");
    }

    @Test
    void publishedServicesStayCompleteAndUnchangedEditsWriteNothing() {
        when(mapper.findService(SERVICE)).thenReturn(row(SERVICE, "PUBLISHED", 5));
        when(mapper.findCities(SERVICE)).thenReturn(List.of("370500"));
        when(mapper.findItems(SERVICE)).thenReturn(List.of(new ItemRow("FACET", "VIDEO_FOOTAGE", "视频素材", null),
                new ItemRow("OUTPUT", null, "成片", "1080P"), new ItemRow("STEP", null, "沟通需求", null)));

        service.update(SERVICE, draft("AERIAL", List.of("VIDEO_FOOTAGE"), List.of("370500")), 5, OPERATOR, "运营");
        verify(mapper, never()).updateService(any(), any(), anyInt(), any());

        assertThatThrownBy(() -> service.update(SERVICE, draft("AERIAL", List.of("VIDEO_FOOTAGE"), List.of()), 5,
                OPERATOR, "运营"))
                .satisfies(failure -> assertThat(((OpsException) failure).details().get("missing"))
                        .isEqualTo(List.of("cities")));

        service.update(SERVICE, draft("AERIAL", List.of("VIDEO_FOOTAGE", "PHOTOS"), List.of("370500", "370100")), 5,
                OPERATOR, "运营");
        verify(mapper).updateService(eq(SERVICE), any(), eq(5), eq(NOW));
        verify(mapper).disableOtherCities(SERVICE, List.of("370500", "370100"), NOW);
        verify(mapper).insertChange(eq(SERVICE), eq("UPDATE"), eq("修改：上架城市（2 个）、筛选项"), eq(OPERATOR),
                eq("运营"), eq(NOW));
    }

    private static ServiceDraft draft(String type, List<String> facets, List<String> cityCodes) {
        return new ServiceDraft(PROVIDER, type, "TOURISM", " 文旅航拍 ", "景区宣传片", "PRICE_FROM", 180000L, "/次起",
                "含 1 架次", null, 0, cityCodes, List.of(), facets, List.of(), List.of(), List.of(),
                List.of(new LineDraft("成片", "1080P")), List.of(), List.of(), List.of("沟通需求"), List.of());
    }

    private static DetailRow row(UUID id, String status, int version) {
        return new DetailRow(id, status, version, NOW, false, PROVIDER, "AERIAL", "TOURISM", "文旅航拍", "景区宣传片",
                "PRICE_FROM", 180000L, "/次起", "含 1 架次", null, 0, null);
    }
}
