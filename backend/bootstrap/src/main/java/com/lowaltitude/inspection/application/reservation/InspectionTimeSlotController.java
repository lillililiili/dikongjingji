package com.lowaltitude.inspection.application.reservation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class InspectionTimeSlotController {

    private final ReservationApplicationService service;

    public InspectionTimeSlotController(ReservationApplicationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/inspection/services/{serviceId}/available-time-slots")
    public List<InspectionServiceDateAvailabilityView> availableTimeSlots(
            @PathVariable UUID serviceId,
            @RequestParam String cityCode,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(defaultValue = "4") int days
    ) {
        return service.availableTimeSlots(serviceId, cityCode.trim(), startDate, days);
    }
}
