package com.lowaltitude.inspection.application.reservation;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inspection/reservations")
public final class InspectionReservationController {

    private static final String CREATE_PERMISSION = "inspection.reservation.create";

    private final ReservationApplicationService service;

    public InspectionReservationController(ReservationApplicationService service) {
        this.service = service;
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
