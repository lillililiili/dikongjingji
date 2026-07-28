package com.lowaltitude.inspection.application.serviceflow;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.transaction.annotation.Transactional;

/** Owns the published-plan state machine and creates one service order on confirmation. */
public class PlanApplicationService {

    static final String PUBLISH_OPERATION = "inspection.plan.publish";
    static final String CONFIRM_OPERATION = "inspection.plan.confirm";
    private static final int MAX_PLAN_ITEMS = 50;
    private static final int MAX_DELIVERABLES = 20;
    private static final int MIN_DELIVERABLES = 4;

    private final PlanStore store;
    private final Clock clock;

    public PlanApplicationService(PlanStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public ServicePlanView publish(
            CurrentSubject subject,
            UUID reservationId,
            UUID idempotencyKey,
            int expectedReservationVersion,
            PublishPlanCommand command,
            String requestId
    ) {
        requireProviderPublisher(subject);
        validatePublishInput(
                reservationId,
                idempotencyKey,
                expectedReservationVersion,
                command,
                requestId);
        PlanReservation reservation = store.findReservation(reservationId)
                .orElseThrow(PlanResourceNotFoundException::new);
        if (!reservation.providerId().equals(subject.organizationId())
                || !command.providerId().equals(subject.organizationId())) {
            throw new PlanResourceNotFoundException();
        }

        String requestDigest = digestPublish(command);
        Optional<PlanIdempotencyEntry> prior =
                store.findIdempotency(subject.subjectId(), PUBLISH_OPERATION, idempotencyKey);
        if (prior.isPresent()) {
            return replayPlan(prior.get(), requestDigest);
        }
        if (reservation.version() != expectedReservationVersion) {
            throw new PlanVersionConflictException();
        }
        Optional<StoredServicePlan> current = store.findCurrentPlan(reservationId);
        if (current.filter(plan -> plan.status() == PlanStatus.CONFIRMED).isPresent()) {
            throw new PlanAlreadyConfirmedException();
        }
        if (reservation.status() == ReservationPlanStatus.CANCELLED) {
            throw new ReservationStateInvalidException();
        }

        Instant now = clock.instant();
        UUID planId = UUID.randomUUID();
        List<PlanItem> items = command.planItems().stream()
                .map(item -> new PlanItem(
                        item.code().trim(),
                        item.name().trim(),
                        item.quantity(),
                        item.unit().trim(),
                        item.unitPriceMinor(),
                        multiplyAmount(item.quantity(), item.unitPriceMinor())))
                .toList();
        long total = items.stream()
                .mapToLong(PlanItem::lineAmountMinor)
                .reduce(0L, PlanApplicationService::addAmount);
        StoredServicePlan plan = new StoredServicePlan(
                planId,
                planNumber(now, planId),
                reservation,
                subject.organizationId(),
                PlanStatus.PUBLISHED,
                store.nextPlanVersion(reservationId),
                command.validUntil(),
                command.serviceDate(),
                normalize(command.summary()),
                items,
                command.deliverables().stream()
                        .map(item -> new CodeLabel(item.code().trim(), item.name().trim()))
                        .toList(),
                total,
                "CNY",
                now,
                null,
                null,
                0);
        PlanIdempotencyEntry entry = new PlanIdempotencyEntry(
                subject.subjectId(),
                PUBLISH_OPERATION,
                idempotencyKey,
                requestDigest,
                plan.id());
        PlanWriteResult result =
                store.publish(reservation, plan, entry, subject, requestId.trim());
        if (result == PlanWriteResult.IDEMPOTENCY_EXISTS) {
            return replayPlan(
                    store.findIdempotency(
                                    subject.subjectId(),
                                    PUBLISH_OPERATION,
                                    idempotencyKey)
                            .orElseThrow(PlanIdempotencyConflictException::new),
                    requestDigest);
        }
        if (result == PlanWriteResult.VERSION_CONFLICT) {
            throw new PlanVersionConflictException();
        }
        if (result != PlanWriteResult.SAVED) {
            throw new ReservationStateInvalidException();
        }
        return toView(plan);
    }

    public ServicePlanView read(CurrentSubject subject, UUID planId) {
        if (subject == null || planId == null) {
            throw new PlanResourceNotFoundException();
        }
        StoredServicePlan plan =
                store.findPlan(planId).orElseThrow(PlanResourceNotFoundException::new);
        boolean own = subject.roles().contains("CUSTOMER")
                && subject.permissions().contains("inspection.plan.read.own")
                && plan.reservation().customerId().equals(subject.subjectId());
        boolean platform = subject.roles().contains("PLATFORM_OPERATOR")
                && subject.permissions().contains("inspection.plan.read.all");
        if (!own && !platform) {
            throw new PlanResourceNotFoundException();
        }
        return toView(plan);
    }

    @Transactional
    public ServiceOrderView confirm(
            CurrentSubject subject,
            UUID planId,
            UUID idempotencyKey,
            int expectedPlanVersion,
            ConfirmPlanCommand command,
            String requestId
    ) {
        requireCustomerConfirmer(subject);
        validateConfirmInput(
                planId,
                idempotencyKey,
                expectedPlanVersion,
                command,
                requestId);
        StoredServicePlan plan =
                store.findPlan(planId).orElseThrow(PlanResourceNotFoundException::new);
        if (!plan.reservation().customerId().equals(subject.subjectId())) {
            throw new PlanResourceNotFoundException();
        }

        String requestDigest = digestConfirm(command);
        Optional<PlanIdempotencyEntry> prior =
                store.findIdempotency(subject.subjectId(), CONFIRM_OPERATION, idempotencyKey);
        if (prior.isPresent()) {
            return replayOrder(prior.get(), requestDigest);
        }
        if (plan.status() == PlanStatus.CONFIRMED) {
            throw new PlanAlreadyConfirmedException();
        }
        if (plan.status() != PlanStatus.PUBLISHED) {
            throw new PlanNotPublishedException();
        }
        if (!plan.validUntil().isAfter(clock.instant())) {
            throw new PlanExpiredException();
        }
        if (plan.version() != expectedPlanVersion) {
            throw new PlanVersionConflictException();
        }

        Instant now = clock.instant();
        UUID orderId = UUID.randomUUID();
        ServiceSnapshot snapshot = new ServiceSnapshot(
                plan.reservation().serviceId(),
                plan.reservation().serviceTitle(),
                plan.reservation().serviceCoverUrl(),
                plan.reservation().cityCode(),
                plan.reservation().address(),
                plan.reservation().longitude(),
                plan.reservation().latitude(),
                plan.reservation().lineLengthMeters(),
                plan.serviceDate(),
                plan.reservation().inspectionItems());
        StoredServiceOrder order = new StoredServiceOrder(
                orderId,
                orderNumber(now, orderId),
                plan.reservation().id(),
                plan.id(),
                plan.reservation().customerId(),
                plan.providerId(),
                ServiceOrderStatus.ARRANGING,
                snapshot,
                now,
                0);
        PlanIdempotencyEntry entry = new PlanIdempotencyEntry(
                subject.subjectId(),
                CONFIRM_OPERATION,
                idempotencyKey,
                requestDigest,
                order.id());
        PlanWriteResult result = store.confirm(
                plan,
                order,
                entry,
                command.agreementVersion().trim(),
                subject,
                requestId.trim());
        if (result == PlanWriteResult.IDEMPOTENCY_EXISTS) {
            return replayOrder(
                    store.findIdempotency(
                                    subject.subjectId(),
                                    CONFIRM_OPERATION,
                                    idempotencyKey)
                            .orElseThrow(PlanIdempotencyConflictException::new),
                    requestDigest);
        }
        if (result == PlanWriteResult.VERSION_CONFLICT) {
            throw new PlanVersionConflictException();
        }
        if (result == PlanWriteResult.EXPIRED) {
            throw new PlanExpiredException();
        }
        if (result != PlanWriteResult.SAVED) {
            throw new PlanNotPublishedException();
        }
        return toView(order);
    }

    private ServicePlanView replayPlan(PlanIdempotencyEntry prior, String requestDigest) {
        if (!prior.requestDigest().equals(requestDigest)) {
            throw new PlanIdempotencyConflictException();
        }
        return toView(store.findPlan(prior.resourceId())
                .orElseThrow(PlanResourceNotFoundException::new));
    }

    private ServiceOrderView replayOrder(PlanIdempotencyEntry prior, String requestDigest) {
        if (!prior.requestDigest().equals(requestDigest)) {
            throw new PlanIdempotencyConflictException();
        }
        return toView(store.findOrder(prior.resourceId())
                .orElseThrow(PlanResourceNotFoundException::new));
    }

    private static void requireProviderPublisher(CurrentSubject subject) {
        if (subject == null
                || !subject.roles().contains("PROVIDER_OPERATOR")
                || !subject.permissions().contains("inspection.plan.publish.assigned")
                || subject.organizationId() == null) {
            throw new PlanForbiddenException();
        }
    }

    private static void requireCustomerConfirmer(CurrentSubject subject) {
        if (subject == null
                || !subject.roles().contains("CUSTOMER")
                || !subject.permissions().contains("inspection.plan.confirm.own")) {
            throw new PlanForbiddenException();
        }
    }

    private void validatePublishInput(
            UUID reservationId,
            UUID idempotencyKey,
            int expectedVersion,
            PublishPlanCommand command,
            String requestId
    ) {
        if (reservationId == null
                || idempotencyKey == null
                || expectedVersion < 0
                || command == null
                || command.providerId() == null
                || command.validUntil() == null
                || !command.validUntil().isAfter(clock.instant())
                || command.serviceDate() == null
                || command.serviceDate().isBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC)))
                || command.summary() != null && command.summary().length() > 1_000
                || !"CNY".equals(command.currency())
                || !validPlanItems(command.planItems())
                || !validDeliverables(command.deliverables())
                || !validText(requestId, 1, 80)) {
            throw new PlanValidationException();
        }
    }

    private static void validateConfirmInput(
            UUID planId,
            UUID idempotencyKey,
            int expectedVersion,
            ConfirmPlanCommand command,
            String requestId
    ) {
        if (planId == null
                || idempotencyKey == null
                || expectedVersion < 0
                || command == null
                || !command.agreementAccepted()
                || !validText(command.agreementVersion(), 1, 32)
                || !validText(requestId, 1, 80)) {
            throw new PlanValidationException();
        }
    }

    private static boolean validPlanItems(List<PlanItemInput> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_PLAN_ITEMS) {
            return false;
        }
        Set<String> codes = new HashSet<>();
        for (PlanItemInput item : items) {
            if (item == null
                    || !validText(item.code(), 1, 64)
                    || !validText(item.name(), 1, 120)
                    || item.quantity() < 1
                    || !validText(item.unit(), 1, 24)
                    || item.unitPriceMinor() < 0
                    || !codes.add(item.code().trim())) {
                return false;
            }
            multiplyAmount(item.quantity(), item.unitPriceMinor());
        }
        return true;
    }

    private static boolean validDeliverables(List<DeliverableInput> deliverables) {
        if (deliverables == null
                || deliverables.size() < MIN_DELIVERABLES
                || deliverables.size() > MAX_DELIVERABLES) {
            return false;
        }
        Set<String> codes = new HashSet<>();
        return deliverables.stream().allMatch(item ->
                item != null
                        && validText(item.code(), 1, 64)
                        && validText(item.name(), 1, 120)
                        && codes.add(item.code().trim()));
    }

    private static boolean validText(String value, int minimum, int maximum) {
        if (value == null) {
            return false;
        }
        int length = value.trim().length();
        return length >= minimum && length <= maximum;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long multiplyAmount(long quantity, long unitPriceMinor) {
        try {
            return Math.multiplyExact(quantity, unitPriceMinor);
        } catch (ArithmeticException exception) {
            throw new PlanValidationException();
        }
    }

    private static long addAmount(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new PlanValidationException();
        }
    }

    private static String digestPublish(PublishPlanCommand command) {
        return digest(List.of(
                command.providerId(),
                command.validUntil(),
                command.serviceDate(),
                String.valueOf(command.summary()),
                command.planItems(),
                command.deliverables(),
                command.currency()));
    }

    private static String digestConfirm(ConfirmPlanCommand command) {
        return digest(List.of(command.agreementVersion(), command.agreementAccepted()));
    }

    private static String digest(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, value);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void updateDigest(MessageDigest digest, Object value) {
        if (value instanceof Collection<?> collection) {
            updateDigest(digest, collection.size());
            collection.forEach(item -> updateDigest(digest, item));
            return;
        }
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String planNumber(Instant now, UUID id) {
        return businessNumber("FA", now, id);
    }

    private static String orderNumber(Instant now, UUID id) {
        return businessNumber("FW", now, id);
    }

    private static String businessNumber(String prefix, Instant now, UUID id) {
        String time = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneOffset.UTC)
                .format(now);
        return prefix + time + id.toString().substring(0, 4).toUpperCase();
    }

    private static ServicePlanView toView(StoredServicePlan plan) {
        return new ServicePlanView(
                plan.id(),
                plan.planNo(),
                plan.reservation().id(),
                plan.providerId(),
                plan.status(),
                plan.planVersion(),
                plan.validUntil(),
                plan.serviceDate(),
                plan.summary(),
                plan.planItems(),
                plan.deliverables(),
                plan.totalAmountMinor(),
                plan.currency(),
                plan.publishedAt(),
                plan.confirmedAt(),
                plan.serviceOrderId(),
                plan.version());
    }

    private static ServiceOrderView toView(StoredServiceOrder order) {
        return new ServiceOrderView(
                order.id(),
                order.serviceOrderNo(),
                order.reservationId(),
                order.planId(),
                order.customerId(),
                order.providerId(),
                order.status(),
                order.serviceSnapshot(),
                order.plannedStartAt(),
                order.assignments(),
                order.progress(),
                order.createdAt(),
                order.deliveredAt(),
                order.acceptedAt(),
                order.version());
    }
}

