package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.lowaltitude.inspection.ops.OpsContractChecker;
import org.junit.jupiter.api.Test;

/**
 * 契约 contracts/openapi-mall-merchant.yaml（商家主页、工作台商家资料）和 openapi-ops-merchant-profile.yaml（运营端审核、
 * 交易条款）与控制器逐项对照（S5-2、S4-4）。
 */
class MallMerchantOpenApiContractTest {

    private final OpsContractChecker publicChecker = new OpsContractChecker("openapi-mall-merchant.yaml",
            MallMerchantController.class)
            .response("MallMerchantPage", MallMerchantPageView.class)
            .response("MallMerchantProfile", MallMerchantProfileView.class)
            .response("MallMerchantActivity", MallMerchantActivityView.class)
            .response("MallMerchantContent", MallMerchantContentView.class)
            .response("MallMerchantProfileState", MallMerchantProfileStateView.class)
            .response("MallMerchantSubmission", MallMerchantSubmissionView.class)
            .response("MallMerchantDraft", MallMerchantDraftView.class)
            .response("MediaAsset", MallMediaView.class)
            .request("MerchantProfileRequest", MallMerchantController.MerchantProfileRequest.class, "businessEntity")
            .enumeration("MallMerchantKind", MallMerchantKind.class)
            .other("ApiError");

    private final OpsContractChecker opsChecker = new OpsContractChecker("openapi-ops-merchant-profile.yaml",
            OpsMerchantProfileController.class)
            .response("MallMerchantSubmissionPage", MallMerchantSubmissionPageView.class)
            .response("MallMerchantSubmissionDetail", MallMerchantSubmissionDetailView.class)
            .response("MallMerchantSubmission", MallMerchantSubmissionView.class)
            .response("MallMerchantDraft", MallMerchantDraftView.class)
            .response("MallMerchantProfile", MallMerchantProfileView.class)
            .response("MallTradeTerms", MallTradeTermsDetailView.class)
            .response("MallOfferTerms", MallOfferTermsView.class)
            .request("MerchantProfileReviewRequest", OpsMerchantProfileController.ReviewRequest.class, "version")
            .request("ProductTermsRequest", OpsMerchantProfileController.ProductTermsRequest.class, "noReasonReturn")
            .request("OfferTermsRequest", OpsMerchantProfileController.OfferTermsRequest.class)
            .other("ApiError", "OpsMerchantProfileErrorCode");

    @Test
    void thePublicAndWorkbenchContractMatchesItsController() {
        assertThat(OpsContractChecker.map(publicChecker.root().get("info")).get("version")).isEqualTo("1.0.0");
        publicChecker.verifyParameters();
        publicChecker.verifyBodies();
        publicChecker.verifyResponseObjects();
        publicChecker.verifyRequestObjects();
        publicChecker.verifyFieldTypes();
        publicChecker.verifyEnums();
        publicChecker.verifyEverySchemaIsCovered();
        Map<String, Object> paths = OpsContractChecker.map(publicChecker.root().get("paths"));
        Set<String> declared = new TreeSet<>();
        paths.forEach((path, item) -> OpsContractChecker.map(item).keySet()
                .forEach(method -> declared.add(method.toUpperCase() + " " + path)));
        assertThat(declared).containsExactlyInAnyOrderElementsOf(
                publicChecker.operations().stream().map(OpsContractChecker.Operation::key).toList());
        // 商家主页公开；工作台要登录。
        assertThat(OpsContractChecker.map(OpsContractChecker.map(paths.get("/api/v1/mall/merchants/{merchantId}"))
                .get("get")).get("security")).isEqualTo(List.of());
        assertThat(OpsContractChecker.map(OpsContractChecker.map(paths.get("/api/v1/provider/mall/merchant-profile"))
                .get("put")).get("security")).isEqualTo(List.of(Map.of("bearerAuth", List.of())));
    }

    @Test
    void theOpsContractMatchesItsController() {
        opsChecker.verifyAll("1.0.0");
        opsChecker.verifyOpsErrors("OpsMerchantProfileErrorCode", MallMerchantError.values());
    }

    @Test
    void limitsAreTheOnesTheServiceUses() {
        assertThat(publicChecker.text()).contains("maxItems: " + MallMerchantService.PAGE_TABS_LIMIT)
                .contains("maxLength: 300").contains("maxLength: 160");
        assertThat(opsChecker.text()).contains("maximum: " + MallMerchantService.MAX_PAGE_SIZE);
    }
}
