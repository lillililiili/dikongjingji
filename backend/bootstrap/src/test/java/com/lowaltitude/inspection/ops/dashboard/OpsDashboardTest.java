package com.lowaltitude.inspection.ops.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityOpsStatsService;
import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.application.reservation.InspectionOpsStatsService;
import com.lowaltitude.inspection.course.application.CourseOpsStatsService;
import com.lowaltitude.inspection.experience.application.ExperienceOpsStatsService;
import com.lowaltitude.inspection.identity.application.account.OpsIdentityStatsService;
import com.lowaltitude.inspection.mall.application.MallOpsStatsService;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import com.lowaltitude.inspection.proservice.application.ProServiceOpsStatsService;
import com.lowaltitude.inspection.support.application.SupportOpsStatsService;
import com.lowaltitude.inspection.trade.application.OpsOrderLookupService.OpsOrderSource;
import com.lowaltitude.inspection.trade.application.OpsTradeStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/** 经营概览：统计期按北京时间自然日算，各模块的数拼对位置，每日补零，城市名找不到时用编码；只给平台角色。 */
class OpsDashboardTest {

    /** 北京时间 2026-11-23 00:30，UTC 还是 22 号。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T16:30:00Z"), ZoneOffset.UTC);

    private final OpsIdentityStatsService identity = mock(OpsIdentityStatsService.class);
    private final OpsTradeStatsService trade = mock(OpsTradeStatsService.class);
    private final MallOpsStatsService mall = mock(MallOpsStatsService.class);
    private final InspectionOpsStatsService inspection = mock(InspectionOpsStatsService.class);
    private final ProServiceOpsStatsService proService = mock(ProServiceOpsStatsService.class);
    private final ActivityOpsStatsService activity = mock(ActivityOpsStatsService.class);
    private final ExperienceOpsStatsService experience = mock(ExperienceOpsStatsService.class);
    private final CourseOpsStatsService course = mock(CourseOpsStatsService.class);
    private final SupportOpsStatsService support = mock(SupportOpsStatsService.class);
    private final CityOperationsService cities = mock(CityOperationsService.class);
    private final OpsDashboardService service = new OpsDashboardService(identity, trade, mall, inspection,
            proService, activity, experience, course, support, cities, CLOCK);

    private void stubModules() {
        when(identity.stats(any(), any(), any())).thenReturn(new OpsIdentityStatsService.Stats(120, 15, 10, 40, 3,
                List.of(new OpsIdentityStatsService.DayCount(LocalDate.parse("2026-11-21"), 4)), 9, 6, 2, 1));
        when(trade.stats(any(), any(), any())).thenReturn(new OpsTradeStatsService.Stats(
                new OpsTradeStatsService.Period(90_000, 100_000, 10_000, 4, 3),
                new OpsTradeStatsService.Period(60_000, 60_000, 0, 2, 2),
                500_000,
                List.of(new OpsTradeStatsService.Day(LocalDate.parse("2026-11-23"), 30_000, 1)),
                List.of(new OpsTradeStatsService.Share("MALL", 70_000, 3),
                        new OpsTradeStatsService.Share("VENUE", 20_000, 1)),
                List.of(new OpsTradeStatsService.Share("370500", 80_000, 3),
                        new OpsTradeStatsService.Share("999999", 10_000, 1)),
                new OpsTradeStatsService.Funnel(6, 4, 3), 7, 1));
        when(mall.stats()).thenReturn(new MallOpsStatsService.Stats(12, 5, 2));
        when(inspection.stats(any(), any())).thenReturn(new InspectionOpsStatsService.Stats(8, 1, 6));
        when(proService.stats()).thenReturn(new ProServiceOpsStatsService.Stats(4, 1));
        when(activity.stats(any(), any())).thenReturn(new ActivityOpsStatsService.Stats(5, 11, 20));
        when(experience.stats(any(), any())).thenReturn(new ExperienceOpsStatsService.Stats(2, 3, 7));
        when(course.stats()).thenReturn(new CourseOpsStatsService.Stats(5));
        when(support.stats()).thenReturn(new SupportOpsStatsService.Stats(4, 2));
        when(cities.cityName("370500")).thenReturn(Optional.of("东营"));
        when(cities.cityName("999999")).thenReturn(Optional.empty());
    }

    @Test
    void periodIsBeijingDaysUpToTodayAndThePreviousPeriodTouchesIt() {
        stubModules();

        var view = service.dashboard(7);

        assertThat(view.fromDate()).isEqualTo(LocalDate.parse("2026-11-17"));
        assertThat(view.toDate()).isEqualTo(LocalDate.parse("2026-11-23"));
        OffsetDateTime from = OffsetDateTime.parse("2026-11-17T00:00+08:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-24T00:00+08:00");
        OffsetDateTime previousFrom = OffsetDateTime.parse("2026-11-10T00:00+08:00");
        verify(identity).stats(from, to, previousFrom);
        verify(trade).stats(from, to, previousFrom);
        verify(activity).stats(from, to);
        verify(experience).stats(from, to);
        verify(inspection).stats(from, to);
    }

    @Test
    void numbersLandInTheirPlaces() {
        stubModules();

        var view = service.dashboard(7);

        assertThat(view.customers()).isEqualTo(new OpsDashboardService.CustomersView(120, 15, 10, 40, 3));
        assertThat(view.organizations()).isEqualTo(new OpsDashboardService.OrganizationsView(9, 6, 2, 7, 5));
        assertThat(view.trade()).isEqualTo(new OpsDashboardService.TradeView(90_000, 100_000, 10_000, 4, 3,
                22_500, 60_000, 2, 2, 500_000));
        assertThat(view.funnel()).isEqualTo(new OpsDashboardService.FunnelView(6, 4, 3));
        assertThat(view.engagement()).isEqualTo(new OpsDashboardService.EngagementView(27, 11, 3, 6));
        assertThat(view.supply()).isEqualTo(new OpsDashboardService.SupplyView(1, 12, 8, 4, 5, 2, 5));
        assertThat(view.todo()).isEqualTo(new OpsDashboardService.TodoView(1, 2, 1, 4, 2, 1));
        assertThat(view.bySource()).extracting(OpsDashboardService.SourceShareView::source)
                .containsExactly(OpsOrderSource.MALL, OpsOrderSource.VENUE);
        assertThat(view.byCity()).extracting(OpsDashboardService.CityShareView::cityName)
                .containsExactly("东营", "999999");
    }

    @Test
    void everyDayHasARowAndEmptyDaysAreZero() {
        stubModules();

        var daily = service.dashboard(7).daily();

        assertThat(daily).hasSize(7);
        assertThat(daily.getFirst()).isEqualTo(
                new OpsDashboardService.DayView(LocalDate.parse("2026-11-17"), 0, 0, 0));
        assertThat(daily.get(4)).isEqualTo(
                new OpsDashboardService.DayView(LocalDate.parse("2026-11-21"), 0, 0, 4));
        assertThat(daily.getLast()).isEqualTo(
                new OpsDashboardService.DayView(LocalDate.parse("2026-11-23"), 30_000, 1, 0));
    }

    @Test
    void noOrdersMeansNoAverageAndOtherPeriodsAreRejected() {
        stubModules();
        when(trade.stats(any(), any(), any())).thenReturn(new OpsTradeStatsService.Stats(
                new OpsTradeStatsService.Period(0, 0, 0, 0, 0), new OpsTradeStatsService.Period(0, 0, 0, 0, 0),
                0, List.of(), List.of(), List.of(), new OpsTradeStatsService.Funnel(0, 0, 0), 0, 0));

        assertThat(service.dashboard(30).trade().averageOrderMinor()).isZero();
        assertThat(service.dashboard(90).daily()).hasSize(90);
        assertThatThrownBy(() -> service.dashboard(14)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyPlatformStaffCanSeeIt() throws Exception {
        stubModules();
        MockMvc mvc = OpsTestSupport.mockMvc(new OpsDashboardController(service, OpsTestSupport.actors()));

        mvc.perform(get("/api/v1/ops/dashboard").principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.customers.total").value(120))
                .andExpect(jsonPath("$.bySource[0].source").value("MALL"));
        mvc.perform(get("/api/v1/ops/dashboard").param("days", "7").principal(OpsTestSupport.support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromDate").value("2026-11-17"));
        mvc.perform(get("/api/v1/ops/dashboard").param("days", "14").principal(OpsTestSupport.operator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        OpsDashboardService unused = mock(OpsDashboardService.class);
        MockMvc guarded = OpsTestSupport.mockMvc(new OpsDashboardController(unused, OpsTestSupport.actors()));
        UUID someone = UUID.fromString("11111111-1111-1111-1111-111111111111");
        guarded.perform(get("/api/v1/ops/dashboard").principal(OpsTestSupport.signedIn(someone, "CUSTOMER")))
                .andExpect(status().isForbidden());
        guarded.perform(get("/api/v1/ops/dashboard")
                        .principal(OpsTestSupport.signedIn(someone, "PROVIDER_OPERATOR")))
                .andExpect(status().isForbidden());
        verify(unused, never()).dashboard(anyInt());
    }

    @Test
    void contractMatchesTheController() {
        OpsContractChecker checker = new OpsContractChecker("openapi-ops-dashboard.yaml",
                OpsDashboardController.class)
                .response("OpsDashboard", OpsDashboardService.OpsDashboardView.class)
                .response("OpsDashboardCustomers", OpsDashboardService.CustomersView.class)
                .response("OpsDashboardOrganizations", OpsDashboardService.OrganizationsView.class)
                .response("OpsDashboardTrade", OpsDashboardService.TradeView.class)
                .response("OpsDashboardFunnel", OpsDashboardService.FunnelView.class)
                .response("OpsDashboardEngagement", OpsDashboardService.EngagementView.class)
                .response("OpsDashboardSupply", OpsDashboardService.SupplyView.class)
                .response("OpsDashboardDay", OpsDashboardService.DayView.class)
                .response("OpsDashboardSourceShare", OpsDashboardService.SourceShareView.class)
                .response("OpsDashboardCityShare", OpsDashboardService.CityShareView.class)
                .response("OpsDashboardTodo", OpsDashboardService.TodoView.class)
                .enumeration("OpsDashboardSource", OpsOrderSource.class)
                .other("ApiError");
        checker.verifyAll("1.0.0");
        assertThat(checker.text()).contains("enum: [7, 30, 90]")
                .contains("取净成交额最高的 " + OpsTradeStatsService.TOP_CITIES + " 个城市");
        assertThat(OpsDashboardService.ALLOWED_DAYS).containsExactlyInAnyOrder(7, 30, 90);
    }
}
