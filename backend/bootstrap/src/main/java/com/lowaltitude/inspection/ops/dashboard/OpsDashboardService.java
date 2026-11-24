package com.lowaltitude.inspection.ops.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.lowaltitude.inspection.activity.application.ActivityOpsStatsService;
import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.application.reservation.InspectionOpsStatsService;
import com.lowaltitude.inspection.course.application.CourseOpsStatsService;
import com.lowaltitude.inspection.experience.application.ExperienceOpsStatsService;
import com.lowaltitude.inspection.identity.application.account.OpsIdentityStatsService;
import com.lowaltitude.inspection.identity.application.account.OpsStatsExclusionService;
import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import com.lowaltitude.inspection.mall.application.MallOpsStatsService;
import com.lowaltitude.inspection.proservice.application.ProServiceOpsStatsService;
import com.lowaltitude.inspection.support.application.SupportOpsStatsService;
import com.lowaltitude.inspection.trade.application.OpsOrderLookupService.OpsOrderSource;
import com.lowaltitude.inspection.trade.application.OpsTradeStatsService;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml 1.1.0）。各模块只出自己表里的汇总数，这里按统计期
 * （北京时间自然日，到今天为止）算好区间交给它们，再拼成一张：每日序列补齐没有数的日子，城市编码换成城市名。
 * 配置里的测试账号、测试机构先由身份模块解析成 ID，再交给各模块排除；待处理和开通城市不排除。
 */
public class OpsDashboardService {

    public static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final OpsStatsExclusionService exclusions;
    private final OpsIdentityStatsService identity;
    private final OpsTradeStatsService trade;
    private final MallOpsStatsService mall;
    private final InspectionOpsStatsService inspection;
    private final ProServiceOpsStatsService proService;
    private final ActivityOpsStatsService activity;
    private final ExperienceOpsStatsService experience;
    private final CourseOpsStatsService course;
    private final SupportOpsStatsService support;
    private final CityOperationsService cities;
    private final Clock clock;

    public OpsDashboardService(OpsStatsExclusionService exclusions, OpsIdentityStatsService identity,
                               OpsTradeStatsService trade, MallOpsStatsService mall,
                               InspectionOpsStatsService inspection, ProServiceOpsStatsService proService,
                               ActivityOpsStatsService activity, ExperienceOpsStatsService experience,
                               CourseOpsStatsService course, SupportOpsStatsService support,
                               CityOperationsService cities, Clock clock) {
        this.exclusions = exclusions;
        this.identity = identity;
        this.trade = trade;
        this.mall = mall;
        this.inspection = inspection;
        this.proService = proService;
        this.activity = activity;
        this.experience = experience;
        this.course = course;
        this.support = support;
        this.cities = cities;
        this.clock = clock;
    }

    public record CustomersView(long total, long newInPeriod, long newPreviousPeriod, long activeInPeriod,
                                long enterprises) {
    }

    public record OrganizationsView(long providers, long qualificationReviewed, long newInPeriod,
                                    long collectingMerchants, long sellingMerchants) {
    }

    public record TradeView(long netPaidMinor, long paidMinor, long refundedMinor, long paidOrders,
                            long payingCustomers, long averageOrderMinor, long previousNetPaidMinor,
                            long previousPaidOrders, long previousPayingCustomers, long lifetimeNetPaidMinor) {
    }

    public record FunnelView(long submitted, long paid, long kept) {
    }

    public record EngagementView(long checkinPeople, long activityEnrollments, long venueBookings,
                                 long inspectionReservations) {
    }

    public record SupplyView(long openCities, long products, long inspectionServices, long proServices,
                             long activities, long venues, long courses) {
    }

    public record DayView(LocalDate date, long netPaidMinor, long paidOrders, long newCustomers) {
    }

    public record SourceShareView(OpsOrderSource source, long netPaidMinor, long paidOrders) {
    }

    public record CityShareView(String cityCode, String cityName, long netPaidMinor, long paidOrders) {
    }

    public record TodoView(long qualificationReviews, long merchantProfileReviews, long providerProfileReviews,
                           long pendingFeedback, long conversationsAwaitingReply, long refundFailedOrders) {
    }

    public record OpsDashboardView(OffsetDateTime generatedAt, int days, LocalDate fromDate, LocalDate toDate,
                                   CustomersView customers, OrganizationsView organizations, TradeView trade,
                                   FunnelView funnel, EngagementView engagement, SupplyView supply,
                                   List<DayView> daily, List<SourceShareView> bySource,
                                   List<CityShareView> byCity, TodoView todo) {
    }

