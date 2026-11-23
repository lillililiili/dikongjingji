package com.lowaltitude.inspection.application.city;

/**
 * 城市的开通状态（S3-1，迁移 V012，契约 openapi-platform-city.yaml 1.1.0 与 openapi-ops-city.yaml 1.0.0）。
 * OPEN 已开通；PREPARING 筹备中（可以先浏览全国商品与线上体验）；CLOSED 未开通。只由运营端改。
 */
public enum CityServiceStatus {
    OPEN,
    PREPARING,
    CLOSED
}
