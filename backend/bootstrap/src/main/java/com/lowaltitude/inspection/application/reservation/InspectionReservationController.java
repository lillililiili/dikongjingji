package com.lowaltitude.inspection.application.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inspection/reservations")
public final class InspectionReservationController {

    private static final String CREATE_PERMISSION = "inspection.reservation.create";
    private static final String READ_OWN_PERMISSION = "inspection.reservation.read.own";
    private static final int MAX_PAGE_SIZE = 50;

    private final ReservationApplicationService service;
    private final CustomerReservationQueryService queryService;

    public InspectionReservationController(
            ReservationApplicationService service,
            CustomerReservationQueryService queryService
    ) {
        this.service = service;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> create(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CreateReservationRequest request,
            Authentication authentication
    ) {
        CurrentSubject subject = currentSubject(authentication);
        if (!subject.roles().contains("CUSTOMER")
                || !subject.permissions().contains(CREATE_PERMISSION)) {
            throw new AccessDeniedException("customer reservation permission is required");
        }

        ReservationView created =
                service.create(subject.subjectId(), idempotencyKey, request.toCommand());
        ReservationDetails details = service.read(subject, created.id());
        return ResponseEntity
                .status(201)
                .eTag(Integer.toString(details.version()))
                .body(ReservationResponse.from(details));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationResponse> get(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        ReservationDetails details = service.read(currentSubject(authentication), id);
        return ResponseEntity
                .ok()
                .eTag(Integer.toString(details.version()))
                .body(ReservationResponse.from(details));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancel(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(name = "X-Request-Id", required = false) String requestId,
            Authentication authentication
    ) {
        ReservationDetails cancelled = service.cancel(
                currentSubject(authentication),
                id,
                idempotencyKey,
                parseIfMatch(ifMatch),
                requestId == null || requestId.isBlank()
                        ? UUID.randomUUID().toString()
                        : requestId.trim());
        return ResponseEntity.ok()
                .eTag(Integer.toString(cancelled.version()))
                .body(ReservationResponse.from(cancelled));
    }

    @GetMapping
    public CustomerReservationPageResponse list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ALL") String stage,
            Authentication authentication
    ) {
        CurrentSubject subject = currentSubject(authentication);
        if (!subject.roles().contains("CUSTOMER")
                || !subject.permissions().contains(READ_OWN_PERMISSION)) {
            throw new AccessDeniedException("customer own reservation permission is required");
        }
        validatePageRequest(page, size);
        return CustomerReservationPageResponse.from(
                queryService.listOwn(
                        subject.subjectId(),
                        CustomerReservationFilter.parse(stage),
                        page,
                        size));
    }

    private static void validatePageRequest(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE
                || (long) (page - 1) * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("customer order page is invalid");
        }
    }

    private static int parseIfMatch(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("^\"(0|[1-9][0-9]*)\"$")) {
            throw new IllegalArgumentException("If-Match must be a strong numeric ETag");
        }
        String version = normalized.substring(1, normalized.length() - 1);
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "If-Match must be a strong numeric ETag",
                    exception);
        }
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
            UUID organizationId = UUID.fromString(requiredClaim(jwt, "organizationId"));
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
            throw new AccessDeniedException("authenticated subject claims are invalid", exception);
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

    public record CreateReservationRequest(
            UUID serviceId,
            String cityCode,
            Location location,
            Long lineLengthMeters,
            LocalDate preferredServiceDate,
            String preferredTimeSlotCode,
            List<String> inspectionItemCodes,
            List<UUID> attachmentFileIds,
            String note,
            Contact contact,
            String agreementVersion,
            boolean agreementAccepted
    ) {

        CreateReservationCommand toCommand() {
            return new CreateReservationCommand(
                    serviceId,
                    cityCode,
                    location == null ? null : location.address(),
                    location == null ? null : location.longitude(),
                    location == null ? null : location.latitude(),
                    lineLengthMeters,
                    preferredServiceDate,
                    preferredTimeSlotCode,
                    inspectionItemCodes,
                    attachmentFileIds == null ? List.of() : attachmentFileIds,
                    note,
                    contact == null ? null : contact.name(),
                    contact == null ? null : contact.mobile(),
                    agreementVersion,
                    agreementAccepted);
        }
    }

    public record Location(String address, Double longitude, Double latitude) {
    }

    public record Contact(String name, String mobile) {
    }

    public record CodeLabel(String code, String label) {
    }

    public record FileObjectResponse(
            UUID id,
            String originalName,
            String contentType,
            long sizeBytes,
            String status,
            String scanFailureReason
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomerReservationSummaryResponse(
            UUID reservationId,
            String reservationNo,
            UUID serviceId,
            String serviceTitle,
            String serviceCoverUrl,
            String cityCode,
            String locationSummary,
            LocalDate preferredServiceDate,
            Instant submittedAt,
            String stage,
            String reservationStatus,
            UUID currentPlanId,
            UUID serviceOrderId,
            String serviceOrderStatus,
            Long quoteAmountMinor,
            String currency,
            int visitCount,
            int acceptedVisitCount,
            LocalDate nextVisitDate,
            UUID pendingAcceptanceVisitId,
            Integer pendingAcceptanceVisitNo,
            Integer paymentDueNodeNo,
            String paymentDueNodeType,
            Integer paymentDueVisitNo,
            Long paymentDueAmountMinor
    ) {

        static CustomerReservationSummaryResponse from(CustomerReservationSummary value) {
            return new CustomerReservationSummaryResponse(
                    value.reservationId(),
                    value.reservationNo(),
                    value.serviceId(),
                    value.serviceTitle(),
                    value.serviceCoverUrl(),
                    value.cityCode(),
                    value.locationSummary(),
                    value.preferredServiceDate(),
                    value.submittedAt(),
                    value.stage().name(),
                    value.reservationStatus(),
                    value.currentPlanId(),
                    value.serviceOrderId(),
                    value.serviceOrderStatus(),
                    value.quoteAmountMinor(),
                    value.currency(),
                    value.visitCount(),
                    value.acceptedVisitCount(),
                    value.nextVisitDate(),
                    value.pendingAcceptanceVisitId(),
                    value.pendingAcceptanceVisitNo(),
                    value.paymentDueNodeNo(),
                    value.paymentDueNodeType(),
                    value.paymentDueVisitNo(),
                    value.paymentDueAmountMinor());
        }
    }

    public record CustomerReservationPageResponse(
            List<CustomerReservationSummaryResponse> items,
            int page,
            int size,
            long total,
            boolean hasMore,
            Map<String, Long> stageCounts
    ) {

        static CustomerReservationPageResponse from(CustomerReservationPage value) {
            CustomerReservationStageCounts counts = value.stageCounts();
            return new CustomerReservationPageResponse(
                    value.items().stream()
                            .map(CustomerReservationSummaryResponse::from)
                            .toList(),
                    value.page(),
                    value.size(),
                    value.total(),
                    value.hasMore(),
                    Map.of(
                            "AWAITING_PLAN", counts.awaitingPlan(),
                            "AWAITING_CONFIRMATION", counts.awaitingConfirmation(),
                            "IN_PROGRESS", counts.inProgress(),
                            "AWAITING_ACCEPTANCE", counts.awaitingAcceptance(),
                            "COMPLETED", counts.completed()));
        }
    }

    public record ReservationResponse(
            UUID id,
            String reservationNo,
            UUID customerId,
            UUID providerId,
            UUID serviceId,
            String serviceTitle,
            String serviceCoverUrl,
            String cityCode,
            String status,
            Location location,
            Long lineLengthMeters,
            LocalDate preferredServiceDate,
            String preferredTimeSlotCode,
            List<CodeLabel> inspectionItems,
            String note,
            Contact contact,
            List<FileObjectResponse> attachments,
            Instant submittedAt,
            UUID currentPlanId,
            int version
    ) {

        static ReservationResponse from(ReservationDetails details) {
            return new ReservationResponse(
                    details.id(),
                    details.reservationNo(),
                    details.customerId(),
                    details.snapshot().providerId(),
                    details.snapshot().serviceId(),
                    details.snapshot().title(),
                    details.snapshot().coverUrl(),
                    details.cityCode(),
                    details.status(),
                    new Location(
                            details.address(),
                            details.longitude(),
                            details.latitude()),
                    details.lineLengthMeters(),
                    details.preferredServiceDate(),
                    details.preferredTimeSlotCode(),
                    details.items().stream()
                            .map(item -> new CodeLabel(item.code(), item.label()))
                            .toList(),
                    details.note(),
                    new Contact(details.contactName(), details.contactMobile()),
                    details.attachments().stream()
                            .map(file -> new FileObjectResponse(
                                    file.id(),
                                    file.originalName(),
                                    file.contentType(),
                                    file.sizeBytes(),
                                    file.status().name(),
                                    file.failureReason()))
                            .toList(),
                    details.submittedAt(),
                    details.currentPlanId(),
                    details.version());
        }
    }
}
