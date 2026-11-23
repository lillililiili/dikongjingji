package com.lowaltitude.inspection.application.reservation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.infrastructure.persistence.ReservationPersistenceMapper;

final class PostgresCustomerReservationQueryStore
        implements CustomerReservationQueryStore {

    private final ReservationPersistenceMapper mapper;

    PostgresCustomerReservationQueryStore(ReservationPersistenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<StoredCustomerReservationSummary> findOwn(
            UUID customerId,
            CustomerReservationFilter filter,
            int limit,
            int offset
    ) {
        return mapper.findCustomerReservations(customerId, filter.name(), limit, offset)
                .stream()
                .map(row -> new StoredCustomerReservationSummary(
                        row.reservationId(),
                        row.reservationNo(),
                        row.serviceId(),
                        row.serviceTitle(),
                        row.serviceCoverUrl(),
                        row.cityCode(),
                        new String(row.locationCiphertext(), StandardCharsets.UTF_8),
                        row.preferredServiceDate(),
                        row.submittedAt(),
                        CustomerReservationStage.valueOf(row.stage()),
                        row.reservationStatus(),
                        row.currentPlanId(),
                        row.serviceOrderId(),
                        row.serviceOrderStatus(),
                        row.quoteAmountMinor(),
                        row.visitCount(),
                        row.acceptedVisitCount(),
                        row.nextVisitDate(),
                        row.pendingAcceptanceVisitId(),
                        row.pendingAcceptanceVisitNo(),
                        row.paymentDueNodeNo(),
                        row.paymentDueNodeType(),
                        row.paymentDueVisitNo(),
                        row.paymentDueAmountMinor()))
                .toList();
    }

    @Override
    public CustomerReservationCountSnapshot countOwn(UUID customerId) {
        ReservationPersistenceMapper.CustomerReservationCountsRow row =
                mapper.countCustomerReservations(customerId);
        return new CustomerReservationCountSnapshot(
                row.allCount(),
                row.awaitingPlan(),
                row.awaitingConfirmation(),
                row.inProgress(),
                row.awaitingAcceptance(),
                row.completed());
    }
}
