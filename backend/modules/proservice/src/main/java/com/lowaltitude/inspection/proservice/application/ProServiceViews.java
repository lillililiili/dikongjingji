package com.lowaltitude.inspection.proservice.application;

import java.util.List;
import java.util.UUID;

// 对外的形状，字段与契约 contracts/openapi-proservice.yaml 逐个对应。

record ProServiceCategoryView(ProServiceCategory code, String label) {
}

/**
 * 价格：起步价有金额、单位（如“/次起”）和一句起步价含什么；按项目报价没有金额和单位，note 是“方案确认后定价”。
 */
record ProServicePriceView(ProServicePriceMode mode, Long amountMinor, String unit, String note) {
}

record ProServiceFacetView(ProServiceFacet code, String label) {
}

/** 这一类服务的筛选项（目录筛选弹层的第一组）。 */
record ProServiceFacetGroupView(String label, List<ProServiceFacetView> options) {
}

/** 目录卡片。没有授权照片时 coverUrl 为 null，App 按 category.code 画场景图标。 */
record ProServiceCardView(
        UUID serviceId,
        ProServiceType type,
        ProServiceCategoryView category,
        String title,
        String summary,
        List<String> tags,
        ProServicePriceView price,
        String coverUrl,
        boolean sample
) {
}

/**
 * 一页服务。cityOpen 为 false 表示这个城市还没有这一类服务（显示“这个城市还没有开通”，不是“没有找到”）。
 */
record ProServicePageView(
        ProServiceType type,
        String cityCode,
        String cityName,
        boolean cityOpen,
        ProServiceFacetGroupView facetGroup,
        ProServiceSort sort,
        int page,
        int size,
        long total,
        boolean hasMore,
        List<ProServiceCardView> items
) {
}

/** 一行“标题 + 说明”：价格与取消、成果格式、服务方式、说明行。 */
record ProServiceLineView(String label, String detail) {
}

record ProServiceCityView(String cityCode, String cityName) {
}

/** qualificationReviewed：平台审核过这家服务商的资质（R6-4）；为 true 时页面在资质说明的位置显示“资质已审核”。 */
record ProServiceProviderView(UUID providerId, String name, String certificationLabel, String description,
                              boolean qualificationReviewed) {
}

/**
 * 服务详情。各段为空数组时页面不显示那一段：航拍有 outputs，测绘有 outputs 和 terms（精度说明），
 * 设备维护有 serviceModes 和 terms（保修与收费边界），技术支持有 serviceModes 和 terms（响应说明）。
 * provider 在服务商资料停用时为 null；cities 是这项服务上架的城市。
 */
record ProServiceDetailView(
        UUID serviceId,
        ProServiceType type,
        ProServiceCategoryView category,
        String title,
        String summary,
        ProServicePriceView price,
        String coverUrl,
        boolean sample,
        List<String> capabilities,
        List<ProServiceLineView> priceRows,
        List<String> scope,
        List<ProServiceLineView> outputs,
        List<ProServiceLineView> serviceModes,
        List<ProServiceLineView> terms,
        List<String> steps,
        List<String> guarantees,
        ProServiceProviderView provider,
        List<ProServiceCityView> cities,
        String warrantyText
) {
}

/** 服务商主页里的一项巡检服务（进巡检详情 M03）。 */
record ProviderInspectionServiceView(
        UUID serviceId,
        String title,
        String summary,
        String categoryName,
        ProServicePriceView price
) {
}

/**
 * 服务商主页：名称、资质说明和资质是否审核过、简介，在 cityCode 这个城市能看到的巡检服务和四类服务，以及可服务的全部城市。
 */
record ProviderPageView(
        UUID providerId,
        String name,
        String certificationLabel,
        boolean qualificationReviewed,
        String description,
        String cityCode,
        List<ProviderInspectionServiceView> inspectionServices,
        List<ProServiceCardView> proServices,
        List<ProServiceCityView> cities,
        ProviderProfileService.ProfileView profile
) {
}
