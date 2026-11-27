package com.lowaltitude.inspection.application.reservation;

import java.util.UUID;
import com.lowaltitude.inspection.application.reservation.InspectionReservationController.CreateReservationRequest;
import com.lowaltitude.inspection.application.reservation.InspectionReservationController.ReservationResponse;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inspection/demand-bookings/{demandId}")
public final class InspectionDemandBookingController {
    private final InspectionDemandBookingService service;

    public InspectionDemandBookingController(InspectionDemandBookingService service) {
        this.service = service;
    }

    @GetMapping
    public InspectionDemandBookingService.Context preview(@PathVariable UUID demandId, Authentication authentication) {
        return service.preview(customer(authentication, "inspection.reservation.read.own").subjectId(), demandId);
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> book(@PathVariable UUID demandId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody CreateReservationRequest request,
            Authentication authentication) {
        var result = service.book(customer(authentication, "inspection.reservation.create"), demandId, key,
                request.toCommand());
        return ResponseEntity.status(result.created() ? 201 : 200)
                .eTag(Integer.toString(result.reservation().version()))
                .body(ReservationResponse.from(result.reservation()));
    }

    private static CurrentSubject customer(Authentication authentication, String permission) {
        CurrentSubject subject = InspectionReservationController.currentSubject(authentication);
        if (!subject.roles().contains("CUSTOMER") || !subject.permissions().contains(permission)) {
            throw new AccessDeniedException("customer reservation permission is required");
        }
        return subject;
    }
}
