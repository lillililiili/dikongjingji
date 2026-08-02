package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InspectionTimeSlotPolicyTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-31T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void exposesThreeFixedSlotsWithCapacityNine() {
        assertThat(InspectionTimeSlotPolicy.CAPACITY).isEqualTo(9);
        assertThat(InspectionTimeSlotPolicy.slots())
                .extracting(InspectionTimeSlotDefinition::code)
                .containsExactly("MORNING", "AFTERNOON", "EVENING");
        assertThat(InspectionTimeSlotPolicy.slot("MORNING").startTime().toString())
                .isEqualTo("09:00");
        assertThat(InspectionTimeSlotPolicy.slot("EVENING").endTime().toString())
                .isEqualTo("21:00");
    }

    @Test
    void acceptsTodayThroughThirtyDaysAndRejectsInvalidRanges() {
        InspectionTimeSlotPolicy.validateDateRange(
                LocalDate.of(2026, 7, 31), 31, CLOCK);
        assertThatThrownBy(() -> InspectionTimeSlotPolicy.validateDateRange(
                LocalDate.of(2026, 7, 30), 1, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InspectionTimeSlotPolicy.validateDateRange(
                LocalDate.of(2026, 7, 31), 32, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InspectionTimeSlotPolicy.slot("NIGHT"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExactlyOneHourLeadTimeAndRejectsOneSecondLess() {
        LocalDate serviceDate = LocalDate.of(2026, 8, 1);
        InspectionTimeSlotDefinition evening = InspectionTimeSlotPolicy.slot("EVENING");
        Clock exactlyOneHour = Clock.fixed(
                Instant.parse("2026-08-01T09:00:00Z"), ZoneOffset.UTC);
        Clock oneSecondTooLate = Clock.fixed(
                Instant.parse("2026-08-01T09:00:01Z"), ZoneOffset.UTC);

        assertThat(InspectionTimeSlotPolicy.satisfiesLeadTime(
                serviceDate, evening, exactlyOneHour)).isTrue();
        assertThat(InspectionTimeSlotPolicy.satisfiesLeadTime(
                serviceDate, evening, oneSecondTooLate)).isFalse();
    }
}
