package com.lowaltitude.inspection.application.serviceflow;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.domain.files.DeliveryFileType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class InspectionDeliveryController {

    private final DeliveryApplicationService service;

    public InspectionDeliveryController(DeliveryApplicationService service) {
        this.service = service;
    }

    @PostMapping(
            "/api/v1/provider/inspection/service-orders/{serviceOrderId}"
                    + "/visits/{visitId}/delivery")
    public ResponseEntity<DeliveryResponse> publish(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID visitId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody PublishDeliveryRequest request,
            Authentication authentication
    ) {
        DeliveryView delivery = service.publish(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                visitId,
                idempotencyKey,
                InspectionPlanController.parseIfMatch(ifMatch),
                request.toCommand(),
                InspectionPlanController.requestId(requestId));
        return ResponseEntity.status(201)
                .eTag(Integer.toString(delivery.version()))
                .body(DeliveryResponse.from(delivery));
    }

    @GetMapping("/api/v1/inspection/service-orders/{serviceOrderId}/visits/{visitId}/delivery")
    public ResponseEntity<DeliveryResponse> get(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID visitId,
            Authentication authentication
    ) {
        DeliveryView delivery = service.read(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                visitId);
        return ResponseEntity.ok()
                .eTag(Integer.toString(delivery.version()))
                .body(DeliveryResponse.from(delivery));
    }

    @PostMapping(
            "/api/v1/inspection/service-orders/{serviceOrderId}"
                    + "/visits/{visitId}/delivery-files/{fileId}/download-intent")
    public ResponseEntity<DownloadIntentResponse> download(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID visitId,
            @PathVariable UUID fileId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            Authentication authentication
    ) {
        DownloadIntentView intent = service.download(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                visitId,
                fileId,
                idempotencyKey);
        return ResponseEntity.status(201)
                .body(DownloadIntentResponse.from(intent));
    }

    @PostMapping(
            "/api/v1/inspection/service-orders/{serviceOrderId}"
                    + "/visits/{visitId}/delivery-files/download-all-intent")
    public ResponseEntity<DownloadIntentResponse> downloadAll(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID visitId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            Authentication authentication
    ) {
        DownloadIntentView intent = service.downloadAll(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                visitId,
                idempotencyKey);
        return ResponseEntity.status(201)
                .body(DownloadIntentResponse.from(intent));
    }

    @PostMapping("/api/v1/inspection/service-orders/{serviceOrderId}/visits/{visitId}/acceptance")
    public ResponseEntity<AcceptanceResponse> accept(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID visitId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody AcceptDeliveryRequest request,
            Authentication authentication
    ) {
        AcceptanceView acceptance = service.accept(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                visitId,
                idempotencyKey,
                InspectionPlanController.parseIfMatch(ifMatch),
                request.toCommand(),
                InspectionPlanController.requestId(requestId));
        return ResponseEntity.ok()
                .eTag(Integer.toString(acceptance.visitVersion()))
                .body(AcceptanceResponse.from(acceptance));
    }

    public record PublishDeliveryRequest(
            Integer towerCount,
            Long inspectedLengthMeters,
            List<DeliveryMetricPayload> metrics,
            Integer issueCount,
            IssueGroupBy issueGroupBy,
            List<IssueSummaryRequest> issueSummary,
            List<DeliveryFileRequest> files
    ) {
        PublishDeliveryCommand toCommand() {
            if (issueCount == null) {
                throw new DeliveryFilesInvalidException();
            }
            return new PublishDeliveryCommand(
                    towerCount,
                    inspectedLengthMeters,
                    metrics == null
                            ? List.of()
                            : InspectionPlanController.mapEntries(
                                    metrics,
                                    DeliveryMetricPayload::toInput),
                    issueCount,
                    issueGroupBy == null ? IssueGroupBy.SEVERITY : issueGroupBy,
                    InspectionPlanController.mapEntries(issueSummary, IssueSummaryRequest::toInput),
                    InspectionPlanController.mapEntries(files, DeliveryFileRequest::toInput));
        }
    }

    public record DeliveryMetricPayload(
            String code,
            String label,
            BigDecimal value,
            String unit
    ) {
        DeliveryMetricInput toInput() {
            return new DeliveryMetricInput(code, label, value, unit);
        }

        static DeliveryMetricPayload from(DeliveryMetric metric) {
            return new DeliveryMetricPayload(
                    metric.code(),
                    metric.label(),
                    metric.value(),
                    metric.unit());
        }
    }

    public record IssueSummaryRequest(
            String groupCode,
            String groupName,
            Integer count
    ) {
        IssueSummaryInput toInput() {
            if (count == null) {
                throw new DeliveryFilesInvalidException();
            }
            return new IssueSummaryInput(groupCode, groupName, count);
        }
    }

    public record DeliveryFileRequest(
            UUID fileId,
            DeliveryFileType type,
            String displayName,
            Integer pointCount,
            Integer itemCount
    ) {
        DeliveryFileInput toInput() {
            return new DeliveryFileInput(fileId, type, displayName, pointCount, itemCount);
        }
    }

    public record AcceptDeliveryRequest(boolean confirmed, String note) {
        AcceptDeliveryCommand toCommand() {
            return new AcceptDeliveryCommand(confirmed, note);
        }
    }

    public record IssueSummaryResponse(
            String groupCode,
            String groupName,
            int count
    ) {
        static IssueSummaryResponse from(IssueSummary summary) {
            return new IssueSummaryResponse(
                    summary.groupCode(),
                    summary.groupName(),
                    summary.count());
        }
    }

    public record DeliveryFileResponse(
            UUID fileId,
            String type,
            String displayName,
            Integer pointCount,
            Integer itemCount,
            String contentType,
            long sizeBytes,
            String status
    ) {
        static DeliveryFileResponse from(DeliveryFile file) {
            return new DeliveryFileResponse(
                    file.fileId(),
                    file.type().name(),
                    file.displayName(),
                    file.pointCount(),
                    file.itemCount(),
                    file.contentType(),
                    file.sizeBytes(),
                    file.status().name());
        }
    }

    public record DeliveryResponse(
            UUID id,
            UUID serviceOrderId,
            UUID visitId,
            String status,
            Integer towerCount,
            Long inspectedLengthMeters,
            List<DeliveryMetricPayload> metrics,
            int issueCount,
            String issueGroupBy,
            List<IssueSummaryResponse> issueSummary,
            List<DeliveryFileResponse> files,
            Instant publishedAt,
            Instant acceptedAt,
            int version
    ) {
        static DeliveryResponse from(DeliveryView delivery) {
            return new DeliveryResponse(
                    delivery.id(),
                    delivery.serviceOrderId(),
                    delivery.visitId(),
                    delivery.status().name(),
                    delivery.towerCount(),
                    delivery.inspectedLengthMeters(),
                    delivery.metrics().stream().map(DeliveryMetricPayload::from).toList(),
                    delivery.issueCount(),
                    delivery.issueGroupBy().name(),
                    delivery.issueSummary().stream()
                            .map(IssueSummaryResponse::from)
                            .toList(),
                    delivery.files().stream()
                            .map(DeliveryFileResponse::from)
                            .toList(),
                    delivery.publishedAt(),
                    delivery.acceptedAt(),
                    delivery.version());
        }
    }

    public record DownloadIntentResponse(
            String method,
            URI downloadUrl,
            String suggestedFileName,
            Instant expiresAt
    ) {
        static DownloadIntentResponse from(DownloadIntentView intent) {
            return new DownloadIntentResponse(
                    intent.method(),
                    intent.downloadUrl(),
                    intent.suggestedFileName(),
                    intent.expiresAt());
        }
    }

    public record AcceptanceResponse(
            UUID id,
            UUID serviceOrderId,
            UUID visitId,
            UUID deliveryId,
            UUID acceptedBy,
            String acceptedByName,
            Instant acceptedAt,
            String visitStatus,
            String orderStatus
    ) {
        static AcceptanceResponse from(AcceptanceView acceptance) {
            return new AcceptanceResponse(
                    acceptance.id(),
                    acceptance.serviceOrderId(),
                    acceptance.visitId(),
                    acceptance.deliveryId(),
                    acceptance.acceptedBy(),
                    acceptance.acceptedByName(),
                    acceptance.acceptedAt(),
                    acceptance.visitStatus().name(),
                    acceptance.orderStatus().name());
        }
    }
}