record PublishPlanCommand(
        UUID providerId,
        Instant validUntil,
        LocalDate serviceDate,
        String summary,
        List<PlanItemInput> planItems,
        List<DeliverableInput> deliverables,
        String currency
) {
}

record ConfirmPlanCommand(String agreementVersion, boolean agreementAccepted) {
}

record PlanItemInput(String code, String name, int quantity, String unit, long unitPriceMinor) {
}

record DeliverableInput(String code, String name) {
}

record PlanItem(
        String code,
        String name,
        int quantity,
        String unit,
        long unitPriceMinor,
        long lineAmountMinor
) {
}

record CodeLabel(String code, String label) {
}

enum ReservationPlanStatus {
    SUBMITTED,
    PLAN_PUBLISHED,
    CANCELLED
}

enum PlanStatus {
    DRAFT,
    PUBLISHED,
    CONFIRMED,
    EXPIRED,
    WITHDRAWN
}

enum ServiceOrderStatus {
    ARRANGING,
    IN_PROGRESS,
    DELIVERED,
    ACCEPTED
}

record PlanReservation(
        UUID id,
        String reservationNo,
        UUID customerId,
        UUID providerId,
        ReservationPlanStatus status,
        UUID serviceId,
        String serviceTitle,
        String serviceCoverUrl,
        String cityCode,
        String address,
        Double longitude,
        Double latitude,
        Long lineLengthMeters,
        List<CodeLabel> inspectionItems,
        int version
) {
    PlanReservation withStatusAndVersion(ReservationPlanStatus nextStatus, int nextVersion) {
        return new PlanReservation(
                id,
                reservationNo,
                customerId,
                providerId,
                nextStatus,
                serviceId,
                serviceTitle,
                serviceCoverUrl,
                cityCode,
                address,
                longitude,
                latitude,
                lineLengthMeters,
                inspectionItems,
                nextVersion);
    }
}

