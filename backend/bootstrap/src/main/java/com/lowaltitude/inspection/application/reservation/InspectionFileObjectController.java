package com.lowaltitude.inspection.application.reservation;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        prefix = "inspection.infrastructure.storage",
        name = "enabled",
        havingValue = "true")
@RequestMapping("/api/v1/inspection/file-objects")
public final class InspectionFileObjectController {

    private final FileObjectApplicationService service;

    public InspectionFileObjectController(FileObjectApplicationService service) {
        this.service = service;
    }

    @PostMapping("/upload-intents")
    public ResponseEntity<UploadIntentResponse> createUploadIntent(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CreateUploadIntentRequest request,
            Authentication authentication
    ) {
        UploadIntentView intent = service.createUploadIntent(
                InspectionReservationController.currentSubject(authentication),
                idempotencyKey,
                request.toCommand());
        return ResponseEntity
                .status(201)
                .body(UploadIntentResponse.from(intent));
    }

    @PostMapping("/{fileId}/complete")
    public ResponseEntity<FileObjectResponse> completeUpload(
            @PathVariable UUID fileId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CompleteUploadRequest request,
            Authentication authentication
    ) {
        FileObjectView file = service.completeUpload(
                InspectionReservationController.currentSubject(authentication),
                idempotencyKey,
                fileId,
                request.toCommand());
        return ResponseEntity.ok(FileObjectResponse.from(file));
    }

    public record CreateUploadIntentRequest(
            String purpose,
            UUID ownerId,
            String deliveryFileType,
            String originalName,
            String contentType,
            long sizeBytes,
            String sha256
    ) {

        CreateUploadIntentCommand toCommand() {
            return new CreateUploadIntentCommand(
                    purpose,
                    ownerId,
                    deliveryFileType,
                    originalName,
                    contentType,
                    sizeBytes,
                    sha256);
        }
    }

    public record CompleteUploadRequest(String etag, long sizeBytes, String sha256) {

        CompleteUploadCommand toCommand() {
            return new CompleteUploadCommand(etag, sizeBytes, sha256);
        }
    }

    public record UploadIntentResponse(
            UUID fileId,
            String method,
            URI uploadUrl,
            Map<String, String> requiredHeaders,
            Instant expiresAt
    ) {

        static UploadIntentResponse from(UploadIntentView intent) {
            return new UploadIntentResponse(
                    intent.fileId(),
                    intent.method(),
                    intent.uploadUrl(),
                    intent.requiredHeaders(),
                    intent.expiresAt());
        }
    }

    public record FileObjectResponse(
            UUID id,
            String originalName,
            String contentType,
            long sizeBytes,
            String status,
            String scanFailureReason
    ) {

        static FileObjectResponse from(FileObjectView file) {
            return new FileObjectResponse(
                    file.id(),
                    file.originalName(),
                    file.contentType(),
                    file.sizeBytes(),
                    file.status().name(),
                    file.scanFailureReason());
        }
    }
}
