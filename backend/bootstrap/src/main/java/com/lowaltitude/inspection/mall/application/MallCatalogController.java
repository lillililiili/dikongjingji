package com.lowaltitude.inspection.mall.application;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开只读接口，不需要登录（契约 contracts/openapi-mall-catalog.yaml）。
 * cityCode 声明为 required = false，缺失时由读服务按参数无效处理并返回 400，而不是落到通用的 500。
 */
@RestController
public final class MallCatalogController {

    private final MallCatalogReadService service;

    public MallCatalogController(MallCatalogReadService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/mall/products")
    public MallProductPageView products(
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listProducts(trim(cityCode), category, sort, page, size);
    }

    @GetMapping("/api/v1/mall/products/{productId}")
    public MallProductDetailView product(
            @PathVariable UUID productId,
            @RequestParam(required = false) String cityCode
    ) {
        return service.findProduct(productId, trim(cityCode));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