record StoredServicePlan(
        UUID id,
        String planNo,
        PlanReservation reservation,
        UUID providerId,
        PlanStatus status,
        int planVersion,
        Instant validUntil,
        LocalDate serviceDate,
        String summary,
        List<PlanItem> planItems,
        List<CodeLabel> deliverables,
        long totalAmountMinor,
        String currency,
        Instant publishedAt,
        Instant confirmedAt,
        UUID serviceOrderId,
        int version
) {
    StoredServicePlan withStatus(PlanStatus nextStatus) {
        return new StoredServicePlan(
                id,
                planNo,
                reservation,
                providerId,
                nextStatus,
                planVersion,
                validUntil,
                serviceDate,
                summary,
                planItems,
                deliverables,
                totalAmountMinor,
                currency,
                publishedAt,
                confirmedAt,
                serviceOrderId,
                version + 1);
    }

    StoredServicePlan withValidUntil(Instant nextValidUntil) {
        return new StoredServicePlan(
                id,
                planNo,
                reservation,
                providerId,
                status,
                planVersion,
                nextValidUntil,
                serviceDate,
                summary,
                planItems,
                deliverables,
                totalAmountMinor,
                currency,
                publishedAt,
                confirmedAt,
                serviceOrderId,
                version);
    }

    StoredServicePlan confirmed(Instant at, UUID orderId) {
        return new StoredServicePlan(
                id,
                planNo,
                reservation,
                providerId,
                PlanStatus.CONFIRMED,
                planVersion,
                validUntil,
                serviceDate,
                summary,
                planItems,
                deliverables,
                totalAmountMinor,
                currency,
                publishedAt,
                at,
                orderId,
                version + 1);
    }
}

