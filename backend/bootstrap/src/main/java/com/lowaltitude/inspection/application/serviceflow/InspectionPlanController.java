package com.lowaltitude.inspection.application.serviceflow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class InspectionPlanController {

    private final PlanApplicationService service;

    public InspectionPlanController(PlanApplicationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/provider/inspection/reservations/{reservationId}/plans")
    public ResponseEntity<ServicePlanResponse> publish(
            @PathVariable UUID reservationId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody PublishPlanRequest request,
            Authentication authentication
    ) {
        ServicePlanView plan = service.publish(
                currentSubject(authentication),
                reservationId,
                idempotencyKey,
                parseIfMatch(ifMatch),
                request.toCommand(),
                requestId(requestId));
        return ResponseEntity.status(201)
                .eTag(Integer.toString(plan.version()))
                .body(ServicePlanResponse.from(plan));
    }

    @GetMapping("/api/v1/inspection/plans/{planId}")
    public ResponseEntity<ServicePlanResponse> get(
            @PathVariable UUID planId,
            Authentication authentication
    ) {
        ServicePlanView plan = service.read(currentSubject(authentication), planId);
        return ResponseEntity.ok()
                .eTag(Integer.toString(plan.version()))
                .body(ServicePlanResponse.from(plan));
    }

    @PostMapping("/api/v1/inspection/plans/{planId}/confirm")
    public ResponseEntity<ServiceOrderResponse> confirm(
            @PathVariable UUID planId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody ConfirmPlanRequest request,
            Authentication authentication
    ) {
        ServiceOrderView order = service.confirm(
                currentSubject(authentication),
                planId,
                idempotencyKey,
                parseIfMatch(ifMatch),
                request.toCommand(),
                requestId(requestId));
        return ResponseEntity.ok()
                .eTag(Integer.toString(order.version()))
                .body(ServiceOrderResponse.from(order));
    }

    static int parseIfMatch(String value) {
        if (value == null || value.isBlank() || value.startsWith("W/")) {
            throw new IllegalArgumentException("If-Match must be a strong numeric ETag");
        }
        String normalized = value.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"")
                && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (!normalized.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("If-Match must be a strong numeric ETag");
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "If-Match must be a strong numeric ETag",
                    exception);
        }
    }

    static String requestId(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    static CurrentSubject currentSubject(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentSubject current) {
            return current;
        }
        if (!(principal instanceof Jwt jwt)) {
            throw new AccessDeniedException("authenticated subject is invalid");
        }
        try {
            UUID subjectId = UUID.fromString(requiredClaim(jwt, "subjectId"));
            String organizationClaim = requiredClaim(jwt, "organizationId");
            UUID organizationId = UUID.fromString(organizationClaim);
            List<String> roles = requiredStringListClaim(jwt, "roles");
            List<String> permissions = requiredStringListClaim(jwt, "permissions");
            Object tokenVersionClaim = jwt.getClaims().get("tokenVersion");
            if (!(tokenVersionClaim instanceof Number tokenVersion)
                    || tokenVersion.intValue() < 0) {
                throw new IllegalArgumentException("tokenVersion is invalid");
            }
            return new CurrentSubject(
                    subjectId,
                    Set.copyOf(roles),
                    organizationId,
                    Set.copyOf(permissions),
                    tokenVersion.intValue());
        } catch (RuntimeException exception) {
            throw new AccessDeniedException(
                    "authenticated subject claims are invalid",
                    exception);
        }
    }

    private static String requiredClaim(Jwt jwt, String claimName) {
        String value = jwt.getClaimAsString(claimName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(claimName + " is missing");
        }
        return value;
    }

    private static List<String> requiredStringListClaim(Jwt jwt, String claimName) {
        List<String> values = jwt.getClaimAsStringList(claimName);
        if (values == null) {
            throw new IllegalArgumentException(claimName + " is missing");
        }
        return values;
    }

    public record PublishPlanRequest(
            UUID providerId,
            Instant validUntil,
            LocalDate serviceDate,
            String summary,
            List<PlanItemRequest> planItems,
            List<DeliverableRequest> deliverables,
            String currency
    ) {
        PublishPlanCommand toCommand() {
            return new PublishPlanCommand(
                    providerId,
                    validUntil,
                    serviceDate,
                    summary,
                    planItems == null
                            ? null
                            : planItems.stream().map(PlanItemRequest::toInput).toList(),
                    deliverables == null
                            ? null
                            : deliverables.stream().map(DeliverableRequest::toInput).toList(),
                    currency);
        }
    }

    public record PlanItemRequest(
            String code,
            String name,
            int quantity,
            String unit,
            long unitPriceMinor
    ) {
        PlanItemInput toInput() {
            return new PlanItemInput(code, name, quantity, unit, unitPriceMinor);
        }
    }

    public record DeliverableRequest(String code, String name) {
        DeliverableInput toInput() {
            return new DeliverableInput(code, name);
        }
    }

    public record ConfirmPlanRequest(
            String agreementVersion,
            boolean agreementAccepted
    ) {
        ConfirmPlanCommand toCommand() {
            return new ConfirmPlanCommand(agreementVersion, agreementAccepted);
        }
    }

    public record PlanItemResponse(
            String code,
            String name,
            int quantity,
            String unit,
            long unitPriceMinor,
            long lineAmountMinor
    ) {
        static PlanItemResponse from(PlanItem item) {
            return new PlanItemResponse(
                    item.code(),
                    item.name(),
                    item.quantity(),
                    item.unit(),
                    item.unitPriceMinor(),
                    item.lineAmountMinor());
        }
    }

    public record CodeLabelResponse(String code, String label) {
        static CodeLabelResponse from(CodeLabel value) {
            return new CodeLabelResponse(value.code(), value.label());
        }
    }

    public record ServicePlanResponse(
            UUID id,
            String planNo,
            UUID reservationId,
            UUID providerId,
            String status,
            int planVersion,
            Instant validUntil,
            LocalDate serviceDate,
            String summary,
            List<PlanItemResponse> planItems,
            List<CodeLabelResponse> deliverables,
            long totalAmountMinor,
            String currency,
            Instant publishedAt,
            Instant confirmedAt,
            UUID serviceOrderId,
            int version
    ) {
        static ServicePlanResponse from(ServicePlanView plan) {
            return new ServicePlanResponse(
                    plan.id(),
                    plan.planNo(),
                    plan.reservationId(),
                    plan.providerId(),
                    plan.status().name(),
                    plan.planVersion(),
                    plan.validUntil(),
                    plan.serviceDate(),
                    plan.summary(),
                    plan.planItems().stream().map(PlanItemResponse::from).toList(),
                    plan.deliverables().stream().map(CodeLabelResponse::from).toList(),
                    plan.totalAmountMinor(),
                    plan.currency(),
                    plan.publishedAt(),
                    plan.confirmedAt(),
                    plan.serviceOrderId(),
                    plan.version());
        }
    }

    public record LocationResponse(
            String address,
            Double longitude,
            Double latitude
    ) {
    }

    public record ServiceSnapshotResponse(
            UUID serviceId,
            String serviceTitle,
            String serviceCoverUrl,
            String cityCode,
            LocationResponse location,
            Long lineLengthMeters,
            LocalDate serviceDate,
            List<CodeLabelResponse> inspectionItems
    ) {
        static ServiceSnapshotResponse from(ServiceSnapshot snapshot) {
            return new ServiceSnapshotResponse(
                    snapshot.serviceId(),
                    snapshot.serviceTitle(),
                    snapshot.serviceCoverUrl(),
                    snapshot.cityCode(),
                    new LocationResponse(
                            snapshot.address(),
                            snapshot.longitude(),
                            snapshot.latitude()),
                    snapshot.lineLengthMeters(),
                    snapshot.serviceDate(),
                    snapshot.inspectionItems().stream()
                            .map(CodeLabelResponse::from)
                            .toList());
        }
    }

    public record ServiceOrderResponse(
            UUID id,
            String serviceOrderNo,
            UUID reservationId,
            UUID planId,
            UUID customerId,
            UUID providerId,
            String status,
            ServiceSnapshotResponse serviceSnapshot,
            Instant plannedStartAt,
            List<OrderAssignmentResponse> assignments,
            List<OrderProgressResponse> progress,
            Instant createdAt,
            Instant deliveredAt,
            Instant acceptedAt,
            int version
    ) {
        static ServiceOrderResponse from(ServiceOrderView order) {
            return new ServiceOrderResponse(
                    order.id(),
                    order.serviceOrderNo(),
                    order.reservationId(),
                    order.planId(),
                    order.customerId(),
                    order.providerId(),
                    order.status().name(),
                    ServiceSnapshotResponse.from(order.serviceSnapshot()),
                    order.plannedStartAt(),
                    order.assignments().stream()
                            .map(OrderAssignmentResponse::from)
                            .toList(),
                    order.progress().stream()
                            .map(OrderProgressResponse::from)
                            .toList(),
                    order.createdAt(),
                    order.deliveredAt(),
                    order.acceptedAt(),
                    order.version());
        }
    }

    public record OrderAssignmentResponse(
            String type,
            UUID resourceId,
            String displayName,
            String roleLabel
    ) {
        static OrderAssignmentResponse from(OrderAssignment assignment) {
            return new OrderAssignmentResponse(
                    assignment.type().name(),
                    assignment.resourceId(),
                    assignment.displayName(),
                    assignment.roleLabel());
        }
    }

    public record OrderProgressResponse(
            UUID id,
            String eventCode,
            Instant occurredAt,
            String message
    ) {
        static OrderProgressResponse from(OrderProgressEvent progress) {
            return new OrderProgressResponse(
                    progress.id(),
                    progress.eventCode().name(),
                    progress.occurredAt(),
                    progress.message());
        }
    }
}
