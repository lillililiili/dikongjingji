package com.lowaltitude.inspection.application.city;

import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public ResponseEntity<?> recordSearchSelection(
            @PathVariable String cityCode,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId
    ) {
        if (service.recordSearchSelection(cityCode)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("COMMON_NOT_FOUND", "资源不存在", traceId(requestId)));
    }

    private String traceId(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