record ServicePlanView(
        UUID id,
        String planNo,
        UUID reservationId,
        UUID providerId,
        PlanStatus status,
        int planVersion,
        Instant validUntil,
        LocalDate serviceDate,
        String summary,
        List<PlanItem> planItems,
        List<CodeLabel> deliverables,
        long totalAmountMinor,
        String currency,
        Instant publishedAt,
        Instant confirmedAt,
        UUID serviceOrderId,
        int version
) {
}

record ServiceSnapshot(
        UUID serviceId,
        String serviceTitle,
        String serviceCoverUrl,
        String cityCode,
        String address,
        Double longitude,
        Double latitude,
        Long lineLengthMeters,
        LocalDate serviceDate,
        List<CodeLabel> inspectionItems
) {
}

record StoredServiceOrder(
        UUID id,
        String serviceOrderNo,
        UUID reservationId,
        UUID planId,
        UUID customerId,
        UUID providerId,
        ServiceOrderStatus status,
        ServiceSnapshot serviceSnapshot,
        Instant plannedStartAt,
        List<OrderAssignment> assignments,
        List<OrderProgressEvent> progress,
        Instant createdAt,
        Instant deliveredAt,
        Instant acceptedAt,
        int version
) {
    StoredServiceOrder(
            UUID id,
            String serviceOrderNo,
            UUID reservationId,
            UUID planId,
            UUID customerId,
            UUID providerId,
            ServiceOrderStatus status,
            ServiceSnapshot serviceSnapshot,
            Instant createdAt,
            int version
    ) {
        this(
                id,
                serviceOrderNo,
                reservationId,
                planId,
                customerId,
                providerId,
                status,
                serviceSnapshot,
                null,
                List.of(),
                List.of(),
                createdAt,
                null,
                null,
                version);
    }
}

