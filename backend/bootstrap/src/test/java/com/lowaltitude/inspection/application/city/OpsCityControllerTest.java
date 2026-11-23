package com.lowaltitude.inspection.application.city;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“城市开通”的控制器（S3-1）：只给平台运营，操作人取自令牌和身份资料，业务拒绝按契约给状态码。 */
class OpsCityControllerTest {

    private final CityOperationsService service = mock(CityOperationsService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new OpsCityController(service, OpsTestSupport.actors()));

    @Test
    void operatorListsAndChangesWithItsOwnName() throws Exception {
        when(service.cities("济南", "PREPARING", null, 2, 10)).thenReturn(
                new CityOperationsService.OpsCityPageView(2, 10, 11, false, List.of()));
        when(service.changeStatus("370100", "OPEN", "首批商家已入驻", 3, OpsTestSupport.OPERATOR_ID,
                OpsTestSupport.OPERATOR_NAME)).thenReturn(new CityOperationsService.OpsCityView("370100", "济南市",
                "370000", "山东省", CityServiceStatus.OPEN, OffsetDateTime.parse("2026-11-22T02:00:00Z"), 4));

        mockMvc.perform(get("/api/v1/ops/cities?keyword=济南&serviceStatus=PREPARING&page=2&size=10")
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11));
        mockMvc.perform(put("/api/v1/ops/cities/370100/service-status").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceStatus\":\"OPEN\",\"note\":\"首批商家已入驻\",\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("OPEN"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.serviceStatusChangedAt").value("2026-11-22T02:00:00Z"));
    }

    @Test
    void rejectionsFollowTheContract() throws Exception {
        when(service.changeStatus("370500", "CLOSED", "x", 0, OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME))
                .thenThrow(new CityOperationException(CityOperationException.Error.VERSION_CONFLICT));
        when(service.changeStatus("370500", "OPEN", "x", 1, OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME))
                .thenThrow(new CityOperationException(CityOperationException.Error.STATUS_UNCHANGED));
        when(service.changes("999999")).thenThrow(new CityOperationException(CityOperationException.Error.NOT_FOUND));

        mockMvc.perform(put("/api/v1/ops/cities/370500/service-status").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceStatus\":\"CLOSED\",\"note\":\"x\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_VERSION_CONFLICT"));
        mockMvc.perform(put("/api/v1/ops/cities/370500/service-status").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceStatus\":\"OPEN\",\"note\":\"x\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_CITY_STATUS_UNCHANGED"));
        mockMvc.perform(get("/api/v1/ops/cities/999999/service-status-changes").principal(OpsTestSupport.operator()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    void otherRolesAndDisabledOperatorsAreForbidden() throws Exception {
        for (var who : List.of(OpsTestSupport.support(),
                OpsTestSupport.signedIn(OpsTestSupport.OPERATOR_ID, "PROVIDER_OPERATOR", "LIVE_HOST"),
                // 角色对，但账号已经停用（没有资料）。
                OpsTestSupport.signedIn(UUID.randomUUID(), "PLATFORM_OPERATOR"))) {
            mockMvc.perform(get("/api/v1/ops/cities").principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
            mockMvc.perform(put("/api/v1/ops/cities/370500/service-status").principal(who)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"serviceStatus\":\"OPEN\",\"note\":\"x\",\"version\":1}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        mockMvc.perform(get("/api/v1/ops/cities").principal(OpsTestSupport.operator())).andExpect(status().isOk());
        verify(service).cities(null, null, null, 1, 20);
    }
}
