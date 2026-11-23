package com.lowaltitude.inspection.opsimport.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportBatchPageView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportBatchSummaryView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportBatchView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportChangeView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportCreatedAccountView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportFileSpecView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportFileView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportIssueView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportKindView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportReviewItemView;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 契约 contracts/openapi-ops-import.yaml 和 OpsImportController 逐项对照（S2-3）。 */
class OpsImportOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-import.yaml",
            OpsImportController.class)
            .response("OpsImportFileSpec", OpsImportFileSpecView.class)
            .response("OpsImportKindSpec", OpsImportKindView.class)
            .response("OpsImportFile", OpsImportFileView.class)
            .response("OpsImportIssue", OpsImportIssueView.class)
            .response("OpsImportReviewItem", OpsImportReviewItemView.class)
            .response("OpsImportChange", OpsImportChangeView.class)
            .response("OpsImportCreatedAccount", OpsImportCreatedAccountView.class)
            .response("OpsImportBatch", OpsImportBatchView.class)
            .response("OpsImportBatchSummary", OpsImportBatchSummaryView.class)
            .response("OpsImportBatchPage", OpsImportBatchPageView.class)
            .enumeration("OpsImportKind", OpsImportKind.class)
            .enumeration("OpsImportStatus", OpsImportStatus.class)
            .enumeration("OpsImportRefKind", OpsImportRefKind.class)
            .enumeration("OpsImportChangeType", OpsImportChangeType.class)
            .other("ApiError", "OpsImportErrorCode");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.0.0");
    }

    @Test
    void limitsAreTheOnesTheServiceEnforces() {
        assertThat(checker.text())
                .contains("maxItems: " + OpsImportService.MAX_FILES)
                .contains("maxItems: " + OpsImportService.MAX_STORED_ISSUES)
                .contains("maximum: " + OpsImportService.MAX_PAGE_SIZE)
                .contains("最多存 " + OpsImportService.MAX_STORED_ISSUES + " 条")
                .contains("≤" + ImportValidator.MAX_ROWS_PER_FILE + " 行")
                .contains("保留 " + OpsImportService.RETENTION.toDays() + " 天");
        // 每类的标题和能不能写，和服务给的一样。
        for (OpsImportKind kind : OpsImportKind.values()) {
            assertThat(checker.text()).contains(kind.name());
        }
        assertThat(OpsImportKind.values()).filteredOn(OpsImportKind::writable)
                .containsExactly(OpsImportKind.ORGANIZATIONS, OpsImportKind.MERCHANT_ACCOUNTS, OpsImportKind.MALL);
    }

    @Test
    void errorCodesAreTheOnesTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (OpsImportException.Error error : OpsImportException.Error.values()) {
            String code = handler.handleOpsImport(new OpsImportException(error), new MockHttpServletRequest())
                    .getBody().code();
            if (code.startsWith("OPS_")) {
                produced.add(code);
            } else {
                assertThat(code).isEqualTo("COMMON_NOT_FOUND");
            }
        }
        checker.verifyErrorCodes("OpsImportErrorCode", produced);
    }
}