record ServiceOrderView(
        UUID id,
        String serviceOrderNo,
        UUID reservationId,
        UUID planId,
        UUID customerId,
        UUID providerId,
        ServiceOrderStatus status,
        ServiceSnapshot serviceSnapshot,
        Instant plannedStartAt,
        List<OrderAssignment> assignments,
        List<OrderProgressEvent> progress,
        Instant createdAt,
        Instant deliveredAt,
        Instant acceptedAt,
        int version
) {
}

enum AssignmentType {
    TEAM_MEMBER,
    DEVICE
}

record OrderAssignment(
        AssignmentType type,
        UUID resourceId,
        String displayName,
        String roleLabel
) {
}

enum OrderProgressCode {
    ARRANGEMENT_READY,
    SERVICE_STARTED,
    SERVICE_PROGRESS,
    SERVICE_COMPLETED
}

record OrderProgressEvent(
        UUID id,
        OrderProgressCode eventCode,
        Instant occurredAt,
        String message
) {
}

record PlanIdempotencyEntry(
        UUID actorId,
        String operation,
        UUID key,
        String requestDigest,
        UUID resourceId
) {
}

enum PlanWriteResult {
    SAVED,
    IDEMPOTENCY_EXISTS,
    VERSION_CONFLICT,
    STATE_CONFLICT,
    EXPIRED
}

