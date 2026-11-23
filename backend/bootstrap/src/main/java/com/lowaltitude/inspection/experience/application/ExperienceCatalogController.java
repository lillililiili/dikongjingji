package com.lowaltitude.inspection.experience.application;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开只读接口，不需要登录（契约 contracts/openapi-experience.yaml）。
 * 必填的查询参数声明为 required = false，缺失时由读服务按参数无效处理并返回 400，而不是落到通用的 500。
 */
@RestController
public final class ExperienceCatalogController {

    private final ExperienceCatalogReadService service;

    public ExperienceCatalogController(ExperienceCatalogReadService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/experiences")
    public ExperienceItemPageView experiences(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listExperienceItems(type, page, size);
    }

    /** S5-4：仿真页、试用页读一个项目的模板和试用时长。 */
    @GetMapping("/api/v1/experiences/{experienceId}")
    public ExperienceItemDetailView experience(@PathVariable UUID experienceId) {
        return service.findExperienceItem(experienceId);
    }

    @GetMapping("/api/v1/venues")
    public VenuePageView venues(
            @RequestParam(required = false) String cityCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listVenues(trim(cityCode), page, size);
    }

    @GetMapping("/api/v1/venues/{venueId}")
    public VenueDetailView venue(
            @PathVariable UUID venueId,
            @RequestParam(required = false) String cityCode
    ) {
        return service.findVenue(venueId, trim(cityCode));
    }

    @GetMapping("/api/v1/venues/{venueId}/sessions")
    public VenueSessionListView sessions(
            @PathVariable UUID venueId,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String date
    ) {
        return service.listSessions(venueId, trim(cityCode), projectId, date);
    }

    @GetMapping("/api/v1/venues/{venueId}/devices")
    public VenueDevicePageView devices(
            @PathVariable UUID venueId,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listDevices(venueId, trim(cityCode), category, page, size);
    }

    /** R6-2 先体验：商品详情读这件商品的线上体验和本城能看到它的场馆。 */
    @GetMapping("/api/v1/experience/product-experiences")
    public ProductExperiencesView productExperiences(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String cityCode
    ) {
        return service.findProductExperiences(productId, trim(cityCode));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
