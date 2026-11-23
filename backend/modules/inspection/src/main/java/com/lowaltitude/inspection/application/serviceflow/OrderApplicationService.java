package com.lowaltitude.inspection.application.serviceflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.transaction.annotation.Transactional;

/** Owns provider arrangement, visit scheduling and fulfillment progress for one service order. */
public class OrderApplicationService {

    static final String ARRANGE_OPERATION = "inspection.order.arrange";
    static final String SCHEDULE_OPERATION = "inspection.visit.schedule";
    static final String PROGRESS_OPERATION = "inspection.order.progress";
    private static final int MAX_ASSIGNMENTS = 30;
    private static final Pattern MOBILE = Pattern.compile("^1[3-9][0-9]{9}$");

    private final PlanStore store;
    private final Clock clock;

    public OrderApplicationService(PlanStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public ServiceOrderView read(CurrentSubject subject, UUID orderId) {
        if (subject == null || orderId == null) {
            throw new PlanResourceNotFoundException();
        }
        StoredServiceOrder order =
                store.findOrder(orderId).orElseThrow(PlanResourceNotFoundException::new);
        boolean own = subject.roles().contains("CUSTOMER")
                && subject.permissions().contains("inspection.order.read.own")
                && order.customerId().equals(subject.subjectId());
        boolean assigned = subject.roles().contains("PROVIDER_OPERATOR")
                && subject.permissions().contains("inspection.order.read.assigned")
                && order.providerId().equals(subject.organizationId());
        boolean platform = subject.roles().contains("PLATFORM_OPERATOR")
                && subject.permissions().contains("inspection.order.read.all");
        if (!own && !assigned && !platform) {
            throw new PlanResourceNotFoundException();
        }
        return ServiceOrderView.from(order, store);
    }

    @Transactional
    public ServiceOrderView arrange(
            CurrentSubject subject,
            UUID orderId,
            UUID idempotencyKey,
            int expectedVersion,
            PutArrangementCommand command,
            String requestId
    ) {
        requireAssignedProvider(subject, "inspection.order.arrange.assigned");
        validateArrangement(orderId, idempotencyKey, expectedVersion, command, requestId);
        store.lockOrder(orderId);
        StoredServiceOrder order =
                store.findOrder(orderId).orElseThrow(PlanResourceNotFoundException::new);
        requireProviderOwns(order, subject);

        String requestDigest = digestArrangement(orderId, command);
        Optional<PlanIdempotencyEntry> prior =
                store.findIdempotency(subject.subjectId(), ARRANGE_OPERATION, idempotencyKey);
        if (prior.isPresent()) {
            return replayOrder(prior.get(), requestDigest);
        }
        if (order.version() != expectedVersion) {
            throw new PlanVersionConflictException();
        }
        requireActive(order);

        Instant now = clock.instant();
        List<AssignmentWrite> writes = command.assignments().stream()
                .map(input -> new AssignmentWrite(
                        new OrderAssignment(
                                input.type(),
                                input.resourceId(),
                                input.displayName().trim(),
                                normalize(input.roleLabel()),
                                maskMobile(normalize(input.contactMobile()))),
                        normalize(input.contactMobile())))
                .toList();
        List<OrderProgressEvent> progress = new ArrayList<>(order.progress());
        progress.add(new OrderProgressEvent(
                UUID.randomUUID(),
                OrderProgressCode.ARRANGEMENT_READY,
                now,
                "作业团队与设备已安排"));
        StoredServiceOrder arranged = copy(
                order,
                order.status(),
                writes.stream().map(AssignmentWrite::assignment).toList(),
                progress,
                order.version() + 1);
        PlanIdempotencyEntry entry = new PlanIdempotencyEntry(
                subject.subjectId(),
                ARRANGE_OPERATION,
                idempotencyKey,
                requestDigest,
                order.id());
        PlanWriteResult result =
                store.arrange(order, arranged, writes, entry, subject, requestId.trim());
        if (result == PlanWriteResult.IDEMPOTENCY_EXISTS) {
            return replayOrder(savedEntry(entry), requestDigest);
        }
        requireSaved(result);
        return ServiceOrderView.from(arranged, store);
    }

    @Transactional
    public StoredServiceVisit schedule(
            CurrentSubject subject,
            UUID orderId,
            UUID visitId,
            UUID idempotencyKey,
            int expectedVersion,
            PutVisitScheduleCommand command,
            String requestId
    ) {
        requireAssignedProvider(subject, "inspection.order.arrange.assigned");
        if (orderId == null
                || visitId == null
                || idempotencyKey == null
                || expectedVersion < 0
                || command == null
                || command.plannedStartAt() == null
                || !validText(requestId, 1, 80)) {
            throw new PlanValidationException();
        }
        store.lockOrder(orderId);
        StoredServiceOrder order =
                store.findOrder(orderId).orElseThrow(PlanResourceNotFoundException::new);
        requireProviderOwns(order, subject);
        StoredServiceVisit visit =
                order.visit(visitId).orElseThrow(PlanResourceNotFoundException::new);

        String requestDigest = sha256(visitId + "|" + command.plannedStartAt());
        Optional<PlanIdempotencyEntry> prior =
                store.findIdempotency(subject.subjectId(), SCHEDULE_OPERATION, idempotencyKey);
        if (prior.isPresent()) {
            return replayVisit(prior.get(), requestDigest);
        }
        if (visit.version() != expectedVersion) {
            throw new PlanVersionConflictException();
        }
        requireActive(order);
        if (visit.status() != ServiceVisitStatus.PENDING_SCHEDULE
                && visit.status() != ServiceVisitStatus.SCHEDULED) {
            throw new VisitStateInvalidException();
        }

        StoredServiceVisit scheduled = visit.scheduled(command.plannedStartAt());
        PlanIdempotencyEntry entry = new PlanIdempotencyEntry(
                subject.subjectId(),
                SCHEDULE_OPERATION,
                idempotencyKey,
                requestDigest,
                visit.id());
        PlanWriteResult result = store.schedule(
                order,
                visit,
                scheduled,
                entry,
                clock.instant(),
                subject,
                requestId.trim());
        if (result == PlanWriteResult.IDEMPOTENCY_EXISTS) {
            return replayVisit(savedEntry(entry), requestDigest);
        }
        requireSaved(result);
        return scheduled;
    }

    @Transactional
    public StoredServiceVisit appendProgress(
            CurrentSubject subject,
            UUID orderId,
            UUID visitId,
            UUID idempotencyKey,
            int expectedVersion,
            AppendProgressCommand command,
            String requestId
    ) {
        requireAssignedProvider(subject, "inspection.order.progress.assigned");
        validateProgress(orderId, visitId, idempotencyKey, expectedVersion, command, requestId);
        store.lockOrder(orderId);
        StoredServiceOrder order =
                store.findOrder(orderId).orElseThrow(PlanResourceNotFoundException::new);
        requireProviderOwns(order, subject);
        StoredServiceVisit visit =
                order.visit(visitId).orElseThrow(PlanResourceNotFoundException::new);

        String requestDigest = digestProgress(visitId, command);
        Optional<PlanIdempotencyEntry> prior =
                store.findIdempotency(subject.subjectId(), PROGRESS_OPERATION, idempotencyKey);
        if (prior.isPresent()) {
            return replayVisit(prior.get(), requestDigest);
        }
        if (visit.version() != expectedVersion) {
            throw new PlanVersionConflictException();
        }
        requireActive(order);
        validateProgressTransition(order, visit, command);

        boolean starting = command.eventCode() == OrderProgressCode.SERVICE_STARTED;
        List<OrderProgressEvent> progress = new ArrayList<>(visit.progress());
        progress.add(new OrderProgressEvent(
                UUID.randomUUID(),
                command.eventCode(),
                command.occurredAt(),
                command.message().trim()));
        StoredServiceVisit updated = visit.withProgress(
                starting ? ServiceVisitStatus.IN_PROGRESS : visit.status(),
                progress,
                starting ? command.occurredAt() : visit.startedAt());
        ServiceOrderStatus nextOrderStatus = ServiceOrderProgression.derive(
                ServiceOrderProgression.replace(order.visits(), updated));
        PlanIdempotencyEntry entry = new PlanIdempotencyEntry(
                subject.subjectId(),
                PROGRESS_OPERATION,
                idempotencyKey,
                requestDigest,
                visit.id());
        PlanWriteResult result = store.appendProgress(
                order,
                nextOrderStatus,
                visit,
                updated,
                entry,
                subject,
                requestId.trim());
        if (result == PlanWriteResult.IDEMPOTENCY_EXISTS) {
            return replayVisit(savedEntry(entry), requestDigest);
        }
        requireSaved(result);
        return updated;
    }

    private PlanIdempotencyEntry savedEntry(PlanIdempotencyEntry entry) {
        return store.findIdempotency(entry.actorId(), entry.operation(), entry.key())
                .orElseThrow(PlanIdempotencyConflictException::new);
    }

    private static void requireSaved(PlanWriteResult result) {
        if (result == PlanWriteResult.VERSION_CONFLICT) {
            throw new PlanVersionConflictException();
        }
        if (result != PlanWriteResult.SAVED) {
            throw new OrderStateInvalidException();
        }
    }

    private ServiceOrderView replayOrder(PlanIdempotencyEntry prior, String requestDigest) {
        if (!prior.requestDigest().equals(requestDigest)) {
            throw new PlanIdempotencyConflictException();
        }
        return ServiceOrderView.from(
                store.findOrder(prior.resourceId())
                        .orElseThrow(PlanResourceNotFoundException::new),
                store);
    }

    private StoredServiceVisit replayVisit(PlanIdempotencyEntry prior, String requestDigest) {
        if (!prior.requestDigest().equals(requestDigest)) {
            throw new PlanIdempotencyConflictException();
        }
        return store.findVisit(prior.resourceId())
                .orElseThrow(PlanResourceNotFoundException::new);
    }

    private static void validateArrangement(
            UUID orderId,
            UUID idempotencyKey,
            int expectedVersion,
            PutArrangementCommand command,
            String requestId
    ) {
        if (orderId == null
                || idempotencyKey == null
                || expectedVersion < 0
                || command == null
                || !validText(requestId, 1, 80)
                || !validAssignments(command.assignments())) {
            throw new PlanValidationException();
        }
    }

    private static boolean validAssignments(List<AssignmentInput> assignments) {
        if (assignments == null
                || assignments.isEmpty()
                || assignments.size() > MAX_ASSIGNMENTS) {
            return false;
        }
        boolean hasTeam = false;
        boolean hasDevice = false;
        Set<String> identities = new HashSet<>();
        for (AssignmentInput input : assignments) {
            if (input == null
                    || input.type() == null
                    || input.resourceId() == null
                    || !validText(input.displayName(), 1, 120)
                    || input.roleLabel() != null && input.roleLabel().length() > 80
                    || !validMobile(input.contactMobile())
                    || !identities.add(input.type() + ":" + input.resourceId())) {
                return false;
            }
            hasTeam |= input.type() == AssignmentType.TEAM_MEMBER;
            hasDevice |= input.type() == AssignmentType.DEVICE;
        }
        return hasTeam && hasDevice;
    }

    private static boolean validMobile(String value) {
        String normalized = normalize(value);
        return normalized == null || MOBILE.matcher(normalized).matches();
    }

    private static String maskMobile(String value) {
        return value == null
                ? null
                : value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private static void validateProgress(
            UUID orderId,
            UUID visitId,
            UUID idempotencyKey,
            int expectedVersion,
            AppendProgressCommand command,
            String requestId
    ) {
        if (orderId == null
                || visitId == null
                || idempotencyKey == null
                || expectedVersion < 0
                || command == null
                || command.eventCode() == null
                || command.occurredAt() == null
                || !validText(command.message(), 1, 500)
                || !validText(requestId, 1, 80)) {
            throw new PlanValidationException();
        }
    }

    private static void validateProgressTransition(
            StoredServiceOrder order,
            StoredServiceVisit visit,
            AppendProgressCommand command
    ) {
        OrderProgressCode code = command.eventCode();
        if (code == OrderProgressCode.ARRANGEMENT_READY
                || visit.status() == ServiceVisitStatus.DELIVERED
                || visit.status() == ServiceVisitStatus.ACCEPTED
                || visit.serviceCompleted()
                || isBeforeEarlierEvents(order, visit, command.occurredAt())) {
            throw new VisitStateInvalidException();
        }
        if (code == OrderProgressCode.SERVICE_STARTED) {
            if (visit.status() != ServiceVisitStatus.SCHEDULED
                    || order.assignments().stream()
                            .noneMatch(item -> item.type() == AssignmentType.TEAM_MEMBER)
                    || order.assignments().stream()
                            .noneMatch(item -> item.type() == AssignmentType.DEVICE)) {
                throw new VisitStateInvalidException();
            }
            requirePreviousVisitCompleted(order, visit);
            return;
        }
        if (visit.status() != ServiceVisitStatus.IN_PROGRESS) {
            throw new VisitStateInvalidException();
        }
    }

    /**
     * Events stay in time order across the whole order: a visit's first event follows the
     * previous visit, and the first visit follows the arrangement.
     */
    private static boolean isBeforeEarlierEvents(
            StoredServiceOrder order,
            StoredServiceVisit visit,
            Instant occurredAt
    ) {
        List<OrderProgressEvent> earlier = !visit.progress().isEmpty()
                ? visit.progress()
                : order.visits().stream()
                        .filter(other -> other.visitNo() == visit.visitNo() - 1)
                        .findFirst()
                        .map(StoredServiceVisit::progress)
                        .orElse(order.progress());
        return !earlier.isEmpty()
                && occurredAt.isBefore(earlier.get(earlier.size() - 1).occurredAt());
    }

    private static void requirePreviousVisitCompleted(
            StoredServiceOrder order,
            StoredServiceVisit visit
    ) {
        boolean previousUnfinished = order.visits().stream()
                .anyMatch(other -> other.visitNo() == visit.visitNo() - 1
                        && !other.serviceCompleted());
        if (previousUnfinished) {
            throw new VisitOutOfOrderException();
        }
    }

    /**
     * 首付到账之前（待付款）、已取消、已全部验收的服务单，服务商不能安排、排期或推进（S4-1）。
     * 放在幂等重放之后：同一个命令重放仍返回原结果。
     */
    private static void requireActive(StoredServiceOrder order) {
        if (order.status() == ServiceOrderStatus.AWAITING_PAYMENT
                || order.status() == ServiceOrderStatus.CANCELLED
                || order.status() == ServiceOrderStatus.ACCEPTED) {
            throw new OrderStateInvalidException();
        }
    }

    private static void requireAssignedProvider(CurrentSubject subject, String permission) {
        if (subject == null
                || !subject.roles().contains("PROVIDER_OPERATOR")
                || !subject.permissions().contains(permission)
                || subject.organizationId() == null) {
            throw new PlanForbiddenException();
        }
    }

    private static void requireProviderOwns(
            StoredServiceOrder order,
            CurrentSubject subject
    ) {
        if (!order.providerId().equals(subject.organizationId())) {
            throw new PlanResourceNotFoundException();
        }
    }

    private static StoredServiceOrder copy(
            StoredServiceOrder source,
            ServiceOrderStatus status,
            List<OrderAssignment> assignments,
            List<OrderProgressEvent> progress,
            int version
    ) {
        return new StoredServiceOrder(
                source.id(),
                source.serviceOrderNo(),
                source.reservationId(),
                source.planId(),
                source.customerId(),
                source.providerId(),
                source.paymentMode(),
                status,
                source.serviceSnapshot(),
                List.copyOf(assignments),
                List.copyOf(progress),
                source.visits(),
                source.paymentNodes(),
                source.createdAt(),
                source.deliveredAt(),
                source.acceptedAt(),
                version);
    }

    private static String digestArrangement(UUID orderId, PutArrangementCommand command) {
        StringBuilder value = new StringBuilder(orderId.toString());
        command.assignments().forEach(item -> value.append('|')
                .append(item.type()).append(':')
                .append(item.resourceId()).append(':')
                .append(item.displayName().trim()).append(':')
                .append(normalize(item.roleLabel())).append(':')
                .append(normalize(item.contactMobile())));
        return sha256(value.toString());
    }

    private static String digestProgress(UUID visitId, AppendProgressCommand command) {
        return sha256(visitId
                + "|" + command.eventCode()
                + "|" + command.occurredAt()
                + "|" + command.message().trim());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean validText(String value, int min, int max) {
        return value != null
                && value.trim().length() >= min
                && value.trim().length() <= max;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

record PutArrangementCommand(List<AssignmentInput> assignments) {
}

record PutVisitScheduleCommand(Instant plannedStartAt) {
}

record AssignmentInput(
        AssignmentType type,
        UUID resourceId,
        String displayName,
        String roleLabel,
        String contactMobile
) {
}

record AppendProgressCommand(
        OrderProgressCode eventCode,
        Instant occurredAt,
        String message
) {
}
