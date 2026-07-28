package com.lowaltitude.inspection.application.reservation;

import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/api/v1/inspection/services")
    public InspectionServicePageView search(
            @RequestParam String cityCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.search(cityCode.trim(), page, size);
    }
}
