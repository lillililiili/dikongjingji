package com.lowaltitude.inspection.application.city;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PlatformCityController {

    private final CityDirectoryReadService service;

    public PlatformCityController(CityDirectoryReadService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/platform/cities")
    public CityDirectoryReadService.DirectoryView directory() {
        return service.directory();
    }

    @PostMapping("/api/v1/platform/cities/{cityCode}/search-selections")
    public ResponseEntity<Void> recordSearchSelection(@PathVariable String cityCode) {
        return service.recordSearchSelection(cityCode)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
