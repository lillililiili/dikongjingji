package com.lowaltitude.inspection.application.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.CategoryStatus;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.CategoryView;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.ServiceDraft;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.ServicePageView;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“巡检服务目录”的控制器（S3-1）：只给平台运营，操作人取自令牌和身份资料，业务拒绝按契约给状态码。 */
class OpsInspectionCatalogControllerTest {

    private static final UUID SERVICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PROVIDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final InspectionCatalogOpsService service = mock(InspectionCatalogOpsService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(
            new OpsInspectionCatalogController(service, OpsTestSupport.actors()));

    @Test
    void operatorListsCreatesAndChangesWithItsOwnName() throws Exception {
        when(service.services("电力", "POWER", "370500", "ACTIVE", 2, 10))
                .thenReturn(new ServicePageView(2, 10, 11, false, List.of()));
        when(service.updateCategory("POWER", "电力", null, 10, 3, OpsTestSupport.OPERATOR_ID,
                OpsTestSupport.OPERATOR_NAME)).thenReturn(new CategoryView("POWER", "电力", null, null, 10,
                CategoryStatus.ACTIVE, 2, 4, OffsetDateTime.parse("2026-11-23T02:00:00Z")));

        mockMvc.perform(get("/api/v1/ops/inspection/services?keyword=电力&categoryCode=POWER&cityCode=370500"
                        + "&status=ACTIVE&page=2&size=10").principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11));
        mockMvc.perform(put("/api/v1/ops/inspection/categories/POWER").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"电力\",\"sortOrder\":10,\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-23T02:00:00Z"));

        mockMvc.perform(post("/api/v1/ops/inspection/services").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"" + PROVIDER_ID + "\",\"title\":\"光伏巡检\","
                                + "\"priceMode\":\"PER_VISIT\",\"startingAmountMinor\":99900,"
                                + "\"cityCodes\":[\"370500\"],"
                                + "\"processSteps\":[{\"title\":\"现场勘察\"}],\"serviceMethods\":[\"UAV\"]}"))
                .andExpect(status().isCreated());
        ArgumentCaptor<ServiceDraft> draft = ArgumentCaptor.forClass(ServiceDraft.class);
        verify(service).createService(draft.capture(), eq(OpsTestSupport.OPERATOR_ID),
                eq(OpsTestSupport.OPERATOR_NAME));
        org.assertj.core.api.Assertions.assertThat(draft.getValue().providerId()).isEqualTo(PROVIDER_ID);
        org.assertj.core.api.Assertions.assertThat(draft.getValue().startingAmountMinor()).isEqualTo(99900L);
        org.assertj.core.api.Assertions.assertThat(draft.getValue().processSteps().get(0).title()).isEqualTo("现场勘察");
        org.assertj.core.api.Assertions.assertThat(draft.getValue().serviceMethods()).containsExactly("UAV");
    }

    @Test
    void rejectionsFollowTheContract() throws Exception {
        when(service.publish(SERVICE_ID, 2, OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME))
                .thenThrow(OpsException.incomplete(InspectionCatalogOpsError.PUBLISH_INCOMPLETE,
                        List.of("coverUrl", "processSteps")));
        when(service.offline(SERVICE_ID, 1, OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME))
                .thenThrow(new OpsException(InspectionCatalogOpsError.VERSION_CONFLICT));
        when(service.changeCategoryStatus("POWER", "INACTIVE", 0, OpsTestSupport.OPERATOR_ID,
                OpsTestSupport.OPERATOR_NAME)).thenThrow(new OpsException(InspectionCatalogOpsError.CATEGORY_IN_USE));
        when(service.serviceChanges(SERVICE_ID)).thenThrow(new OpsException(InspectionCatalogOpsError.NOT_FOUND));
        when(service.updateService(eq(SERVICE_ID), any(), eq(5), eq(OpsTestSupport.OPERATOR_ID),
                eq(OpsTestSupport.OPERATOR_NAME))).thenThrow(new IllegalArgumentException("title is required"));

        mockMvc.perform(post("/api/v1/ops/inspection/services/" + SERVICE_ID + "/publish")
                        .principal(OpsTestSupport.operator()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS_PUBLISH_INCOMPLETE"))
                .andExpect(jsonPath("$.details.missing[0]").value("coverUrl"))
                .andExpect(jsonPath("$.details.missing[1]").value("processSteps"));
        mockMvc.perform(post("/api/v1/ops/inspection/services/" + SERVICE_ID + "/offline")
                        .principal(OpsTestSupport.operator()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_VERSION_CONFLICT"));
        mockMvc.perform(put("/api/v1/ops/inspection/categories/POWER/status").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_INSPECTION_CATEGORY_IN_USE"));
        mockMvc.perform(get("/api/v1/ops/inspection/services/" + SERVICE_ID + "/changes")
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(put("/api/v1/ops/inspection/services/" + SERVICE_ID).principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"" + PROVIDER_ID + "\",\"title\":\"\",\"cityCodes\":[\"370500\"],"
                                + "\"version\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void otherRolesAndDisabledOperatorsAreForbidden() throws Exception {
        for (var who : List.of(OpsTestSupport.support(),
                OpsTestSupport.signedIn(OpsTestSupport.OPERATOR_ID, "PROVIDER_OPERATOR", "LIVE_HOST"),
                OpsTestSupport.signedIn(UUID.randomUUID(), "PLATFORM_OPERATOR"))) {
            mockMvc.perform(get("/api/v1/ops/inspection/categories").principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
            mockMvc.perform(post("/api/v1/ops/inspection/services/" + SERVICE_ID + "/publish").principal(who)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        mockMvc.perform(get("/api/v1/ops/inspection/services").principal(OpsTestSupport.operator()))
                .andExpect(status().isOk());
        verify(service).services(null, null, null, null, 1, 20);
    }
}
