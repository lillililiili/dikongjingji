package com.lowaltitude.inspection.opsimport.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 批量导入对外的视图（契约 contracts/openapi-ops-import.yaml 1.0.0），字段和契约一一对应。 */
public final class OpsImportViews {

    private OpsImportViews() {
    }

    public record OpsImportFileSpecView(String fileName, String title, boolean required, List<String> columns) {
    }

    public record OpsImportKindView(OpsImportKind kind, String title, boolean writable,
                                    List<OpsImportFileSpecView> files) {
    }

    public record OpsImportFileView(String fileName, int rowCount) {
    }

    public record OpsImportIssueView(String fileName, int line, String column, String message) {
    }

    public record OpsImportReviewItemView(String fileName, int line, String reference, String label, String value) {
    }

    public record OpsImportChangeView(OpsImportRefKind refKind, String reference, UUID targetId,
                                      OpsImportChangeType change) {
    }

    public record OpsImportCreatedAccountView(String organizationCode, String loginName, String initialPassword) {
    }

    public record OpsImportBatchView(UUID batchId, OpsImportKind kind, OpsImportStatus status, String fileSha256,
                                     List<OpsImportFileView> files, int rowCount, int issueCount,
                                     List<OpsImportIssueView> issues, List<OpsImportReviewItemView> reviewItems,
                                     List<OpsImportChangeView> changes,
                                     List<OpsImportCreatedAccountView> createdAccounts, UUID duplicateOfBatchId,
                                     String checkedByName, OffsetDateTime checkedAt, String importedByName,
                                     OffsetDateTime importedAt, OffsetDateTime retainedUntil) {
    }

    public record OpsImportBatchSummaryView(UUID batchId, OpsImportKind kind, OpsImportStatus status,
                                            List<String> fileNames, int rowCount, int issueCount, int changeCount,
                                            String checkedByName, OffsetDateTime checkedAt, String importedByName,
                                            OffsetDateTime importedAt) {
    }

    public record OpsImportBatchPageView(int page, int size, long total, boolean hasMore,
                                         List<OpsImportBatchSummaryView> items) {
    }
}
