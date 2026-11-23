package com.lowaltitude.inspection.experience.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;

import com.lowaltitude.inspection.experience.application.ops.VenueOpsError;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsService;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.junit.jupiter.api.Test;

/** 契约 contracts/openapi-ops-venue.yaml 和 OpsVenueController 逐项对照（S3-1 第三部分）。 */
class OpsVenueOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-venue.yaml",
            OpsVenueController.class)
            .response("OpsVenueSummary", VenueOpsViews.OpsVenueSummaryView.class)
            .response("OpsVenuePage", VenueOpsViews.OpsVenuePageView.class)
            .response("OpsVenueInfoItem", VenueOpsViews.OpsVenueInfoItemView.class)
            .response("OpsVenueProject", VenueOpsViews.OpsVenueProjectView.class)
            .response("OpsVenueDevice", VenueOpsViews.OpsVenueDeviceView.class)
            .response("OpsVenueDetail", VenueOpsViews.OpsVenueDetailView.class)
            .response("OpsVenueSession", VenueOpsViews.OpsVenueSessionView.class)
            .response("OpsVenueSessionsGenerated", VenueOpsViews.OpsVenueSessionsGeneratedView.class)
            .response("OpsChange", OpsChangeView.class)
            .request("OpsVenueRequest", OpsVenueController.VenueRequest.class,
                    "operatorId", "name", "cityCode", "address")
            .request("OpsVenueInfoItemRequest", OpsVenueController.InfoItemRequest.class, "label", "content")
            .request("OpsVenueProjectRequest", OpsVenueController.ProjectRequest.class,
                    "name", "durationMinutes", "priceAmountMinor", "capacity")
            .request("OpsVenueDeviceRequest", OpsVenueController.DeviceRequest.class, "category", "name", "trialMode")
            .request("OpsVenueStatusRequest", OpsVenueController.StatusRequest.class, "status", "version")
            .request("OpsVenueTransitionRequest", OpsVenueController.TransitionRequest.class, "version")
            .request("OpsVenueGenerateSessionsRequest", OpsVenueController.GenerateSessionsRequest.class,
                    "fromDate", "toDate", "weekdays", "startTimes")
            .requestEnumField("OpsVenueDeviceRequest.category", "OpsVenueDeviceCategory")
            .requestEnumField("OpsVenueDeviceRequest.trialMode", "OpsVenueTrialMode")
            .requestEnumField("OpsVenueStatusRequest.status", "OpsVenueItemStatus")
            .enumeration("OpsVenueStatus", VenueOpsViews.VenueOpsStatus.class)
            .enumeration("OpsVenueItemStatus", VenueOpsViews.VenueItemStatus.class)
            .enumeration("OpsVenueSessionStatus", VenueOpsViews.VenueSessionOpsStatus.class)
            .enumeration("OpsVenueDeviceCategory", VenueDeviceCategory.class)
            .enumeration("OpsVenueTrialMode", VenueDeviceTrialMode.class)
            .enumeration("OpsWeekday", DayOfWeek.class)
            .other("ApiError", "OpsVenueErrorCode", "OpsVenuePublishField");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.0.0");
    }

    @Test
    void errorCodesAreTheOnesTheServiceRaises() {
        checker.verifyOpsErrors("OpsVenueErrorCode", VenueOpsError.values());
    }

    @Test
    void limitsAndPublishFieldsAreTheOnesTheServiceUses() {
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(checker.schemas().get("OpsVenuePublishField"))
                .get("enum"))).containsExactly("coverUrl", "openingHours", "projects", "sessions");
        assertThat(checker.text())
                .contains("maximum: " + VenueOpsService.MAX_PAGE_SIZE)
                .contains("maxLength: " + VenueOpsService.MAX_KEYWORD)
                .contains("maxItems: " + VenueOpsService.CHANGE_HISTORY_LIMIT)
                .contains("maxItems: " + VenueOpsService.SESSION_LIST_LIMIT)
                .contains("maxItems: " + VenueOpsService.MAX_INFO_ITEMS)
                .contains("maxItems: " + VenueOpsService.MAX_START_TIMES)
                .contains("maximum: " + VenueOpsService.MAX_SESSIONS_PER_BATCH)
                .contains("maximum: " + VenueOpsService.MAX_CHECKIN_MINUTES)
                .contains("maximum: " + VenueOpsService.MAX_PRICE_MINOR)
                .contains("minimum: " + VenueOpsService.MIN_DURATION)
                .contains("maximum: " + VenueOpsService.MAX_DURATION)
                .contains("maximum: " + VenueOpsService.MAX_CAPACITY)
                .contains("不超过 " + VenueOpsService.MAX_RANGE_DAYS + " 天");
    }
}
