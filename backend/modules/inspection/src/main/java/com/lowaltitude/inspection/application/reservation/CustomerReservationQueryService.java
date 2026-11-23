package com.lowaltitude.inspection.application.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
                .map(this::toSummary)
                .toList();
        return new CustomerReservationPage(
                items,
                page,
                size,
                total,
                (long) page * size < total,
                counts.visibleStageCounts());
    }

    /**
     * S3-2 服务商工作台：分给本机构的巡检预约（reservation.provider_id 等于令牌里的机构），形状和阶段推导与客户订单相同。
     * 只返回列表摘要；联系人、附件等仍要经 GET /inspection/reservations/{id} 按“已分配”的权限读。
     */
    public CustomerReservationPage listAssigned(
            UUID providerId,
            CustomerReservationFilter filter,
            int page,
            int size
    ) {
        if (providerId == null || filter == null || page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("provider reservation query is invalid");
        }
        int offset = Math.multiplyExact(page - 1, size);
        CustomerReservationCountSnapshot counts = store.countAssigned(providerId);
        long total = counts.totalFor(filter);
        List<CustomerReservationSummary> items = store.findAssigned(providerId, filter, size, offset)
                .stream()
                .map(this::toSummary)
                .toList();
        return new CustomerReservationPage(
                items,
                page,
                size,
                total,
                (long) page * size < total,
                counts.visibleStageCounts());
    }

    /** 本机构的一张巡检预约的列表摘要；不是本机构的当作不存在。 */
    public CustomerReservationSummary readAssigned(UUID providerId, UUID reservationId) {
        if (providerId == null || reservationId == null) {
            throw new ReservationNotFoundException();
        }
        return store.findAssigned(providerId, reservationId)
                .map(this::toSummary)
                .orElseThrow(ReservationNotFoundException::new);
    }

    private CustomerReservationSummary toSummary(StoredCustomerReservationSummary row) {
        return new CustomerReservationSummary(
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
                "CNY",
                row.visitCount(),
                row.acceptedVisitCount(),
                row.nextVisitDate(),
                row.pendingAcceptanceVisitId(),
                row.pendingAcceptanceVisitNo(),
                row.paymentDueNodeNo(),
                row.paymentDueNodeType(),
                row.paymentDueVisitNo(),
                row.paymentDueAmountMinor(),
                row.provider());
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
        String currency,
        int visitCount,
        int acceptedVisitCount,
        LocalDate nextVisitDate,
        UUID pendingAcceptanceVisitId,
        Integer pendingAcceptanceVisitNo,
        Integer paymentDueNodeNo,
        String paymentDueNodeType,
        Integer paymentDueVisitNo,
        Long paymentDueAmountMinor,
        ReservationProvider provider
) { }

/**
 * S4-4：订单卡头行的服务商（原型画板 OrderList）。qualificationReviewed 只来自机构的资质审核通过时间（R6-4）。
 * 还没分给服务商的预约为 null。
 */
record ReservationProvider(UUID providerId, String name, boolean qualificationReviewed) { }

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
        Long quoteAmountMinor,
        int visitCount,
        int acceptedVisitCount,
        LocalDate nextVisitDate,
        UUID pendingAcceptanceVisitId,
        Integer pendingAcceptanceVisitNo,
        Integer paymentDueNodeNo,
        String paymentDueNodeType,
        Integer paymentDueVisitNo,
        Long paymentDueAmountMinor,
        ReservationProvider provider
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

    List<StoredCustomerReservationSummary> findAssigned(
            UUID providerId,
            CustomerReservationFilter filter,
            int limit,
            int offset);

    Optional<StoredCustomerReservationSummary> findAssigned(UUID providerId, UUID reservationId);

    CustomerReservationCountSnapshot countAssigned(UUID providerId);
}