interface PlanStore {
    Optional<PlanReservation> findReservation(UUID reservationId);

    default Optional<StoredServicePlan> findCurrentPlan(UUID reservationId) {
        return Optional.empty();
    }

    Optional<StoredServicePlan> findPlan(UUID planId);

    Optional<StoredServiceOrder> findOrder(UUID orderId);

    Optional<PlanIdempotencyEntry> findIdempotency(
            UUID actorId,
            String operation,
            UUID key);

    int nextPlanVersion(UUID reservationId);

    PlanWriteResult publish(
            PlanReservation expectedReservation,
            StoredServicePlan plan,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId);

    PlanWriteResult confirm(
            StoredServicePlan expectedPlan,
            StoredServiceOrder order,
            PlanIdempotencyEntry entry,
            String agreementVersion,
            CurrentSubject actor,
            String requestId);

    default PlanWriteResult arrange(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder arrangedOrder,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        return PlanWriteResult.STATE_CONFLICT;
    }

    default PlanWriteResult appendProgress(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder updatedOrder,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        return PlanWriteResult.STATE_CONFLICT;
    }
}

final class PlanValidationException extends PlanFlowException {
    PlanValidationException() {
        super(PlanFlowError.INVALID_ARGUMENT);
    }
}

final class PlanForbiddenException extends PlanFlowException {
    PlanForbiddenException() {
        super(PlanFlowError.FORBIDDEN);
    }
}

final class PlanResourceNotFoundException extends PlanFlowException {
    PlanResourceNotFoundException() {
        super(PlanFlowError.NOT_FOUND);
    }
}

final class PlanIdempotencyConflictException extends PlanFlowException {
    PlanIdempotencyConflictException() {
        super(PlanFlowError.IDEMPOTENCY_CONFLICT);
    }
}

final class PlanVersionConflictException extends PlanFlowException {
    PlanVersionConflictException() {
        super(PlanFlowError.VERSION_CONFLICT);
    }
}

final class ReservationStateInvalidException extends PlanFlowException {
    ReservationStateInvalidException() {
        super(PlanFlowError.RESERVATION_STATE_INVALID);
    }
}

final class PlanNotPublishedException extends PlanFlowException {
    PlanNotPublishedException() {
        super(PlanFlowError.PLAN_NOT_PUBLISHED);
    }
}

final class PlanExpiredException extends PlanFlowException {
    PlanExpiredException() {
        super(PlanFlowError.PLAN_EXPIRED);
    }
}

final class PlanAlreadyConfirmedException extends PlanFlowException {
    PlanAlreadyConfirmedException() {
        super(PlanFlowError.PLAN_ALREADY_CONFIRMED);
    }
}

final class OrderStateInvalidException extends PlanFlowException {
    OrderStateInvalidException() {
        super(PlanFlowError.ORDER_STATE_INVALID);
    }
}
