package com.lowaltitude.inspection.ops.dashboard;

import java.time.Clock;

import com.lowaltitude.inspection.activity.application.ActivityOpsStatsService;
import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.application.reservation.InspectionOpsStatsService;
import com.lowaltitude.inspection.course.application.CourseOpsStatsService;
import com.lowaltitude.inspection.experience.application.ExperienceOpsStatsService;
import com.lowaltitude.inspection.identity.application.account.OpsIdentityStatsService;
import com.lowaltitude.inspection.mall.application.MallOpsStatsService;
import com.lowaltitude.inspection.proservice.application.ProServiceOpsStatsService;
import com.lowaltitude.inspection.support.application.SupportOpsStatsService;
import com.lowaltitude.inspection.trade.application.OpsTradeStatsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class OpsDashboardConfiguration {

    @Bean
    OpsDashboardService opsDashboardService(OpsIdentityStatsService identity, OpsTradeStatsService trade,
                                            MallOpsStatsService mall, InspectionOpsStatsService inspection,
                                            ProServiceOpsStatsService proService, ActivityOpsStatsService activity,
                                            ExperienceOpsStatsService experience, CourseOpsStatsService course,
                                            SupportOpsStatsService support, CityOperationsService cities) {
        return new OpsDashboardService(identity, trade, mall, inspection, proService, activity, experience, course,
                support, cities, Clock.systemUTC());
    }
}
