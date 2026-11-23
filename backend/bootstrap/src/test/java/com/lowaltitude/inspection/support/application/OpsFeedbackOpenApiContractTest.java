package com.lowaltitude.inspection.support.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** 契约 contracts/openapi-ops-feedback.yaml 和 OpsFeedbackController 逐项对照（S3-1）。 */
class OpsFeedbackOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-feedback.yaml",
            OpsFeedbackController.class)
            .response("OpsFeedback", OpsFeedbackView.class)
            .response("OpsFeedbackPage", OpsFeedbackPageView.class)
            .response("SupportReportTarget", SupportReportTargetView.class)
            .response("SupportFeedbackReporter", SupportFeedbackReporterView.class)
            .response("SupportFeedbackHandling", SupportFeedbackHandlingView.class)
            .request("OpsFeedbackHandleRequest", OpsFeedbackController.HandleRequest.class, "note", "version")
            .enumeration("SupportFeedbackType", SupportFeedbackType.class)
            .enumeration("SupportFeedbackSource", SupportFeedbackSource.class)
            .enumeration("SupportFeedbackHandleStatus", SupportFeedbackHandleStatus.class)
            .other("ApiError", "OpsFeedbackErrorCode");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.0.0");
    }

    @Test
    void handleNoteLimitIsTheOneTheServiceEnforces() {
        var properties = OpsContractChecker.map(OpsContractChecker.map(checker.schemas()
                .get("OpsFeedbackHandleRequest")).get("properties"));
        assertThat(OpsContractChecker.map(properties.get("note")).get("maxLength"))
                .isEqualTo(SupportService.MAX_HANDLE_NOTE);
        // 页面隐藏评论、弹幕调的是内容、直播已有的受权接口，契约里写明了路径。
        assertThat(checker.text())
                .contains("POST /api/v1/content/comments/{target.id}/hide")
                .contains("POST /api/v1/platform/live/rooms/{target.parentId}/comments/{target.id}/hide");
    }

    @Test
    void errorCodesAreTheOnesTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (SupportException.Error error : SupportException.Error.values()) {
            ResponseEntity<ApiError> response = handler.handleSupport(new SupportException(error),
                    new MockHttpServletRequest());
            String code = response.getBody().code();
            if (code.startsWith("OPS_")) {
                produced.add(code);
                assertThat(response.getStatusCode().value()).isEqualTo(409);
            }
        }
        checker.verifyErrorCodes("OpsFeedbackErrorCode", produced);
    }
}
