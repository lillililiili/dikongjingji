package com.lowaltitude.inspection.application.serviceflow;

import static com.lowaltitude.inspection.application.serviceflow.ServiceVisitStatus.ACCEPTED;
import static com.lowaltitude.inspection.application.serviceflow.ServiceVisitStatus.DELIVERED;
import static com.lowaltitude.inspection.application.serviceflow.ServiceVisitStatus.IN_PROGRESS;
import static com.lowaltitude.inspection.application.serviceflow.ServiceVisitStatus.PENDING_SCHEDULE;
import static com.lowaltitude.inspection.application.serviceflow.ServiceVisitStatus.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ServiceOrderProgressionTest {

    @Test
    void singleVisitFollowsTheOriginalServiceOrderStates() {
        assertThat(derive(PENDING_SCHEDULE)).isEqualTo(ServiceOrderStatus.ARRANGING);
        assertThat(derive(SCHEDULED)).isEqualTo(ServiceOrderStatus.ARRANGING);
        assertThat(derive(IN_PROGRESS)).isEqualTo(ServiceOrderStatus.IN_PROGRESS);
        assertThat(derive(DELIVERED)).isEqualTo(ServiceOrderStatus.DELIVERED);
        assertThat(derive(ACCEPTED)).isEqualTo(ServiceOrderStatus.ACCEPTED);
    }

    @Test
    void orderStaysInProgressUntilEveryVisitIsDelivered() {
        assertThat(derive(IN_PROGRESS, PENDING_SCHEDULE))
                .isEqualTo(ServiceOrderStatus.IN_PROGRESS);
        assertThat(derive(DELIVERED, SCHEDULED)).isEqualTo(ServiceOrderStatus.IN_PROGRESS);
        assertThat(derive(ACCEPTED, PENDING_SCHEDULE))
                .isEqualTo(ServiceOrderStatus.IN_PROGRESS);
        assertThat(derive(ACCEPTED, IN_PROGRESS)).isEqualTo(ServiceOrderStatus.IN_PROGRESS);
    }

    @Test
    void orderIsDeliveredOnceAllVisitsAreDeliveredAndAcceptedWhenAllAreAccepted() {
        assertThat(derive(DELIVERED, DELIVERED)).isEqualTo(ServiceOrderStatus.DELIVERED);
        assertThat(derive(ACCEPTED, DELIVERED)).isEqualTo(ServiceOrderStatus.DELIVERED);
        assertThat(derive(ACCEPTED, ACCEPTED)).isEqualTo(ServiceOrderStatus.ACCEPTED);
    }

    private static ServiceOrderStatus derive(ServiceVisitStatus... statuses) {
        List<StoredServiceVisit> visits = Arrays.stream(statuses)
                .map(status -> new StoredServiceVisit(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        LocalDate.of(2026, 8, 2),
                        null,
                        status,
                        List.of(),
                        null,
                        null,
                        null,
                        0))
                .toList();
        return ServiceOrderProgression.derive(visits);
    }
}
