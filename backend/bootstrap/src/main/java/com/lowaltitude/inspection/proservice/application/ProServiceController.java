package com.lowaltitude.inspection.proservice.application;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 四类专业服务的目录、详情和服务商主页（F2，契约 contracts/openapi-proservice.yaml 1.0.0）。公开只读，不需要登录。
 * 必填的查询参数声明为 required = false，缺失时由读服务按参数无效处理并返回 400。
 */
@RestController
public final class ProServiceController {

    private final ProServiceReadService service;

    public ProServiceController(ProServiceReadService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/pro-services")
    public ProServicePageView services(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> deliverables,
            @RequestParam(required = false) String priceMode,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(type, cityCode, keyword, deliverables, priceMode, sort, page, size);
    }

    @GetMapping("/api/v1/pro-services/{serviceId}")
    public ProServiceDetailView detail(
            @PathVariable UUID serviceId,
            @RequestParam(required = false) String cityCode
    ) {
        return service.detail(serviceId, cityCode);
    }

    @GetMapping("/api/v1/providers/{providerId}")
    public ProviderPageView provider(
            @PathVariable UUID providerId,
            @RequestParam(required = false) String cityCode
    ) {
        return service.provider(providerId, cityCode);
    }
}
