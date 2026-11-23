package com.lowaltitude.inspection.proservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsTestSupport;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“专业服务目录”的控制器（S3-1）：只给平台运营，业务拒绝按契约给状态码。 */
class OpsProServiceControllerTest {

    private static final UUID SERVICE_ID = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ProServiceOpsService service = mock(ProServiceOpsService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new OpsProServiceController(service,
            OpsTestSupport.actors()));

    @Test
    void operatorCreatesWithItsOwnNameAndRejectionsFollowTheContract() throws Exception {
        mockMvc.perform(post("/api/v1/ops/pro-services").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"" + PROVIDER_ID + "\",\"type\":\"AERIAL\",\"categoryCode\":\"TOURISM\","
                                + "\"title\":\"文旅航拍\",\"summary\":\"景区宣传片\",\"priceMode\":\"PRICE_FROM\","
                                + "\"priceAmountMinor\":180000,\"priceUnit\":\"/次起\",\"priceNote\":\"含 1 架次\","
                                + "\"facets\":[\"VIDEO_FOOTAGE\"],\"steps\":[\"沟通需求\"],"
                                + "\"outputs\":[{\"label\":\"成片\",\"detail\":\"1080P\"}]}"))
                .andExpect(status().isCreated());
        ArgumentCaptor<ProServiceOpsService.ServiceDraft> draft =
                ArgumentCaptor.forClass(ProServiceOpsService.ServiceDraft.class);
        verify(service).create(draft.capture(), eq(OpsTestSupport.OPERATOR_ID), eq(OpsTestSupport.OPERATOR_NAME));
        assertThat(draft.getValue().priceAmountMinor()).isEqualTo(180000L);
        assertThat(draft.getValue().outputs().get(0).detail()).isEqualTo("1080P");
        assertThat(draft.getValue().facets()).containsExactly("VIDEO_FOOTAGE");

        when(service.publish(SERVICE_ID, 3, OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME))
                .thenThrow(OpsException.incomplete(ProServiceOpsError.PUBLISH_INCOMPLETE, List.of("cities")));
        mockMvc.perform(post("/api/v1/ops/pro-services/" + SERVICE_ID + "/publish").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS_PUBLISH_INCOMPLETE"))
                .andExpect(jsonPath("$.details.missing[0]").value("cities"));
        when(service.offline(SERVICE_ID, 3, OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME))
                .thenThrow(new OpsException(ProServiceOpsError.STATUS_UNCHANGED));
        mockMvc.perform(post("/api/v1/ops/pro-services/" + SERVICE_ID + "/offline").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_STATUS_UNCHANGED"));
        when(service.service(SERVICE_ID)).thenThrow(new OpsException(ProServiceOpsError.NOT_FOUND));
        mockMvc.perform(get("/api/v1/ops/pro-services/" + SERVICE_ID).principal(OpsTestSupport.operator()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    void otherRolesAndDisabledOperatorsAreForbidden() throws Exception {
        for (var who : List.of(OpsTestSupport.support(),
                OpsTestSupport.signedIn(OpsTestSupport.OPERATOR_ID, "PROVIDER_OPERATOR"),
                OpsTestSupport.signedIn(UUID.randomUUID(), "PLATFORM_OPERATOR"))) {
            mockMvc.perform(get("/api/v1/ops/pro-services").principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(service);
        mockMvc.perform(get("/api/v1/ops/pro-services?type=SURVEY&status=PUBLISHED")
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isOk());
        verify(service).services(null, "SURVEY", null, "PUBLISHED", 1, 20);
    }
}
