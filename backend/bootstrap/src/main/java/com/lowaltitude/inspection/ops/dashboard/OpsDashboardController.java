package com.lowaltitude.inspection.ops.dashboard;

import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.ops.dashboard.OpsDashboardService.OpsDashboardView;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml 1.0.0）。平台运营和平台客服都能看，只有汇总数。 */
@RestController
public final class OpsDashboardController {

    private final OpsDashboardService dashboard;
    private final OpsActors actors;

    public OpsDashboardController(OpsDashboardService dashboard, OpsActors actors) {
        this.dashboard = dashboard;
        this.actors = actors;
    }

    @GetMapping("/api/v1/ops/dashboard")
    public OpsDashboardView dashboard(Authentication authentication,
                                      @RequestParam(defaultValue = "30") int days) {
        actors.staff(authentication);
        return dashboard.dashboard(days);
    }
}
