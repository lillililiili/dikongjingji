package com.lowaltitude.inspection.application.reservation;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class InspectionServiceCatalogController {

    private final InspectionServiceCatalogReadService service;

    public InspectionServiceCatalogController(
            InspectionServiceCatalogReadService service
    ) {
        this.service = service;
    }

    // Query parameters are declared optional so a missing one is answered by the read service
    // with 400 COMMON_INVALID_ARGUMENT, like every other invalid value, instead of a 500.
    @GetMapping("/api/v1/inspection/portal")
    public InspectionPortalView portal(
            @RequestParam(required = false) String cityCode
    ) {
        return service.findPortal(trimmed(cityCode));
    }

    @GetMapping("/api/v1/inspection/filters")
    public InspectionFilterOptionsView filters(
            @RequestParam(required = false) String cityCode
    ) {
        return service.findFilterOptions(trimmed(cityCode));
    }

    @GetMapping("/api/v1/inspection/services")
    public InspectionServicePageView search(
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) List<String> serviceMethods,
            @RequestParam(required = false) List<String> availability,
            @RequestParam(required = false) List<String> priceModes,
            @RequestParam(required = false) Boolean includesReport,
            @RequestParam(defaultValue = "COMPREHENSIVE") String sort,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.search(new InspectionCatalogSearch(
                trimmed(cityCode),
                keyword,
                trimmed(categoryCode),
                serviceMethods,
                availability,
                priceModes,
                includesReport,
                sort,
                latitude,
                longitude,
                page,
                size));
    }

    @GetMapping("/api/v1/inspection/services/{serviceId}")
    public InspectionServiceDetailView detail(
            @PathVariable UUID serviceId,
            @RequestParam(required = false) String cityCode
    ) {
        return service.findDetail(serviceId, trimmed(cityCode));
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
