package com.lowaltitude.inspection.application.reservation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * S3-2 服务商工作台的巡检预约列表：要服务商操作员角色和“读取已分配巡检预约”权限，机构只取自令牌；
 * 列表和单条摘要的形状和客户订单一样。
 */
class InspectionProviderReservationControllerTest {

    private static final UUID PROVIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OPERATOR = UUID.fromString("22222222-2222-2222-2222-333333333333");
    private static final UUID RESERVATION = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final Set<String> PROVIDER_PERMISSIONS = Set.of(
            "inspection.reservation.read.assigned", "inspection.plan.publish.assigned");

    private final CustomerReservationQueryService queryService = mock(CustomerReservationQueryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new InspectionProviderReservationController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listsTheReservationsOfTheOrganizationInTheToken() throws Exception {
        when(queryService.listAssigned(PROVIDER, CustomerReservationFilter.AWAITING_PLAN, 2, 10))
                .thenReturn(new CustomerReservationPage(List.of(summary()), 2, 10, 11, false,
                        new CustomerReservationStageCounts(11, 0, 0, 0, 0)));

        mvc.perform(get("/api/v1/provider/inspection/reservations")
                        .param("stage", "AWAITING_PLAN")
                        .param("page", "2")
                        .param("size", "10")
                        .param("providerId", UUID.randomUUID().toString())
                        .principal(signedIn(Set.of("PROVIDER_OPERATOR"), PROVIDER, PROVIDER_PERMISSIONS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.items[0].reservationNo").value("IR202611230001"))
                .andExpect(jsonPath("$.items[0].stage").value("AWAITING_PLAN"))
                .andExpect(jsonPath("$.items[0].locationSummary").value("东营市东营区黄河路 1 号"))
                .andExpect(jsonPath("$.stageCounts.AWAITING_PLAN").value(11));

        verify(queryService).listAssigned(PROVIDER, CustomerReservationFilter.AWAITING_PLAN, 2, 10);
    }

    @Test
    void readsOneSummaryAndTreatsOthersAsMissing() throws Exception {
        when(queryService.readAssigned(PROVIDER, RESERVATION)).thenReturn(summary());
        UUID other = UUID.randomUUID();
        when(queryService.readAssigned(PROVIDER, other)).thenThrow(new ReservationNotFoundException());
        var operator = signedIn(Set.of("PROVIDER_OPERATOR"), PROVIDER, PROVIDER_PERMISSIONS);

        mvc.perform(get("/api/v1/provider/inspection/reservations/{id}", RESERVATION).principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(RESERVATION.toString()))
                .andExpect(jsonPath("$.quoteAmountMinor").value(120000))
                .andExpect(jsonPath("$.paymentDueNodeNo").value(1))
                .andExpect(jsonPath("$.paymentDueNodeType").value("DOWN_PAYMENT"))
                .andExpect(jsonPath("$.paymentDueAmountMinor").value(36000));
        mvc.perform(get("/api/v1/provider/inspection/reservations/{id}", other).principal(operator))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAProviderOperatorWithTheAssignedPermissionGetsIn() throws Exception {
        for (var who : List.of(
                signedIn(Set.of("CUSTOMER"), PROVIDER, Set.of("inspection.reservation.read.own")),
                signedIn(Set.of("PROVIDER_OPERATOR"), PROVIDER, Set.of("inspection.plan.publish.assigned")),
                signedIn(Set.of("LIVE_HOST"), PROVIDER, PROVIDER_PERMISSIONS),
                signedIn(Set.of("PLATFORM_OPERATOR"), PROVIDER, Set.of("inspection.reservation.read.all")))) {
            mvc.perform(get("/api/v1/provider/inspection/reservations").principal(who))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/provider/inspection/reservations/{id}", RESERVATION).principal(who))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(queryService);
    }

    @Test
    void anUnknownStageOrPageIsABadRequest() throws Exception {
        var operator = signedIn(Set.of("PROVIDER_OPERATOR"), PROVIDER, PROVIDER_PERMISSIONS);
        mvc.perform(get("/api/v1/provider/inspection/reservations").param("stage", "CANCELLED").principal(operator))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/provider/inspection/reservations").param("size", "51").principal(operator))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/provider/inspection/reservations").param("page", "0").principal(operator))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(queryService);
    }

    private static CustomerReservationSummary summary() {
        return new CustomerReservationSummary(
                RESERVATION, "IR202611230001", UUID.randomUUID(), "园区综合巡检", null, "370500",
                "东营市东营区黄河路 1 号", LocalDate.parse("2026-11-28"), Instant.parse("2026-11-23T01:00:00Z"),
                CustomerReservationStage.AWAITING_PLAN, "SUBMITTED", null, null, null, 120000L, "CNY",
                0, 0, null, null, null,
                // S4-1：工作台的“付款状态”读这几项（客户现在该付的一笔）。
                1, "DOWN_PAYMENT", null, 36000L);
    }

    private static UsernamePasswordAuthenticationToken signedIn(Set<String> roles, UUID organizationId,
                                                                Set<String> permissions) {
        return new UsernamePasswordAuthenticationToken(
                new CurrentSubject(OPERATOR, roles, organizationId, permissions, 0), "n/a");
    }
}
