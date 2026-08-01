package com.lowaltitude.inspection.application.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class CustomerReservationQueryService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CustomerReservationQueryStore store;
    private final SensitiveDataCipher cipher;

    public CustomerReservationQueryService(
            CustomerReservationQueryStore store,
            SensitiveDataCipher cipher
    ) {
        this.store = store;
        this.cipher = cipher;
    }

    public CustomerReservationPage listOwn(
            UUID customerId,
            CustomerReservationFilter filter,
            int page,
            int size
    ) {
        if (customerId == null || filter == null || page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("customer order query is invalid");
        }
        int offset = Math.multiplyExact(page - 1, size);
        CustomerReservationCountSnapshot counts = store.countOwn(customerId);
        long total = counts.totalFor(filter);
        List<CustomerReservationSummary> items = store.findOwn(customerId, filter, size, offset)
                .stream()
                .map(row -> new CustomerReservationSummary(
                        row.reservationId(),
                        row.reservationNo(),
                        row.serviceId(),
                        row.serviceTitle(),
                        row.serviceCoverUrl(),
                        row.cityCode(),
                        cipher.decrypt(row.locationCiphertext()),
                        row.preferredServiceDate(),
                        row.submittedAt(),
                        row.stage(),
                        row.reservationStatus(),
                        row.currentPlanId(),
                        row.serviceOrderId(),
                        row.serviceOrderStatus(),
                        row.quoteAmountMinor(),
                        "CNY"))
                .toList();
        return new CustomerReservationPage(
                items,
                page,
                size,
                total,
                (long) page * size < total,
                counts.visibleStageCounts());
    }
}

enum CustomerReservationFilter {
    ALL,
    AWAITING_PLAN,
    AWAITING_CONFIRMATION,
    IN_PROGRESS,
    AWAITING_ACCEPTANCE,
    COMPLETED;

    static CustomerReservationFilter parse(String value) {
        try {
            return value == null || value.isBlank()
                    ? ALL
                    : valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("order stage is invalid", exception);
        }
    }
}

enum CustomerReservationStage {
    AWAITING_PLAN,
    AWAITING_CONFIRMATION,
    IN_PROGRESS,
    AWAITING_ACCEPTANCE,
    COMPLETED,
    CANCELLED
}

record CustomerReservationSummary(
        UUID reservationId,
        String reservationNo,
        UUID serviceId,
        String serviceTitle,
        String serviceCoverUrl,
        String cityCode,
        String locationSummary,
        LocalDate preferredServiceDate,
        Instant submittedAt,
        CustomerReservationStage stage,
        String reservationStatus,
        UUID currentPlanId,
        UUID serviceOrderId,
        String serviceOrderStatus,
        Long quoteAmountMinor,
        String currency
) { }

record StoredCustomerReservationSummary(
        UUID reservationId,
        String reservationNo,
        UUID serviceId,
        String serviceTitle,
        String serviceCoverUrl,
        String cityCode,
        String locationCiphertext,
        LocalDate preferredServiceDate,
        Instant submittedAt,
        CustomerReservationStage stage,
        String reservationStatus,
        UUID currentPlanId,
        UUID serviceOrderId,
        String serviceOrderStatus,
        Long quoteAmountMinor
) { }

record CustomerReservationStageCounts(
        long awaitingPlan,
        long awaitingConfirmation,
        long inProgress,
        long awaitingAcceptance,
        long completed
) { }

record CustomerReservationCountSnapshot(
        long allCount,
        long awaitingPlan,
        long awaitingConfirmation,
        long inProgress,
        long awaitingAcceptance,
        long completed
) {
    long totalFor(CustomerReservationFilter filter) {
        return switch (filter) {
            case ALL -> allCount;
            case AWAITING_PLAN -> awaitingPlan;
            case AWAITING_CONFIRMATION -> awaitingConfirmation;
            case IN_PROGRESS -> inProgress;
            case AWAITING_ACCEPTANCE -> awaitingAcceptance;
            case COMPLETED -> completed;
        };
    }

    CustomerReservationStageCounts visibleStageCounts() {
        return new CustomerReservationStageCounts(
                awaitingPlan,
                awaitingConfirmation,
                inProgress,
                awaitingAcceptance,
                completed);
    }
}

record CustomerReservationPage(
        List<CustomerReservationSummary> items,
        int page,
        int size,
        long total,
        boolean hasMore,
        CustomerReservationStageCounts stageCounts
) { }

interface CustomerReservationQueryStore {
    List<StoredCustomerReservationSummary> findOwn(
            UUID customerId,
            CustomerReservationFilter filter,
            int limit,
            int offset);

    CustomerReservationCountSnapshot countOwn(UUID customerId);
}