    public OpsDashboardView dashboard(int days) {
        if (!ALLOWED_DAYS.contains(days)) {
            throw new IllegalArgumentException("days must be 7, 30 or 90");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate toDate = now.atZoneSameInstant(BEIJING).toLocalDate();
        LocalDate fromDate = toDate.minusDays(days - 1L);
        OffsetDateTime from = startOf(fromDate);
        OffsetDateTime to = startOf(toDate.plusDays(1));
        OffsetDateTime previousFrom = startOf(fromDate.minusDays(days));

        OpsStatsExclusion excluded = exclusions.resolve();
        OpsIdentityStatsService.Stats people = identity.stats(from, to, previousFrom, excluded);
        OpsTradeStatsService.Stats money = trade.stats(from, to, previousFrom, excluded);
        MallOpsStatsService.Stats shop = mall.stats(excluded);
        InspectionOpsStatsService.Stats inspections = inspection.stats(from, to, excluded);
        ProServiceOpsStatsService.Stats pro = proService.stats(excluded);
        ActivityOpsStatsService.Stats activities = activity.stats(from, to, excluded);
        ExperienceOpsStatsService.Stats venues = experience.stats(from, to, excluded);
        CourseOpsStatsService.Stats courses = course.stats(excluded);
        SupportOpsStatsService.Stats desk = support.stats();

        OpsTradeStatsService.Period current = money.current();
        OpsTradeStatsService.Period previous = money.previous();
        return new OpsDashboardView(
                now.withOffsetSameInstant(ZoneOffset.UTC),
                days,
                fromDate,
                toDate,
                new CustomersView(people.customers(), people.newCustomers(), people.newCustomersPrevious(),
                        people.activeCustomers(), people.enterprises()),
                new OrganizationsView(people.providers(), people.qualificationReviewedProviders(),
                        people.newProviders(), money.collectingMerchants(), shop.sellingMerchants()),
                new TradeView(current.netPaidMinor(), current.paidMinor(), current.refundedMinor(),
                        current.paidOrders(), current.payingCustomers(),
                        current.paidOrders() == 0 ? 0 : current.netPaidMinor() / current.paidOrders(),
                        previous.netPaidMinor(), previous.paidOrders(), previous.payingCustomers(),
                        money.lifetimeNetPaidMinor()),
                new FunnelView(money.funnel().submitted(), money.funnel().paid(), money.funnel().kept()),
                new EngagementView(activities.checkinPeople() + venues.checkinPeople(), activities.enrollments(),
                        venues.bookings(), inspections.reservations()),
                new SupplyView(inspections.openCities(), shop.products(), inspections.services(), pro.services(),
                        activities.activities(), venues.venues(), courses.courses()),
                daily(fromDate, toDate, money.daily(), people.dailyNewCustomers()),
                money.bySource().stream()
                        .map(share -> new SourceShareView(OpsOrderSource.valueOf(share.code()),
                                share.netPaidMinor(), share.paidOrders()))
                        .toList(),
                money.byCity().stream()
                        .map(share -> new CityShareView(share.code(),
                                cities.cityName(share.code()).orElse(share.code()),
                                share.netPaidMinor(), share.paidOrders()))
                        .toList(),
                new TodoView(people.pendingQualifications(), shop.pendingMerchantProfiles(),
                        pro.pendingProviderProfiles(), desk.pendingFeedback(), desk.conversationsAwaitingReply(),
                        money.refundFailedOrders()));
    }

    private static OffsetDateTime startOf(LocalDate date) {
        return date.atStartOfDay(BEIJING).toOffsetDateTime();
    }

    /** 统计期每天一行，没有数的日子给 0。 */
    static List<DayView> daily(LocalDate fromDate, LocalDate toDate, List<OpsTradeStatsService.Day> trade,
                               List<OpsIdentityStatsService.DayCount> newCustomers) {
        Map<LocalDate, OpsTradeStatsService.Day> tradeByDay = new HashMap<>();
        trade.forEach(day -> tradeByDay.put(day.date(), day));
        Map<LocalDate, Long> customersByDay = new HashMap<>();
        newCustomers.forEach(day -> customersByDay.put(day.date(), day.count()));
        List<DayView> days = new ArrayList<>();
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            OpsTradeStatsService.Day money = tradeByDay.get(date);
            days.add(new DayView(date, money == null ? 0 : money.netPaidMinor(),
                    money == null ? 0 : money.paidOrders(), customersByDay.getOrDefault(date, 0L)));
        }
        return days;
    }
}
