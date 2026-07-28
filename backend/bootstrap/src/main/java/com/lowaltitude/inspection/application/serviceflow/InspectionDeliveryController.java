package com.lowaltitude.inspection.application.serviceflow;

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

    @PostMapping("/api/v1/provider/inspection/service-orders/{serviceOrderId}/delivery")
    public ResponseEntity<DeliveryResponse> publish(
            @PathVariable UUID serviceOrderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody PublishDeliveryRequest request,
            Authentication authentication
    ) {
        DeliveryView delivery = service.publish(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                idempotencyKey,
                InspectionPlanController.parseIfMatch(ifMatch),
                request.toCommand(),
                InspectionPlanController.requestId(requestId));
        return ResponseEntity.status(201)
                .eTag(Integer.toString(delivery.version()))
                .body(DeliveryResponse.from(delivery));
    }

    @GetMapping("/api/v1/inspection/service-orders/{serviceOrderId}/delivery")
    public ResponseEntity<DeliveryResponse> get(
            @PathVariable UUID serviceOrderId,
            Authentication authentication
    ) {
        DeliveryView delivery = service.read(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId);
        return ResponseEntity.ok()
                .eTag(Integer.toString(delivery.version()))
                .body(DeliveryResponse.from(delivery));
    }

    @PostMapping(
            "/api/v1/inspection/service-orders/{serviceOrderId}"
                    + "/delivery-files/{fileId}/download-intent")
    public ResponseEntity<DownloadIntentResponse> download(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID fileId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            Authentication authentication
    ) {
        DownloadIntentView intent = service.download(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                fileId,
                idempotencyKey);
        return ResponseEntity.status(201)
                .body(DownloadIntentResponse.from(intent));
    }

    @PostMapping(
            "/api/v1/inspection/service-orders/{serviceOrderId}"
                    + "/delivery-files/download-all-intent")
    public ResponseEntity<DownloadIntentResponse> downloadAll(
            @PathVariable UUID serviceOrderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            Authentication authentication
    ) {
        DownloadIntentView intent = service.downloadAll(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                idempotencyKey);
        return ResponseEntity.status(201)
                .body(DownloadIntentResponse.from(intent));
    }

    @PostMapping("/api/v1/inspection/service-orders/{serviceOrderId}/acceptance")
    public ResponseEntity<AcceptanceResponse> accept(
            @PathVariable UUID serviceOrderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody AcceptDeliveryRequest request,
            Authentication authentication
    ) {
        AcceptanceView acceptance = service.accept(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                idempotencyKey,
                InspectionPlanController.parseIfMatch(ifMatch),
                request.toCommand(),
                InspectionPlanController.requestId(requestId));
        return ResponseEntity.ok()
                .eTag(Integer.toString(acceptance.orderVersion()))
                .body(AcceptanceResponse.from(acceptance));
    }

    public record PublishDeliveryRequest(
            Integer towerCount,
            Long inspectedLengthMeters,
            Integer issueCount,
            List<IssueSummaryRequest> issueSummary,
            List<DeliveryFileRequest> files
    ) {
        PublishDeliveryCommand toCommand() {
            if (towerCount == null
                    || inspectedLengthMeters == null
                    || issueCount == null) {
                throw new DeliveryFilesInvalidException();
            }
            return new PublishDeliveryCommand(
                    towerCount,
                    inspectedLengthMeters,
                    issueCount,
                    issueSummary == null
                            ? null
                            : issueSummary.stream()
                                    .map(IssueSummaryRequest::toInput)
                                    .toList(),
                    files == null
                            ? null
                            : files.stream()
                                    .map(DeliveryFileRequest::toInput)
                                    .toList());
        }
    }

    public record IssueSummaryRequest(
            String severityCode,
            String severityName,
            Integer count
    ) {
        IssueSummaryInput toInput() {
            if (count == null) {
                throw new DeliveryFilesInvalidException();
            }
            return new IssueSummaryInput(severityCode, severityName, count);
        }
    }

    public record DeliveryFileRequest(
            UUID fileId,
            DeliveryFileType type,
            String displayName,
            Integer pointCount
    ) {
        DeliveryFileInput toInput() {
            return new DeliveryFileInput(fileId, type, displayName, pointCount);
        }
    }

    public record AcceptDeliveryRequest(boolean confirmed, String note) {
        AcceptDeliveryCommand toCommand() {
            return new AcceptDeliveryCommand(confirmed, note);
        }
    }

    public record IssueSummaryResponse(
            String severityCode,
            String severityName,
            int count
    ) {
        static IssueSummaryResponse from(IssueSummary summary) {
            return new IssueSummaryResponse(
                    summary.severityCode(),
                    summary.severityName(),
                    summary.count());
        }
    }

    public record DeliveryFileResponse(
            UUID fileId,
            String type,
            String displayName,
            Integer pointCount,
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
                    file.contentType(),
                    file.sizeBytes(),
                    file.status().name());
        }
    }

    public record DeliveryResponse(
            UUID id,
            UUID serviceOrderId,
            String status,
            int towerCount,
            long inspectedLengthMeters,
            int issueCount,
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
                    delivery.status().name(),
                    delivery.towerCount(),
                    delivery.inspectedLengthMeters(),
                    delivery.issueCount(),
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
            UUID deliveryId,
            UUID acceptedBy,
            Instant acceptedAt,
            String orderStatus
    ) {
        static AcceptanceResponse from(AcceptanceView acceptance) {
            return new AcceptanceResponse(
                    acceptance.id(),
                    acceptance.serviceOrderId(),
                    acceptance.deliveryId(),
                    acceptance.acceptedBy(),
                    acceptance.acceptedAt(),
                    acceptance.orderStatus().name());
        }
    }
}
