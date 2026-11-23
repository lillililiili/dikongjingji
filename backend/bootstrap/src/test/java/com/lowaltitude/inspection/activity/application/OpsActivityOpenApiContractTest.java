package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.lowaltitude.inspection.activity.application.ops.ActivityOpsError;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsService;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.junit.jupiter.api.Test;

/** 契约 contracts/openapi-ops-activity.yaml 和 OpsActivityController 逐项对照（S3-1 第三部分）。 */
class OpsActivityOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-activity.yaml",
            OpsActivityController.class)
            .response("OpsActivityPage", ActivityOpsViews.OpsActivityPageView.class)
            .response("OpsActivitySummary", ActivityOpsViews.OpsActivitySummaryView.class)
            .response("OpsActivity", ActivityOpsViews.OpsActivityView.class)
            .response("OpsActivitySession", ActivityOpsViews.OpsActivitySessionView.class)
            .response("OpsOrganizationRef", ActivityOpsViews.OpsOrganizationRefView.class)
            .response("OpsActivityDeleted", ActivityOpsViews.OpsActivityDeletedView.class)
            .response("OpsChange", OpsChangeView.class)
            .request("OpsActivityRequest", OpsActivityController.ActivityRequest.class,
                    "type", "title", "organizerId", "cityCode", "venueName", "venueAddress", "priceMode")
            .request("OpsActivitySessionRequest", OpsActivityController.SessionRequest.class,
                    "startsAt", "enrollmentClosesAt", "capacity")
            .request("OpsVersionRequest", OpsActivityController.VersionRequest.class, "version")
            .requestEnumField("OpsActivityRequest.type", "ActivityType")
            .requestEnumField("OpsActivityRequest.priceMode", "ActivityPriceMode")
            .enumeration("ActivityType", ActivityType.class)
            .enumeration("ActivityPriceMode", ActivityPriceMode.class)
            .enumeration("OpsActivityStatus", ActivityOpsViews.OpsActivityStatus.class)
            .enumeration("OpsSessionSaleStatus", ActivityOpsViews.OpsSessionSaleStatus.class)
            .other("ApiError", "OpsActivityErrorCode", "OpsActivityPublishField");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.1.0");
        checker.verifyOpsErrors("OpsActivityErrorCode", ActivityOpsError.values());
    }

    @Test
    void publishFieldsAndLimitsAreTheOnesTheServiceUses() {
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(checker.schemas()
                .get("OpsActivityPublishField")).get("enum"))).containsExactly(ActivityOpsService.FIELD_COVER,
                ActivityOpsService.FIELD_NOTICE, ActivityOpsService.FIELD_REFUND, ActivityOpsService.FIELD_ORGANIZER,
                ActivityOpsService.FIELD_SESSIONS);
        var request = OpsContractChecker.map(OpsContractChecker.map(checker.schemas()
                .get("OpsActivityRequest")).get("properties"));
        assertThat(OpsContractChecker.map(request.get("title")).get("maxLength")).isEqualTo(ActivityOpsService.MAX_TITLE);
        assertThat(OpsContractChecker.map(request.get("enrollmentNotice")).get("maxLength"))
                .isEqualTo(ActivityOpsService.MAX_ENROLLMENT_NOTICE);
        assertThat(OpsContractChecker.map(request.get("galleryUrls")).get("maxItems"))
                .isEqualTo(ActivityOpsService.MAX_GALLERY);
        assertThat(OpsContractChecker.map(request.get("priceAmountMinor")).get("maximum"))
                .isEqualTo((int) ActivityOpsService.MAX_PRICE_MINOR);
        var session = OpsContractChecker.map(OpsContractChecker.map(checker.schemas()
                .get("OpsActivitySessionRequest")).get("properties"));
        assertThat(OpsContractChecker.map(session.get("capacity")).get("maximum"))
                .isEqualTo(ActivityOpsService.MAX_CAPACITY);
        assertThat(checker.text()).contains("maximum: " + ActivityOpsService.MAX_PAGE_SIZE)
                .contains("maxItems: " + ActivityOpsService.CHANGE_HISTORY_LIMIT)
                .contains("最多 " + ActivityOpsService.MAX_SESSIONS + " 个场次");
        var change = OpsContractChecker.map(OpsContractChecker.map(checker.schemas().get("OpsChange"))
                .get("properties"));
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(change.get("action")).get("enum")))
                .containsExactlyElementsOf(List.of("CREATE", "UPDATE", "PUBLISH", "OFFLINE", "DELETE", "STOP_SALE",
                        "RESUME_SALE"));
    }
}
