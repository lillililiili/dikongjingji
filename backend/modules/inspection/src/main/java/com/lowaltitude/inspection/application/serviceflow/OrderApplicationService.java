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

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.transaction.annotation.Transactional;

/** Owns provider arrangement and fulfillment progress for one confirmed service order. */
public class OrderApplicationService {

    static final String ARRANGE_OPERATION = "inspection.order.arrange";
    static final String PROGRESS_OPERATION = "inspection.order.progress";
    private static final int MAX_ASSIGNMENTS = 30;

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
        return toView(order);
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
        StoredServiceOrder order =
                store.findOrder(orderId).orElseThrow(PlanResourceNotFoundException::new);
        requireProviderOwns(order, subject);

        String requestDigest = digestArrangement(command);
        Optional<PlanIdempotencyEntry> prior =
                store.findIdempotency(subject.subjectId(), ARRANGE_OPERATION, idempotencyKey);
        if (prior.isPresent()) {
            return replay(prior.get(), requestDigest);
        }
        if (order.version() != expectedVersion) {
            throw new PlanVersionConflictException();
        }
        if (order.status() != ServiceOrderStatus.ARRANGING) {
            throw new OrderStateInvalidException();
        }

        Instant now = clock.instant();
        List<OrderAssignment> assignments = command.assignments().stream()
                .map(input -> new OrderAssignment(
                        input.type(),
                        input.resourceId(),
                        input.displayName().trim(),
                        normalize(input.roleLabel())))
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
                command.plannedStartAt(),
                assignments,
                progress,
                order.version() + 1);
        PlanIdempotencyEntry entry = new PlanIdempotencyEntry(
                subject.subjectId(),
                ARRANGE_OPERATION,
                idempotencyKey,
                requestDigest,
                order.id());
        PlanWriteResult result =
                store.arrange(order, arranged, entry, subject, requestId.trim());
        return resolveWrite(result, entry, requestDigest, arranged);
    }

    @Transactional
    public ServiceOrderView appendProgress(
            CurrentSubject subject,
            UUID orderId,
            UUID idempotencyKey,
            int expectedVersion,
            AppendProgressCommand command,
            String requestId
    ) {
        requireAssignedProvider(subject, "inspection.order.progress.assigned");
        validateProgress(orderId, idempotencyKey, expectedVersion, command, requestId);
        StoredServiceOrder order =
                store.findOrder(orderId).orElseThrow(PlanResourceNotFoundException::new);
        requireProviderOwns(order, subject);

        String requestDigest = digestProgress(command);
        Optional<PlanIdempotencyEntry> prior =
                store.findIdempotency(subject.subjectId(), PROGRESS_OPERATION, idempotencyKey);
        if (prior.isPresent()) {
            return replay(prior.get(), requestDigest);
        }
        if (order.version() != expectedVersion) {
            throw new PlanVersionConflictException();
        }
        validateProgressTransition(order, command);

        ServiceOrderStatus nextStatus = command.eventCode() == OrderProgressCode.SERVICE_STARTED
                ? ServiceOrderStatus.IN_PROGRESS
                : order.status();
        List<OrderProgressEvent> progress = new ArrayList<>(order.progress());
        progress.add(new OrderProgressEvent(
                UUID.randomUUID(),
                command.eventCode(),
                command.occurredAt(),
                command.message().trim()));
        StoredServiceOrder updated = copy(
                order,
                nextStatus,
                order.plannedStartAt(),
                order.assignments(),
                progress,
                order.version() + 1);
        PlanIdempotencyEntry entry = new PlanIdempotencyEntry(
                subject.subjectId(),
                PROGRESS_OPERATION,
                idempotencyKey,
                requestDigest,
                order.id());
        PlanWriteResult result =
                store.appendProgress(order, updated, entry, subject, requestId.trim());
        return resolveWrite(result, entry, requestDigest, updated);
    }

    private ServiceOrderView resolveWrite(
            PlanWriteResult result,
            PlanIdempotencyEntry entry,
            String requestDigest,
            StoredServiceOrder expected
    ) {
        if (result == PlanWriteResult.IDEMPOTENCY_EXISTS) {
            PlanIdempotencyEntry saved = store.findIdempotency(
                            entry.actorId(),
                            entry.operation(),
                            entry.key())
                    .orElseThrow(PlanIdempotencyConflictException::new);
            return replay(saved, requestDigest);
        }
        if (result == PlanWriteResult.VERSION_CONFLICT) {
            throw new PlanVersionConflictException();
        }
        if (result != PlanWriteResult.SAVED) {
            throw new OrderStateInvalidException();
        }
        return toView(expected);
    }

    private ServiceOrderView replay(PlanIdempotencyEntry prior, String requestDigest) {
        if (!prior.requestDigest().equals(requestDigest)) {
            throw new PlanIdempotencyConflictException();
        }
        return toView(store.findOrder(prior.resourceId())
                .orElseThrow(PlanResourceNotFoundException::new));
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
                || command.plannedStartAt() == null
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
                    || !identities.add(input.type() + ":" + input.resourceId())) {
                return false;
            }
            hasTeam |= input.type() == AssignmentType.TEAM_MEMBER;
            hasDevice |= input.type() == AssignmentType.DEVICE;
        }
        return hasTeam && hasDevice;
    }

    private static void validateProgress(
            UUID orderId,
            UUID idempotencyKey,
            int expectedVersion,
            AppendProgressCommand command,
            String requestId
    ) {
        if (orderId == null
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
            AppendProgressCommand command
    ) {
        OrderProgressCode code = command.eventCode();
        if (code == OrderProgressCode.ARRANGEMENT_READY
                || order.status() == ServiceOrderStatus.DELIVERED
                || order.status() == ServiceOrderStatus.ACCEPTED
                || order.progress().stream()
                        .anyMatch(event -> event.eventCode() == OrderProgressCode.SERVICE_COMPLETED)
                || !order.progress().isEmpty()
                        && command.occurredAt().isBefore(
                                order.progress().get(order.progress().size() - 1).occurredAt())) {
            throw new OrderStateInvalidException();
        }
        if (code == OrderProgressCode.SERVICE_STARTED) {
            if (order.status() != ServiceOrderStatus.ARRANGING
                    || order.assignments().stream()
                            .noneMatch(item -> item.type() == AssignmentType.TEAM_MEMBER)
                    || order.assignments().stream()
                            .noneMatch(item -> item.type() == AssignmentType.DEVICE)) {
                throw new OrderStateInvalidException();
            }
            return;
        }
        if (order.status() != ServiceOrderStatus.IN_PROGRESS) {
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
            Instant plannedStartAt,
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
                status,
                source.serviceSnapshot(),
                plannedStartAt,
                List.copyOf(assignments),
                List.copyOf(progress),
                source.createdAt(),
                source.deliveredAt(),
                source.acceptedAt(),
                version);
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

    private static String digestArrangement(PutArrangementCommand command) {
        StringBuilder value = new StringBuilder(command.plannedStartAt().toString());
        command.assignments().forEach(item -> value.append('|')
                .append(item.type()).append(':')
                .append(item.resourceId()).append(':')
                .append(item.displayName().trim()).append(':')
                .append(normalize(item.roleLabel())));
        return sha256(value.toString());
    }

    private static String digestProgress(AppendProgressCommand command) {
        return sha256(command.eventCode()
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

record PutArrangementCommand(
        Instant plannedStartAt,
        List<AssignmentInput> assignments
) {
}

record AssignmentInput(
        AssignmentType type,
        UUID resourceId,
        String displayName,
        String roleLabel
) {
}

record AppendProgressCommand(
        OrderProgressCode eventCode,
        Instant occurredAt,
        String message
) {
}
