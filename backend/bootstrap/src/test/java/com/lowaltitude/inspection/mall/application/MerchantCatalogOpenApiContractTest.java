package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.lowaltitude.inspection.ops.OpsContractChecker;
import org.junit.jupiter.api.Test;

/**
 * 契约 contracts/openapi-merchant-catalog.yaml 和 MerchantCatalogController 逐项对照。形状由它 $ref 的
 * openapi-ops-catalog.yaml（OpsCatalogOpenApiContractTest）保证，这里核对路由、方法、登录要求和前缀。
 */
class MerchantCatalogOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-merchant-catalog.yaml",
            MerchantCatalogController.class);

    @Test
    void everyRouteIsDeclaredUnderTheProviderPrefixAndNeedsALogin() {
        assertThat(OpsContractChecker.map(checker.root().get("info")).get("version")).isEqualTo("1.1.0");
        Map<String, Object> paths = OpsContractChecker.map(checker.root().get("paths"));
        Set<String> declared = new TreeSet<>();
        paths.forEach((path, item) -> OpsContractChecker.map(item).forEach((method, operation) -> {
            declared.add(method.toUpperCase() + " " + path);
            assertThat(path).startsWith("/api/v1/provider/catalog/");
            assertThat(OpsContractChecker.map(operation).get("security")).as(method + " " + path)
                    .isEqualTo(List.of(Map.of("bearerAuth", List.of())));
        }));
        assertThat(declared).containsExactlyInAnyOrderElementsOf(
                checker.operations().stream().map(OpsContractChecker.Operation::key).toList());
        // 和运营端“商品与供给”一一对应：同样的路由，只换前缀；收款账户不在商家侧。删除草稿只有商家侧有。
        OpsContractChecker ops = new OpsContractChecker("openapi-ops-catalog.yaml", OpsCatalogController.class);
        Set<String> mirrored = new TreeSet<>();
        OpsContractChecker.map(ops.root().get("paths")).forEach((path, item) -> {
            if (path.startsWith("/api/v1/ops/catalog/")) {
                OpsContractChecker.map(item).keySet().forEach(method -> mirrored.add(method.toUpperCase() + " "
                        + path.replace("/api/v1/ops/catalog/", "/api/v1/provider/catalog/")));
            }
        });
        mirrored.add("DELETE /api/v1/provider/catalog/products/{productId}");
        assertThat(declared).isEqualTo(mirrored);
        assertThat(checker.text()).contains("OPS_MERCHANT_NOT_QUALIFIED").contains("owner_organization_id");
    }
}
