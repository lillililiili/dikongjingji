package com.lowaltitude.inspection.activity.application;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开只读接口，不需要登录（契约 contracts/openapi-activity.yaml）。
 * cityCode 等必填项声明为 required = false，缺失时由读服务按参数无效处理并返回 400，而不是落到通用的 500。
 */
@RestController
public final class ActivityCatalogController {

    private final ActivityCatalogReadService service;

    public ActivityCatalogController(ActivityCatalogReadService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/activities")
    public ActivityPageView search(
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "false") boolean enrolling,
            @RequestParam(defaultValue = "false") boolean free,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.search(trim(cityCode), type, date, enrolling, free, page, size);
    }

    @GetMapping("/api/v1/activities/session-dates")
    public ActivitySessionDatesView sessionDates(
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        return service.findSessionDates(trim(cityCode), type, from, to);
    }

    @GetMapping("/api/v1/activities/{activityId}")
    public ActivityDetailView detail(
            @PathVariable UUID activityId,
            @RequestParam(required = false) String cityCode
    ) {
        return service.findDetail(activityId, trim(cityCode));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
