package com.lowaltitude.inspection.application.serviceflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class InspectionOrderController {

    private final OrderApplicationService service;

    public InspectionOrderController(OrderApplicationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/inspection/service-orders/{serviceOrderId}")
    public ResponseEntity<InspectionPlanController.ServiceOrderResponse> get(
            @PathVariable UUID serviceOrderId,
            Authentication authentication
    ) {
        ServiceOrderView order = service.read(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId);
        return response(order, 200);
    }

    @PutMapping("/api/v1/provider/inspection/service-orders/{serviceOrderId}/arrangement")
    public ResponseEntity<InspectionPlanController.ServiceOrderResponse> arrange(
            @PathVariable UUID serviceOrderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody PutArrangementRequest request,
            Authentication authentication
    ) {
        ServiceOrderView order = service.arrange(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                idempotencyKey,
                InspectionPlanController.parseIfMatch(ifMatch),
                request.toCommand(),
                InspectionPlanController.requestId(requestId));
        return response(order, 200);
    }

    @PutMapping(
            "/api/v1/provider/inspection/service-orders/{serviceOrderId}"
                    + "/visits/{visitId}/schedule")
    public ResponseEntity<InspectionPlanController.ServiceVisitResponse> schedule(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID visitId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody PutVisitScheduleRequest request,
            Authentication authentication
    ) {
        StoredServiceVisit visit = service.schedule(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                visitId,
                idempotencyKey,
                InspectionPlanController.parseIfMatch(ifMatch),
                request.toCommand(),
                InspectionPlanController.requestId(requestId));
        return visitResponse(visit, 200);
    }

    @PostMapping(
            "/api/v1/provider/inspection/service-orders/{serviceOrderId}"
                    + "/visits/{visitId}/progress-events")
    public ResponseEntity<InspectionPlanController.ServiceVisitResponse> progress(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID visitId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody AppendProgressRequest request,
            Authentication authentication
    ) {
        StoredServiceVisit visit = service.appendProgress(
                InspectionPlanController.currentSubject(authentication),
                serviceOrderId,
                visitId,
                idempotencyKey,
                InspectionPlanController.parseIfMatch(ifMatch),
                request.toCommand(),
                InspectionPlanController.requestId(requestId));
        return visitResponse(visit, 201);
    }

    private static ResponseEntity<InspectionPlanController.ServiceVisitResponse> visitResponse(
            StoredServiceVisit visit,
            int status
    ) {
        return ResponseEntity.status(status)
                .eTag(Integer.toString(visit.version()))
                .body(InspectionPlanController.ServiceVisitResponse.from(visit));
    }

    private static ResponseEntity<InspectionPlanController.ServiceOrderResponse> response(
            ServiceOrderView order,
            int status
    ) {
        return ResponseEntity.status(status)
                .eTag(Integer.toString(order.version()))
                .body(InspectionPlanController.ServiceOrderResponse.from(order));
    }

    public record PutArrangementRequest(List<AssignmentRequest> assignments) {
        PutArrangementCommand toCommand() {
            return new PutArrangementCommand(
                    InspectionPlanController.mapEntries(assignments, AssignmentRequest::toInput));
        }
    }

    public record PutVisitScheduleRequest(Instant plannedStartAt) {
        PutVisitScheduleCommand toCommand() {
            return new PutVisitScheduleCommand(plannedStartAt);
        }
    }

    public record AssignmentRequest(
            AssignmentType type,
            UUID resourceId,
            String displayName,
            String roleLabel,
            String contactMobile
    ) {
        AssignmentInput toInput() {
            return new AssignmentInput(type, resourceId, displayName, roleLabel, contactMobile);
        }
    }

    public record AppendProgressRequest(
            OrderProgressCode eventCode,
            Instant occurredAt,
            String message
    ) {
        AppendProgressCommand toCommand() {
            return new AppendProgressCommand(eventCode, occurredAt, message);
        }
    }
}
