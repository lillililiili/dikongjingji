package com.lowaltitude.inspection.application.serviceflow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.lowaltitude.inspection.domain.files.DeliveryFileType;
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

    @GetMapping("/api/v1/provider/inspection/reservations/{reservationId}/plans/current")
    public ResponseEntity<ServicePlanResponse> currentForProvider(
            @PathVariable UUID reservationId,
            Authentication authentication
    ) {
        ServicePlanView plan = service.currentForProvider(currentSubject(authentication), reservationId);
        return ResponseEntity.ok().eTag(Integer.toString(plan.version())).body(ServicePlanResponse.from(plan));
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

    @GetMapping("/api/v1/inspection/reservations/{reservationId}/plans")
    public List<PlanVersionSummaryResponse> versions(
            @PathVariable UUID reservationId,
            Authentication authentication
    ) {
        return service.listVersions(currentSubject(authentication), reservationId).stream()
                .map(PlanVersionSummaryResponse::from)
                .toList();
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

    /** Keeps null entries so the service rejects them as invalid input, not a server error. */
    static <T, R> List<R> mapEntries(List<T> items, Function<T, R> mapper) {
        return items == null
                ? null
                : items.stream().map(item -> item == null ? null : mapper.apply(item)).toList();
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
            List<PlanVisitRequest> visits,
            String summary,
            String scopeSummary,
            List<CodeLabelResponse> serviceMethods,
            DeliveryTermsPayload deliveryTerms,
            String changeNote,
            List<PlanItemRequest> planItems,
            List<DeliverableRequest> deliverables,
            String currency
    ) {
        PublishPlanCommand toCommand() {
            return new PublishPlanCommand(
                    providerId,
                    validUntil,
                    mapEntries(visits, PlanVisitRequest::plannedDate),
                    summary,
                    scopeSummary,
                    serviceMethods == null
                            ? List.of()
                            : mapEntries(serviceMethods, CodeLabelResponse::toValue),
                    deliveryTerms == null ? null : deliveryTerms.toValue(),
                    changeNote,
                    mapEntries(planItems, PlanItemRequest::toInput),
                    mapEntries(deliverables, DeliverableRequest::toInput),
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

    public record PlanVisitRequest(LocalDate plannedDate) {
    }

    public record DeliverableRequest(String code, String name, DeliveryFileType fileType) {
        DeliverableInput toInput() {
            return new DeliverableInput(code, name, fileType);
        }
    }

    public record DeliveryTermsPayload(Integer reportDueHours, Integer mediaRetentionDays) {
        DeliveryTerms toValue() {
            return new DeliveryTerms(reportDueHours, mediaRetentionDays);
        }

        static DeliveryTermsPayload from(DeliveryTerms terms) {
            return terms == null
                    ? null
                    : new DeliveryTermsPayload(terms.reportDueHours(), terms.mediaRetentionDays());
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

        CodeLabel toValue() {
            return new CodeLabel(code, label);
        }
    }

    public record PlanDeliverableResponse(String code, String label, String fileType) {
        static PlanDeliverableResponse from(PlanDeliverable value) {
            return new PlanDeliverableResponse(
                    value.code(),
                    value.label(),
                    value.fileType() == null ? null : value.fileType().name());
        }
    }

    public record PlanVisitResponse(int visitNo, LocalDate plannedDate) {
        static PlanVisitResponse from(PlanVisit visit) {
            return new PlanVisitResponse(visit.visitNo(), visit.plannedDate());
        }
    }

    public record PlanPaymentNodeResponse(
            int nodeNo,
            String nodeType,
            Integer visitNo,
            long amountMinor
    ) {
        static PlanPaymentNodeResponse from(PlanPaymentNode node) {
            return new PlanPaymentNodeResponse(
                    node.nodeNo(),
                    node.nodeType().name(),
                    node.visitNo(),
                    node.amountMinor());
        }
    }

    public record OrderPaymentNodeResponse(
            int nodeNo,
            String nodeType,
            Integer visitNo,
            long amountMinor,
            boolean payable,
            Instant paidAt,
            UUID tradeOrderId,
            NodeRefundResponse refund
    ) {
        static OrderPaymentNodeResponse from(OrderPaymentNodeView node) {
            return new OrderPaymentNodeResponse(
                    node.nodeNo(),
                    node.nodeType().name(),
                    node.visitNo(),
                    node.amountMinor(),
                    node.payable(),
                    node.paidAt(),
                    node.tradeOrderId(),
                    NodeRefundResponse.from(node.refund()));
        }
    }

    /** status 是交易模块的退款状态；读不到时为 null（已申请，进度未知）。 */
    public record NodeRefundResponse(UUID refundId, long amountMinor, String status, Instant requestedAt) {
        static NodeRefundResponse from(NodeRefundRecord refund) {
            return refund == null
                    ? null
                    : new NodeRefundResponse(refund.refundId(), refund.amountMinor(), refund.status(),
                            refund.requestedAt());
        }
    }

    public record PaymentRecordResponse(
            UUID attemptId,
            int nodeNo,
            String nodeType,
            Integer visitNo,
            UUID tradeOrderId,
            long amountMinor,
            String status,
            Instant payBefore,
            Instant createdAt,
            Instant paidAt,
            Instant closedAt
    ) {
        static PaymentRecordResponse from(PaymentAttemptRecord record) {
            return new PaymentRecordResponse(
                    record.id(),
                    record.nodeNo(),
                    record.nodeType().name(),
                    record.visitNo(),
                    record.tradeOrderId(),
                    record.amountMinor(),
                    record.status().name(),
                    record.payBefore(),
                    record.createdAt(),
                    record.paidAt(),
                    record.closedAt());
        }
    }

    public record CancellationResponse(Instant cancelledAt, String reason) {
        static CancellationResponse from(OrderCancellation cancellation) {
            return cancellation == null
                    ? null
                    : new CancellationResponse(cancellation.cancelledAt(), cancellation.reason());
        }
    }

    public record VisitRescheduleResponse(
            UUID id,
            UUID visitId,
            LocalDate fromDate,
            LocalDate requestedDate,
            String reason,
            String status,
            String decisionNote,
            Instant requestedAt,
            Instant decidedAt
    ) {
        static VisitRescheduleResponse from(VisitRescheduleRecord record) {
            return record == null
                    ? null
                    : new VisitRescheduleResponse(
                            record.id(),
                            record.visitId(),
                            record.fromDate(),
                            record.requestedDate(),
                            record.reason(),
                            record.status().name(),
                            record.decisionNote(),
                            record.requestedAt(),
                            record.decidedAt());
        }
    }

    public record VisitReworkResponse(
            UUID id,
            UUID visitId,
            int deliveryRevision,
            String reason,
            Instant requestedAt,
            Instant resolvedAt
    ) {
        static VisitReworkResponse from(VisitReworkRecord record) {
            return record == null
                    ? null
                    : new VisitReworkResponse(
                            record.id(),
                            record.visitId(),
                            record.deliveryRevision(),
                            record.reason(),
                            record.requestedAt(),
                            record.resolvedAt());
        }
    }

    public record ProviderSummaryResponse(UUID providerId, String name, String certificationLabel,
                                          boolean qualificationReviewed) {
        static ProviderSummaryResponse from(ProviderSummary provider) {
            return provider == null
                    ? null
                    : new ProviderSummaryResponse(
                            provider.providerId(),
                            provider.name(),
                            provider.certificationLabel(),
                            provider.qualificationReviewed());
        }
    }

    public record CustomerPartyResponse(String organizationName, String operatorName) {
        static CustomerPartyResponse from(CustomerParty party) {
            return party == null
                    ? null
                    : new CustomerPartyResponse(party.organizationName(), party.operatorName());
        }
    }

    public record PersonRefResponse(UUID subjectId, String displayName) {
        static PersonRefResponse from(PersonRef person) {
            return person == null
                    ? null
                    : new PersonRefResponse(person.subjectId(), person.displayName());
        }
    }

    public record PlanVersionSummaryResponse(
            UUID id,
            String planNo,
            int planVersion,
            String status,
            long totalAmountMinor,
            List<PlanItemResponse> planItems,
            String changeNote,
            Instant publishedAt
    ) {
        static PlanVersionSummaryResponse from(PlanVersionSummary summary) {
            return new PlanVersionSummaryResponse(
                    summary.id(),
                    summary.planNo(),
                    summary.planVersion(),
                    summary.status().name(),
                    summary.totalAmountMinor(),
                    summary.planItems().stream().map(PlanItemResponse::from).toList(),
                    summary.changeNote(),
                    summary.publishedAt());
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
            List<PlanVisitResponse> visits,
            ProviderSummaryResponse provider,
            CustomerPartyResponse customerParty,
            String summary,
            String scopeSummary,
            List<CodeLabelResponse> serviceMethods,
            DeliveryTermsPayload deliveryTerms,
            String changeNote,
            List<PlanItemResponse> planItems,
            List<PlanDeliverableResponse> deliverables,
            long totalAmountMinor,
            String currency,
            List<PlanPaymentNodeResponse> paymentNodes,
            Instant publishedAt,
            Instant confirmedAt,
            PersonRefResponse confirmedBy,
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
                    plan.terms().visits().stream().map(PlanVisitResponse::from).toList(),
                    ProviderSummaryResponse.from(plan.provider()),
                    CustomerPartyResponse.from(plan.customerParty()),
                    plan.summary(),
                    plan.terms().scopeSummary(),
                    plan.terms().serviceMethods().stream()
                            .map(CodeLabelResponse::from)
                            .toList(),
                    DeliveryTermsPayload.from(plan.terms().deliveryTerms()),
                    plan.terms().changeNote(),
                    plan.planItems().stream().map(PlanItemResponse::from).toList(),
                    plan.deliverables().stream().map(PlanDeliverableResponse::from).toList(),
                    plan.totalAmountMinor(),
                    plan.currency(),
                    plan.terms().paymentNodes().stream()
                            .map(PlanPaymentNodeResponse::from)
                            .toList(),
                    plan.publishedAt(),
                    plan.confirmedAt(),
                    PersonRefResponse.from(plan.confirmedBy()),
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
            String paymentMode,
            List<OrderPaymentNodeResponse> paymentNodes,
            ProviderSummaryResponse provider,
            CustomerPartyResponse customerParty,
            ServiceSnapshotResponse serviceSnapshot,
            List<OrderAssignmentResponse> assignments,
            List<OrderProgressResponse> progress,
            List<ServiceVisitResponse> visits,
            Instant createdAt,
            Instant deliveredAt,
            Instant acceptedAt,
            int version,
            boolean cancellable,
            CancellationResponse cancellation,
            List<PaymentRecordResponse> paymentRecords
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
                    order.paymentMode().name(),
                    order.paymentNodes().stream()
                            .map(OrderPaymentNodeResponse::from)
                            .toList(),
                    ProviderSummaryResponse.from(order.provider()),
                    CustomerPartyResponse.from(order.customerParty()),
                    ServiceSnapshotResponse.from(order.serviceSnapshot()),
                    order.assignments().stream()
                            .map(OrderAssignmentResponse::from)
                            .toList(),
                    order.progress().stream()
                            .map(OrderProgressResponse::from)
                            .toList(),
                    order.visits().stream()
                            .map(visit -> ServiceVisitResponse.from(visit, order))
                            .toList(),
                    order.createdAt(),
                    order.deliveredAt(),
                    order.acceptedAt(),
                    order.version(),
                    order.cancellable(),
                    CancellationResponse.from(order.extras().cancellation()),
                    order.extras().attempts().stream().map(PaymentRecordResponse::from).toList());
        }
    }

    public record ServiceVisitResponse(
            UUID id,
            UUID serviceOrderId,
            int visitNo,
            LocalDate plannedDate,
            Instant plannedStartAt,
            String status,
            List<OrderProgressResponse> progress,
            Instant startedAt,
            Instant deliveredAt,
            Instant acceptedAt,
            int version,
            LocalDate originalPlannedDate,
            boolean reschedulable,
            VisitRescheduleResponse reschedule,
            VisitReworkResponse rework
    ) {
        /** 改期和整改信息只在读整张服务单时带出；单独返回一次巡检（排期、进度）时为空。 */
        static ServiceVisitResponse from(StoredServiceVisit visit) {
            return from(visit, null);
        }

        static ServiceVisitResponse from(StoredServiceVisit visit, ServiceOrderView order) {
            ServiceOrderExtras extras = order == null ? ServiceOrderExtras.EMPTY : order.extras();
            VisitRescheduleRecord reschedule = extras.latestReschedule(visit.id()).orElse(null);
            boolean reschedulable = order != null && order.reschedulableVisitIds().contains(visit.id());
            return new ServiceVisitResponse(
                    visit.id(),
                    visit.serviceOrderId(),
                    visit.visitNo(),
                    visit.plannedDate(),
                    visit.plannedStartAt(),
                    visit.status().name(),
                    visit.progress().stream().map(OrderProgressResponse::from).toList(),
                    visit.startedAt(),
                    visit.deliveredAt(),
                    visit.acceptedAt(),
                    visit.version(),
                    extras.originalPlannedDates().get(visit.id()),
                    reschedulable,
                    VisitRescheduleResponse.from(reschedule),
                    VisitReworkResponse.from(extras.latestRework(visit.id()).orElse(null)));
        }
    }

    public record OrderAssignmentResponse(
            String type,
            UUID resourceId,
            String displayName,
            String roleLabel,
            String contactMobileMasked,
            boolean certifiedPilot
    ) {
        static OrderAssignmentResponse from(OrderAssignment assignment) {
            return new OrderAssignmentResponse(
                    assignment.type().name(),
                    assignment.resourceId(),
                    assignment.displayName(),
                    assignment.roleLabel(),
                    assignment.contactMobileMasked(),
                    assignment.certifiedPilot());
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
