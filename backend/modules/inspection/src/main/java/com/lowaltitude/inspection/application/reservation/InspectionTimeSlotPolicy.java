package com.lowaltitude.inspection.application.reservation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

final class InspectionTimeSlotPolicy {
    static final int CAPACITY = 9;
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<InspectionTimeSlotDefinition> SLOTS = List.of(
            new InspectionTimeSlotDefinition("MORNING", LocalTime.of(9, 0), LocalTime.of(12, 0)),
            new InspectionTimeSlotDefinition("AFTERNOON", LocalTime.of(14, 0), LocalTime.of(17, 0)),
            new InspectionTimeSlotDefinition("EVENING", LocalTime.of(18, 0), LocalTime.of(21, 0)));

    private InspectionTimeSlotPolicy() {
    }

    static List<InspectionTimeSlotDefinition> slots() {
        return SLOTS;
    }

    static InspectionTimeSlotDefinition slot(String code) {
        return SLOTS.stream()
                .filter(value -> value.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("time slot code is invalid"));
    }

    static void validateDateRange(LocalDate startDate, int days, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        if (startDate == null
                || startDate.isBefore(today)
                || startDate.isAfter(today.plusDays(30))
                || days < 1
                || days > 31
                || startDate.plusDays(days - 1L).isAfter(today.plusDays(30))) {
            throw new IllegalArgumentException("time slot date range is invalid");
        }
    }
}

record InspectionTimeSlotDefinition(
        String code,
        LocalTime startTime,
        LocalTime endTime
) {
}

record InspectionTimeSlotView(
        String slotCode,
        LocalTime startTime,
        LocalTime endTime,
        int remainingCount,
        String availability
) {
}

record InspectionServiceDateAvailabilityView(
        LocalDate serviceDate,
        List<InspectionTimeSlotView> slots
) {
}
