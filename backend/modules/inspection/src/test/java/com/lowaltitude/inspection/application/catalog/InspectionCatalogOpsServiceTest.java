package com.lowaltitude.inspection.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.ItemDraft;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.ServiceDraft;
import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.CategoryRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.CityRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.DetailItemRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.ItemRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.ServiceDetailRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.ServiceFields;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 运营端“巡检服务目录”的业务规则（S3-1）：校验、编码、上架必填、版本号、改动记录。 */
class InspectionCatalogOpsServiceTest {

    private static final UUID OPERATOR = UUID.fromString("90000000-0000-4000-8000-0000000000b1");
    private static final UUID PROVIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CUSTOMER_ORG = UUID.fromString("22222222-2222-2222-2222-000000000001");
    private static final UUID SERVICE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-23T02:00:00Z");

    private final InspectionCatalogOpsMapper mapper = mock(InspectionCatalogOpsMapper.class);
    private final IdentityOrganizationPort organizations = mock(IdentityOrganizationPort.class);
    private final InspectionCatalogOpsService service = new InspectionCatalogOpsService(mapper, organizations,
            Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(organizations.findActiveOrganization(PROVIDER)).thenReturn(Optional.of(
                new IdentityOrganization(PROVIDER, "鹰眼智能巡检", "PROVIDER", null)));
        when(organizations.findActiveOrganization(CUSTOMER_ORG)).thenReturn(Optional.of(
                new IdentityOrganization(CUSTOMER_ORG, "某企业", "CUSTOMER", null)));
        when(mapper.findCategory("POWER")).thenReturn(category("POWER", "电力", "ACTIVE", 3));
        when(mapper.findCategory("OLD")).thenReturn(category("OLD", "旧类目", "INACTIVE", 0));
        when(mapper.findDirectoryCities(anyList())).thenAnswer(invocation -> {
            List<String> codes = invocation.getArgument(0);
            return codes.stream().filter(code -> code.startsWith("37"))
                    .map(code -> new CityRow(code, "城市" + code)).toList();
        });
        when(mapper.insertService(any(), anyString(), any(), any())).thenReturn(1);
        when(mapper.updateService(any(), any(), anyInt(), any())).thenReturn(1);
        when(mapper.updateServiceStatus(any(), anyString(), anyInt(), any())).thenReturn(1);
        when(mapper.insertChange(anyString(), any(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(1);
    }

    @Test
    void newServicesStartUnlistedWithGeneratedCodesAndARecord() {
        when(mapper.findService(any())).thenAnswer(invocation -> row(invocation.getArgument(0), "DISABLED", 0));

        service.createService(draft(" 光伏电站巡检 ", List.of("370500", "370100", "370500"),
                List.of(new ItemDraft(null, "组件热斑", null, "checkbox"), new ItemDraft("KEEP_ME", "支架", "看锈蚀", null))),
                OPERATOR, "平台运营（示例）");

        ArgumentCaptor<ServiceFields> fields = ArgumentCaptor.forClass(ServiceFields.class);
        ArgumentCaptor<String> serviceNo = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertService(any(), serviceNo.capture(), fields.capture(), eq(NOW));
        assertThat(serviceNo.getValue()).matches("^INS-SVC-[0-9A-F]{12}$");
        assertThat(fields.getValue().title()).isEqualTo("光伏电站巡检");
        assertThat(fields.getValue().cityCode()).isEqualTo("370500");
        assertThat(fields.getValue().categoryName()).isEqualTo("电力");
        assertThat(fields.getValue().currency()).isEqualTo("CNY");
        verify(mapper).activateCity(any(), eq("370500"), eq(NOW));
        verify(mapper).activateCity(any(), eq("370100"), eq(NOW));
        ArgumentCaptor<ItemRow> items = ArgumentCaptor.forClass(ItemRow.class);
        verify(mapper, org.mockito.Mockito.times(2)).upsertBookingItem(any(), items.capture(), anyInt(), eq(NOW));
        assertThat(items.getAllValues().get(0).code()).matches("^OPS_[0-9A-F]{8}$");
        assertThat(items.getAllValues().get(1).code()).isEqualTo("KEEP_ME");
        verify(mapper).insertMethod(any(), eq("UAV"), eq(NOW));
        verify(mapper).insertChange(eq("SERVICE"), any(), eq("CREATE"), eq("新建服务“光伏电站巡检”（未上架）"),
                eq(OPERATOR), eq("平台运营（示例）"), eq(NOW));
    }

    @Test
    void providerCityAndCategoryMustBeKnown() {
        assertThatThrownBy(() -> service.createService(withProvider(CUSTOMER_ORG), OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(InspectionCatalogOpsError.PROVIDER_UNKNOWN);
        assertThatThrownBy(() -> service.createService(draft("巡检", List.of("110100"), List.of()), OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(InspectionCatalogOpsError.CITY_UNKNOWN);
        assertThatThrownBy(() -> service.createService(withCategory("OLD"), OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(InspectionCatalogOpsError.CATEGORY_UNKNOWN);
        assertThatThrownBy(() -> service.createService(draft("巡检", List.of(), List.of()), OPERATOR, "运营"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createService(draft("巡检", List.of("370500"),
                List.of(new ItemDraft("A", "一", null, null), new ItemDraft("A", "二", null, null))), OPERATOR, "运营"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertService(any(), anyString(), any(), any());
    }

    @Test
    void publishingNeedsEverythingTheAppShows() {
        when(mapper.findService(SERVICE)).thenReturn(row(SERVICE, "DISABLED", 2));

        assertThatThrownBy(() -> service.publish(SERVICE, 2, OPERATOR, "运营"))
                .isInstanceOf(OpsException.class)
                .satisfies(failure -> {
                    OpsException ops = (OpsException) failure;
                    assertThat(ops.error()).isEqualTo(InspectionCatalogOpsError.PUBLISH_INCOMPLETE);
                    assertThat(ops.details().get("missing"))
                            .isEqualTo(List.of("coverUrl", "cities", "bookingItems", "processSteps", "deliverables"));
                });

        when(mapper.findServiceCities(SERVICE)).thenReturn(List.of(new CityRow("370500", "东营市")));
        when(mapper.findBookingItems(SERVICE)).thenReturn(List.of(new ItemRow("POWER_LINE", "杆塔本体巡检", null, null)));
        when(mapper.findDetailItems(SERVICE)).thenReturn(List.of(
                new DetailItemRow("PROCESS", "BOOK", "预约", null, null),
                new DetailItemRow("DELIVERABLE", "REPORT", "巡检报告", null, null)));
        when(mapper.findService(SERVICE)).thenReturn(withCover(row(SERVICE, "DISABLED", 2)));
        // 身份 V013：资质没审核通过的服务商，资料齐全也不能上架。
        when(organizations.findActiveOrganization(PROVIDER)).thenReturn(Optional.of(
                new IdentityOrganization(PROVIDER, "新入驻服务商", "PROVIDER", null, true)));
        assertThatThrownBy(() -> service.publish(SERVICE, 2, OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(InspectionCatalogOpsError.PROVIDER_NOT_QUALIFIED);
        verify(mapper, never()).updateServiceStatus(any(), anyString(), anyInt(), any());
        when(organizations.findActiveOrganization(PROVIDER)).thenReturn(Optional.of(
                new IdentityOrganization(PROVIDER, "鹰眼智能巡检", "PROVIDER", null)));
        service.publish(SERVICE, 2, OPERATOR, "运营");
        verify(mapper).updateServiceStatus(SERVICE, "ACTIVE", 2, NOW);
        verify(mapper).insertChange(eq("SERVICE"), eq(SERVICE), eq("PUBLISH"), anyString(), eq(OPERATOR),
                eq("运营"), eq(NOW));

        assertThatThrownBy(() -> service.publish(SERVICE, 1, OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(InspectionCatalogOpsError.VERSION_CONFLICT);
    }

    @Test
    void projectQuoteNeedsNoAmountAndPerVisitNeedsAUnit() {
        var base = new InspectionCatalogOpsService.Completeness(true, "s", "POWER", "https://cdn.example.com/a.jpg",
                InspectionCatalogOpsService.PriceMode.PROJECT_QUOTE, null, null,
                InspectionCatalogOpsService.Availability.RESERVABLE, InspectionCatalogOpsService.PrimaryAction.RESERVE,
                1, 1, 1, 1);
        assertThat(InspectionCatalogOpsService.missing(base)).isEmpty();
        var perVisit = new InspectionCatalogOpsService.Completeness(false, null, "POWER", null,
                InspectionCatalogOpsService.PriceMode.PER_VISIT, null, null, null, null, 1, 1, 1, 1);
        assertThat(InspectionCatalogOpsService.missing(perVisit)).containsExactly("provider", "summary", "coverUrl",
                "startingAmountMinor", "unit", "availability", "primaryAction");
    }

    @Test
    void listedServicesStayCompleteAndUnchangedEditsWriteNothing() {
        when(mapper.findService(SERVICE)).thenReturn(withCover(row(SERVICE, "ACTIVE", 4)));
        when(mapper.findServiceCities(SERVICE)).thenReturn(List.of(new CityRow("370500", "东营市")));
        when(mapper.findBookingItems(SERVICE)).thenReturn(List.of(new ItemRow("POWER_LINE", "杆塔本体巡检", null, null)));
        when(mapper.findDetailItems(SERVICE)).thenReturn(List.of(
                new DetailItemRow("PROCESS", "BOOK", "预约", null, null),
                new DetailItemRow("DELIVERABLE", "REPORT", "巡检报告", null, null)));
        when(mapper.findMethods(SERVICE)).thenReturn(List.of("UAV"));

        ServiceDraft same = new ServiceDraft(PROVIDER, "电力巡检", "高精度巡检", "POWER", "https://cdn.example.com/a.jpg",
                "PER_VISIT", 99900L, "VISIT", "次", null, null, "RESERVABLE", "RESERVE", false, List.of("UAV"),
                List.of("370500"), List.of(new ItemDraft("POWER_LINE", "杆塔本体巡检", null, null)), List.of(), List.of(),
                List.of(new ItemDraft("BOOK", "预约", null, null)), List.of(new ItemDraft("REPORT", "巡检报告", null, null)),
                List.of());
        service.updateService(SERVICE, same, 4, OPERATOR, "运营");
        verify(mapper, never()).updateService(any(), any(), anyInt(), any());

        ServiceDraft dropped = new ServiceDraft(PROVIDER, "电力巡检", "高精度巡检", "POWER",
                "https://cdn.example.com/a.jpg", "PER_VISIT", 99900L, "VISIT", "次", null, null, "RESERVABLE", "RESERVE",
                false, List.of("UAV"), List.of("370500"), List.of(new ItemDraft("POWER_LINE", "杆塔本体巡检", null, null)),
                List.of(), List.of(), List.of(new ItemDraft("BOOK", "预约", null, null)), List.of(), List.of());
        assertThatThrownBy(() -> service.updateService(SERVICE, dropped, 4, OPERATOR, "运营"))
                .satisfies(failure -> assertThat(((OpsException) failure).details().get("missing"))
                        .isEqualTo(List.of("deliverables")));

        ServiceDraft renamed = new ServiceDraft(PROVIDER, "电力线路巡检", "高精度巡检", "POWER",
                "https://cdn.example.com/a.jpg", "PER_VISIT", 89900L, "VISIT", "次", null, null, "RESERVABLE", "RESERVE",
                false, List.of("UAV"), List.of("370500"), List.of(new ItemDraft("POWER_LINE", "杆塔本体巡检", null, null)),
                List.of(), List.of(), List.of(new ItemDraft("BOOK", "预约", null, null)),
                List.of(new ItemDraft("REPORT", "巡检报告", null, null)), List.of());
        service.updateService(SERVICE, renamed, 4, OPERATOR, "运营");
        verify(mapper).updateService(eq(SERVICE), any(), eq(4), eq(NOW));
        verify(mapper).insertChange(eq("SERVICE"), eq(SERVICE), eq("UPDATE"), eq("修改：标题、价格"), eq(OPERATOR),
                eq("运营"), eq(NOW));
    }

    @Test
    void categoriesCannotBeStoppedWhileServicesAreListed() {
        when(mapper.countActiveServicesUnder("POWER")).thenReturn(2L);
        assertThatThrownBy(() -> service.changeCategoryStatus("POWER", "INACTIVE", 3, OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(InspectionCatalogOpsError.CATEGORY_IN_USE);
        assertThatThrownBy(() -> service.changeCategoryStatus("POWER", "ACTIVE", 3, OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(InspectionCatalogOpsError.STATUS_UNCHANGED);

        when(mapper.updateCategory("POWER", "电力能源", null, 10, 3, NOW)).thenReturn(1);
        service.updateCategory("POWER", "电力能源", null, 10, 3, OPERATOR, "运营");
        verify(mapper).renameCategoryOnServices("POWER", "电力能源");
        verify(mapper).insertChange(eq("CATEGORY"), eq(InspectionCatalogOpsService.categorySubject("POWER")),
                eq("UPDATE"), eq("类目 POWER：名称 电力 → 电力能源"), eq(OPERATOR), eq("运营"), eq(NOW));

        when(mapper.insertCategory(eq("POWER"), anyString(), any(), any(), anyInt(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.createCategory(new InspectionCatalogOpsService.CategoryDraft("POWER", "电力",
                null, null, 0), OPERATOR, "运营"))
                .extracting(failure -> ((OpsException) failure).error())
                .isEqualTo(InspectionCatalogOpsError.CATEGORY_CODE_TAKEN);
        assertThatThrownBy(() -> service.createCategory(new InspectionCatalogOpsService.CategoryDraft("power", "电力",
                null, null, 0), OPERATOR, "运营")).isInstanceOf(IllegalArgumentException.class);
    }

    private static ServiceDraft draft(String title, List<String> cities, List<ItemDraft> bookingItems) {
        return new ServiceDraft(PROVIDER, title, "简介", "POWER", null, "PER_VISIT", 99900L, "VISIT", "次", null, null,
                "RESERVABLE", "RESERVE", true, List.of("UAV", "UAV"), cities, bookingItems, null, null, null, null,
                null);
    }

    private static ServiceDraft withProvider(UUID provider) {
        ServiceDraft base = draft("巡检", List.of("370500"), List.of());
        return new ServiceDraft(provider, base.title(), base.summary(), base.categoryCode(), base.coverUrl(),
                base.priceMode(), base.startingAmountMinor(), base.unitCode(), base.unitLabel(), null, null,
                base.availability(), base.primaryAction(), true, base.serviceMethods(), base.cityCodes(), List.of(),
                null, null, null, null, null);
    }

    private static ServiceDraft withCategory(String category) {
        ServiceDraft base = draft("巡检", List.of("370500"), List.of());
        return new ServiceDraft(PROVIDER, base.title(), base.summary(), category, null, base.priceMode(),
                base.startingAmountMinor(), base.unitCode(), base.unitLabel(), null, null, base.availability(),
                base.primaryAction(), true, base.serviceMethods(), base.cityCodes(), List.of(), null, null, null, null,
                null);
    }

    private static CategoryRow category(String code, String name, String status, int version) {
        return new CategoryRow(code, name, null, null, 10, status, version, NOW, 0);
    }

    private static ServiceDetailRow row(UUID id, String status, int version) {
        return new ServiceDetailRow(id, "INS-SVC-DEV-POWER", status, version, NOW, PROVIDER, "鹰眼智能巡检", "电力巡检",
                "高精度巡检", "POWER", "电力", null, "370500", "PER_VISIT", 99900L, "VISIT", "次", null, null,
                "RESERVABLE", "RESERVE", false, 0);
    }

    private static ServiceDetailRow withCover(ServiceDetailRow row) {
        return new ServiceDetailRow(row.serviceId(), row.serviceNo(), row.status(), row.version(), row.updatedAt(),
                row.providerId(), row.providerName(), row.title(), row.summary(), row.categoryCode(),
                row.categoryName(), "https://cdn.example.com/a.jpg", row.cityCode(), row.priceMode(),
                row.startingAmountMinor(), row.unitCode(), row.unitLabel(), row.priceNote(), row.cancellationNote(),
                row.availability(), row.primaryAction(), row.includesReport(), row.completedCount());
    }
}
