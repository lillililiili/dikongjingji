package com.lowaltitude.inspection.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.Availability;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.CategoryStatus;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.PrimaryAction;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.PriceMode;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.ServiceMethod;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.ServiceStatus;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.junit.jupiter.api.Test;

/** 契约 contracts/openapi-ops-inspection-catalog.yaml 和 OpsInspectionCatalogController 逐项对照（S3-1 第三部分）。 */
class OpsInspectionCatalogOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-inspection-catalog.yaml",
            OpsInspectionCatalogController.class)
            .response("OpsInspectionCategory", InspectionCatalogOpsService.CategoryView.class)
            .response("OpsInspectionServiceRow", InspectionCatalogOpsService.ServiceRowView.class)
            .response("OpsInspectionServicePage", InspectionCatalogOpsService.ServicePageView.class)
            .response("OpsInspectionService", InspectionCatalogOpsService.ServiceDetailView.class)
            .response("OpsInspectionCity", InspectionCatalogOpsService.OpsCityView.class)
            .response("OpsInspectionItem", InspectionCatalogOpsService.OpsItemView.class)
            .response("OpsChange", OpsChangeView.class)
            .request("OpsInspectionCategoryCreateRequest", OpsInspectionCatalogController.CategoryCreateRequest.class,
                    "code", "name")
            .request("OpsInspectionCategoryUpdateRequest", OpsInspectionCatalogController.CategoryUpdateRequest.class,
                    "name", "version")
            .request("OpsInspectionCategoryStatusRequest", OpsInspectionCatalogController.CategoryStatusRequest.class,
                    "status", "version")
            .request("OpsVersionRequest", OpsInspectionCatalogController.VersionRequest.class, "version")
            .request("OpsInspectionItemRequest", OpsInspectionCatalogController.ItemRequest.class, "title")
            .request("OpsInspectionServiceRequest", OpsInspectionCatalogController.ServiceRequest.class,
                    "providerId", "title", "cityCodes")
            .requestEnumField("OpsInspectionCategoryStatusRequest.status", "OpsInspectionCategoryStatus")
            .requestEnumField("OpsInspectionServiceRequest.priceMode", "InspectionPriceMode")
            .requestEnumField("OpsInspectionServiceRequest.availability", "InspectionAvailability")
            .requestEnumField("OpsInspectionServiceRequest.primaryAction", "InspectionPrimaryAction")
            .enumeration("OpsInspectionServiceStatus", ServiceStatus.class)
            .enumeration("OpsInspectionCategoryStatus", CategoryStatus.class)
            .enumeration("InspectionPriceMode", PriceMode.class)
            .enumeration("InspectionAvailability", Availability.class)
            .enumeration("InspectionPrimaryAction", PrimaryAction.class)
            .enumeration("InspectionServiceMethod", ServiceMethod.class)
            .other("ApiError", "OpsInspectionCatalogErrorCode", "OpsInspectionPublishField");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.0.0");
    }

    @Test
    void errorCodesAndPublishFieldsAreTheOnesTheServiceUses() {
        checker.verifyOpsErrors("OpsInspectionCatalogErrorCode", InspectionCatalogOpsError.values());
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(checker.schemas()
                .get("OpsInspectionPublishField")).get("enum")))
                .containsExactlyElementsOf(InspectionCatalogOpsService.PUBLISH_FIELDS);
    }

    @Test
    void limitsAreTheOnesTheServiceEnforces() {
        assertThat(checker.text())
                .contains("maximum: " + InspectionCatalogOpsService.MAX_PAGE_SIZE)
                .contains("maxLength: " + InspectionCatalogOpsService.MAX_KEYWORD)
                .contains("maxItems: " + InspectionCatalogOpsService.CHANGE_HISTORY_LIMIT)
                .contains("maxLength: " + InspectionCatalogOpsService.MAX_TITLE)
                .contains("maxItems: " + InspectionCatalogOpsService.MAX_CITIES)
                .contains("maxItems: " + InspectionCatalogOpsService.MAX_BOOKING_ITEMS)
                .contains("maxItems: " + InspectionCatalogOpsService.MAX_SECTION_ITEMS)
                .contains("maximum: " + InspectionCatalogOpsService.MAX_AMOUNT_MINOR)
                .contains("maxLength: " + InspectionCatalogOpsService.MAX_CATEGORY_NAME)
                .contains("maxLength: " + InspectionCatalogOpsService.MAX_CATEGORY_SUBTITLE);
    }
}
