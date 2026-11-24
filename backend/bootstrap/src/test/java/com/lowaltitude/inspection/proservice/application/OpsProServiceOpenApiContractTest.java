package com.lowaltitude.inspection.proservice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.junit.jupiter.api.Test;

/** 契约 contracts/openapi-ops-proservice.yaml 和 OpsProServiceController 逐项对照（S3-1 第三部分）。 */
class OpsProServiceOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-proservice.yaml",
            OpsProServiceController.class, OpsProviderProfileController.class)
            .response("OpsProviderProfileRow", ProviderProfileService.OpsRowView.class)
            .response("OpsProviderProfilePage", ProviderProfileService.OpsPageView.class)
            .response("OpsProviderProfile", ProviderProfileService.OpsDetailView.class)
            .response("OpsProviderProfileContent", ProviderProfileService.ProfileView.class)
            .request("OpsProviderProfileReviewRequest", OpsProviderProfileController.ReviewRequest.class,
                    "decision", "version")
            .enumeration("OpsProviderProfileStatus", ProviderProfileService.OpsStatus.class)
            .response("OpsProServiceRow", ProServiceOpsService.RowView.class)
            .response("OpsProServicePage", ProServiceOpsService.PageView.class)
            .response("OpsProService", ProServiceOpsService.DetailView.class)
            .response("OpsProServiceCity", ProServiceOpsService.CityView.class)
            .response("OpsProServiceLine", ProServiceOpsService.LineView.class)
            .response("OpsChange", OpsChangeView.class)
            .request("OpsVersionRequest", OpsProServiceController.VersionRequest.class, "version")
            .request("OpsProServiceLineRequest", OpsProServiceController.LineRequest.class, "label", "detail")
            .request("OpsProServiceRequest", OpsProServiceController.ServiceRequest.class,
                    "providerId", "type", "categoryCode", "title", "summary", "priceMode")
            .requestEnumField("OpsProServiceRequest.type", "ProServiceType")
            .requestEnumField("OpsProServiceRequest.categoryCode", "ProServiceCategory")
            .requestEnumField("OpsProServiceRequest.priceMode", "ProServicePriceMode")
            .enumeration("OpsProServiceStatus", ProServiceOpsService.Status.class)
            .enumeration("ProServiceType", ProServiceType.class)
            .enumeration("ProServiceCategory", ProServiceCategory.class)
            .enumeration("ProServicePriceMode", ProServicePriceMode.class)
            .enumeration("ProServiceFacet", ProServiceFacet.class)
            .other("ApiError", "OpsProServiceErrorCode", "OpsProServicePublishField");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.2.0");
    }

    @Test
    void errorCodesAndPublishFieldsAreTheOnesTheServiceUses() {
        checker.verifyOpsErrors("OpsProServiceErrorCode", ProServiceOpsError.values());
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(checker.schemas()
                .get("OpsProServicePublishField")).get("enum")))
                .containsExactlyElementsOf(ProServiceOpsService.PUBLISH_FIELDS);
    }

    @Test
    void limitsAreTheOnesTheServiceEnforces() {
        assertThat(checker.text())
                .contains("maximum: " + ProServiceOpsService.MAX_PAGE_SIZE)
                .contains("maxLength: " + ProServiceOpsService.MAX_TITLE)
                .contains("maxLength: " + ProServiceOpsService.MAX_SUMMARY)
                .contains("maxLength: " + ProServiceOpsService.MAX_PRICE_UNIT)
                .contains("maxLength: " + ProServiceOpsService.MAX_DETAIL)
                .contains("maxItems: " + ProServiceOpsService.MAX_ITEMS)
                .contains("maxItems: " + ProServiceOpsService.MAX_CITIES)
                .contains("maxItems: " + ProServiceOpsService.CHANGE_HISTORY_LIMIT)
                .contains("maximum: " + ProServiceOpsService.MAX_AMOUNT_MINOR);
    }
}
