package com.lowaltitude.inspection.application.city;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PlatformCityController {

    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");

    private final CityDirectoryReadService service;
    private final Optional<CitySearchSelectionRateLimiter> rateLimiter;

    public PlatformCityController(
            CityDirectoryReadService service,
            Optional<CitySearchSelectionRateLimiter> rateLimiter
    ) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/v1/platform/cities")
    public CityDirectoryReadService.DirectoryView directory() {
        return service.directory();
    }

    @PostMapping("/api/v1/platform/cities/{cityCode}/search-selections")
    public ResponseEntity<?> recordSearchSelection(
            @PathVariable String cityCode,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            HttpServletRequest request
    ) {
        if (!CITY_CODE.matcher(cityCode).matches()) {
            return ResponseEntity.badRequest()
                    .body(new ApiError(
                            "COMMON_INVALID_ARGUMENT",
                            "请求参数无效",
                            traceId(requestId)));
        }
        CitySearchSelectionRateLimiter.Decision decision = rateLimiter
                .map(limiter -> limiter.check(request.getRemoteAddr()))
                .orElseGet(CitySearchSelectionRateLimiter.Decision::unavailable);
        if (decision.status() == CitySearchSelectionRateLimiter.Status.RATE_LIMITED) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds(decision.retryAfter()))
                    .body(new ApiError(
                            "COMMON_RATE_LIMITED",
                            "请求过于频繁，请稍后重试",
                            traceId(requestId)));
        }
        if (decision.status() == CitySearchSelectionRateLimiter.Status.UNAVAILABLE) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiError(
                            "COMMON_SERVICE_UNAVAILABLE",
                            "服务暂不可用，请稍后重试",
                            traceId(requestId)));
        }
        if (service.recordSearchSelection(cityCode)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("COMMON_NOT_FOUND", "资源不存在", traceId(requestId)));
    }

    private String retryAfterSeconds(Duration retryAfter) {
        long seconds = Math.max(1L, (retryAfter.toMillis() + 999L) / 1000L);
        return Long.toString(seconds);
    }

    private String traceId(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
