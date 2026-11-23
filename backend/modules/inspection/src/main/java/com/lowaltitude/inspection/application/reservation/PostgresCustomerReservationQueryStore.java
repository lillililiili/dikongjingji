package com.lowaltitude.inspection.application.reservation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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
                .map(PostgresCustomerReservationQueryStore::toStored)
                .toList();
    }

    @Override
    public CustomerReservationCountSnapshot countOwn(UUID customerId) {
        return toCounts(mapper.countCustomerReservations(customerId));
    }

    @Override
    public List<StoredCustomerReservationSummary> findAssigned(
            UUID providerId,
            CustomerReservationFilter filter,
            int limit,
            int offset
    ) {
        return mapper.findProviderReservations(providerId, filter.name(), limit, offset)
                .stream()
                .map(PostgresCustomerReservationQueryStore::toStored)
                .toList();
    }

    @Override
    public Optional<StoredCustomerReservationSummary> findAssigned(UUID providerId, UUID reservationId) {
        return mapper.findProviderReservation(providerId, reservationId)
                .map(PostgresCustomerReservationQueryStore::toStored);
    }

    @Override
    public CustomerReservationCountSnapshot countAssigned(UUID providerId) {
        return toCounts(mapper.countProviderReservations(providerId));
    }

    private static StoredCustomerReservationSummary toStored(
            ReservationPersistenceMapper.CustomerReservationSummaryRow row
    ) {
        return new StoredCustomerReservationSummary(
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
                row.pendingAcceptanceVisitNo());
    }

    private static CustomerReservationCountSnapshot toCounts(
            ReservationPersistenceMapper.CustomerReservationCountsRow row
    ) {
        return new CustomerReservationCountSnapshot(
                row.allCount(),
                row.awaitingPlan(),
                row.awaitingConfirmation(),
                row.inProgress(),
                row.awaitingAcceptance(),
                row.completed());
    }
}
