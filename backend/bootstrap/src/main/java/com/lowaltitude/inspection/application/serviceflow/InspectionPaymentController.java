package com.lowaltitude.inspection.application.serviceflow;

import static com.lowaltitude.inspection.application.serviceflow.InspectionPlanController.currentSubject;
import static com.lowaltitude.inspection.application.serviceflow.InspectionPlanController.parseIfMatch;
import static com.lowaltitude.inspection.application.serviceflow.InspectionPlanController.requestId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 巡检付款节点、确认方案后的取消、改期和退回整改（S4-1，契约 openapi-inspection-service-flow.yaml 2.3.0）。
 */
@RestController
public final class InspectionPaymentController {

    private final InspectionPaymentService payments;
    private final VisitChangeService changes;

    public InspectionPaymentController(InspectionPaymentService payments, VisitChangeService changes) {
        this.payments = payments;
        this.changes = changes;
    }

    @PostMapping("/api/v1/inspection/service-orders/{serviceOrderId}/payment-nodes/{nodeNo}/payment")
    public PaymentLaunchResponse pay(
            @PathVariable UUID serviceOrderId,
            @PathVariable int nodeNo,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            Authentication authentication
    ) {
        return PaymentLaunchResponse.from(payments.payNode(
                currentSubject(authentication),
                serviceOrderId,
                nodeNo,
                requestId(requestId)));
    }

    @PostMapping("/api/v1/inspection/service-orders/{serviceOrderId}/cancel")
    public ResponseEntity<InspectionPlanController.ServiceOrderResponse> cancel(
            @PathVariable UUID serviceOrderId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody(required = false) CancelServiceOrderRequest request,
            Authentication authentication
    ) {
        ServiceOrderView order = payments.cancel(
                currentSubject(authentication),
                serviceOrderId,
                parseIfMatch(ifMatch),
                request == null ? null : request.reason(),
                requestId(requestId));
        return ResponseEntity.ok()
                .eTag(Integer.toString(order.version()))
                .body(InspectionPlanController.ServiceOrderResponse.from(order));
    }

    @PostMapping("/api/v1/inspection/service-orders/{serviceOrderId}/visits/{visitId}/reschedule-requests")
    public ResponseEntity<InspectionPlanController.VisitRescheduleResponse> requestReschedule(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID visitId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody RescheduleRequest request,
            Authentication authentication
    ) {
        VisitRescheduleRecord created = changes.requestReschedule(
                currentSubject(authentication),
                serviceOrderId,
                visitId,
                parseIfMatch(ifMatch),
                request == null ? null : request.requestedDate(),
                request == null ? null : request.reason(),
                requestId(requestId));
        return ResponseEntity.status(201).body(InspectionPlanController.VisitRescheduleResponse.from(created));
    }

    @PostMapping("/api/v1/provider/inspection/service-orders/{serviceOrderId}/visits/{visitId}"
            + "/reschedule-requests/{requestId}/decision")
    public InspectionPlanController.VisitRescheduleResponse decideReschedule(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID visitId,
            @PathVariable("requestId") UUID rescheduleId,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody RescheduleDecisionRequest request,
            Authentication authentication
    ) {
        if (request == null || request.decision() == null) {
            throw new PlanValidationException();
        }
        return InspectionPlanController.VisitRescheduleResponse.from(changes.decideReschedule(
                currentSubject(authentication),
                serviceOrderId,
                visitId,
                rescheduleId,
                request.decision() == RescheduleDecision.APPROVE,
                request.note(),
                requestId(requestId)));
    }

    @PostMapping("/api/v1/inspection/service-orders/{serviceOrderId}/visits/{visitId}/rework")
    public ResponseEntity<InspectionPlanController.VisitReworkResponse> rework(
            @PathVariable UUID serviceOrderId,
            @PathVariable UUID visitId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            @RequestBody ReworkRequest request,
            Authentication authentication
    ) {
        VisitReworkRecord rework = changes.returnForRework(
                currentSubject(authentication),
                serviceOrderId,
                visitId,
                parseIfMatch(ifMatch),
                request == null ? null : request.reason(),
                requestId(requestId));
        return ResponseEntity.status(201).body(InspectionPlanController.VisitReworkResponse.from(rework));
    }

    public record PaymentLaunchResponse(int nodeNo, UUID tradeOrderId, Instant payBefore, boolean paid) {
        static PaymentLaunchResponse from(PaymentLaunchView view) {
            return new PaymentLaunchResponse(view.nodeNo(), view.tradeOrderId(), view.payBefore(), view.paid());
        }
    }

    public record CancelServiceOrderRequest(String reason) {
    }

    public record RescheduleRequest(LocalDate requestedDate, String reason) {
    }

    public enum RescheduleDecision {
        APPROVE,
        REJECT
    }

    public record RescheduleDecisionRequest(RescheduleDecision decision, String note) {
    }

    public record ReworkRequest(String reason) {
    }
}
