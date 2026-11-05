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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.domain.files.DeliveryFileType;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.transaction.annotation.Transactional;

/** Owns the published-plan state machine and creates one service order on confirmation. */
public class PlanApplicationService {

    static final String PUBLISH_OPERATION = "inspection.plan.publish";
    static final String CONFIRM_OPERATION = "inspection.plan.confirm";
    private static final int MAX_PLAN_ITEMS = 50;
    private static final int MAX_DELIVERABLES = 20;
    private static final int MIN_DELIVERABLES = 1;
    private static final int MAX_VISITS = 52;
    private static final int MAX_SERVICE_METHODS = 10;
    private static final int MAX_REPORT_DUE_HOURS = 8_760;
    private static final int MAX_MEDIA_RETENTION_DAYS = 3_650;

    private final PlanStore store;
    private final Clock clock;
    private final boolean paymentNodesEnabled;

    public PlanApplicationService(PlanStore store, Clock clock) {
        this(store, clock, false);
    }

    public PlanApplicationService(
            PlanStore store,
            Clock clock,
            boolean paymentNodesEnabled
    ) {
        this.store = store;
        this.clock = clock;
        this.paymentNodesEnabled = paymentNodesEnabled;
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

        String requestDigest = digestPublish(reservationId, command);
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
        List<PlanVisit> visits = new ArrayList<>();
        for (LocalDate plannedDate : command.visits()) {
            visits.add(new PlanVisit(visits.size() + 1, plannedDate));
        }
        PlanTerms terms = new PlanTerms(
                List.copyOf(visits),
                normalize(command.scopeSummary()),
                command.serviceMethods().stream()
                        .map(item -> new CodeLabel(item.code().trim(), item.label().trim()))
                        .toList(),
                command.deliveryTerms(),
                normalize(command.changeNote()),
                paymentNodesEnabled
                        ? PaymentNodePlanner.plan(total, visits.size())
                        : List.of());
        StoredServicePlan plan = new StoredServicePlan(
                planId,
                planNumber(now, planId),
                reservation,
                subject.organizationId(),
                PlanStatus.PUBLISHED,
                store.nextPlanVersion(reservationId),
                command.validUntil(),
                command.visits().get(0),
                normalize(command.summary()),
                terms,
                items,
                command.deliverables().stream()
                        .map(item -> new PlanDeliverable(
                                item.code().trim(),
                                item.name().trim(),
                                item.fileType()))
                        .toList(),
                total,
                "CNY",
                now,
                null,
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
        requirePlanReader(subject, plan.reservation());
        return toView(plan);
    }

    public List<PlanVersionSummary> listVersions(CurrentSubject subject, UUID reservationId) {
        if (subject == null || reservationId == null) {
            throw new PlanResourceNotFoundException();
        }
        requirePlanReader(
                subject,
                store.findReservation(reservationId)
                        .orElseThrow(PlanResourceNotFoundException::new));
        return store.findPlanVersions(reservationId);
    }

    private static void requirePlanReader(CurrentSubject subject, PlanReservation reservation) {
        boolean own = subject.roles().contains("CUSTOMER")
                && subject.permissions().contains("inspection.plan.read.own")
                && reservation.customerId().equals(subject.subjectId());
        boolean platform = subject.roles().contains("PLATFORM_OPERATOR")
                && subject.permissions().contains("inspection.plan.read.all");
        if (!own && !platform) {
            throw new PlanResourceNotFoundException();
        }
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

        String requestDigest = digestConfirm(planId, command);
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
        List<StoredServiceVisit> visits = plan.terms().visits().stream()
                .map(visit -> StoredServiceVisit.planned(UUID.randomUUID(), orderId, visit))
                .toList();
        StoredServiceOrder order = new StoredServiceOrder(
                orderId,
                orderNumber(now, orderId),
                plan.reservation().id(),
                plan.id(),
                plan.reservation().customerId(),
                plan.providerId(),
                plan.terms().paymentNodes().isEmpty() ? PaymentMode.NONE : PaymentMode.NODES,
                ServiceOrderStatus.ARRANGING,
                snapshot,
                visits,
                orderPaymentNodes(plan.terms().paymentNodes(), visits),
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

    private static List<OrderPaymentNode> orderPaymentNodes(
            List<PlanPaymentNode> nodes,
            List<StoredServiceVisit> visits
    ) {
        return nodes.stream()
                .map(node -> new OrderPaymentNode(
                        node.nodeNo(),
                        node.nodeType(),
                        node.visitNo(),
                        node.visitNo() == null
                                ? null
                                : visits.get(node.visitNo() - 1).id(),
                        node.amountMinor(),
                        null))
                .toList();
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
                || !validVisits(command.visits())
                || command.summary() != null && command.summary().length() > 1_000
                || command.scopeSummary() != null && command.scopeSummary().length() > 120
                || command.changeNote() != null && command.changeNote().length() > 200
                || !validServiceMethods(command.serviceMethods())
                || !validDeliveryTerms(command.deliveryTerms())
                || !"CNY".equals(command.currency())
                || !validPlanItems(command.planItems())
                || !validDeliverables(command.deliverables())
                || !validText(requestId, 1, 80)) {
            throw new PlanValidationException();
        }
    }

    private boolean validVisits(List<LocalDate> visits) {
        if (visits == null || visits.isEmpty() || visits.size() > MAX_VISITS) {
            return false;
        }
        LocalDate previous = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
        for (LocalDate visit : visits) {
            if (visit == null || !visit.isAfter(previous)) {
                return false;
            }
            previous = visit;
        }
        return true;
    }

    private static boolean validServiceMethods(List<CodeLabel> methods) {
        if (methods == null || methods.size() > MAX_SERVICE_METHODS) {
            return false;
        }
        Set<String> codes = new HashSet<>();
        return methods.stream().allMatch(item ->
                item != null
                        && validText(item.code(), 1, 64)
                        && validText(item.label(), 1, 120)
                        && codes.add(item.code().trim()));
    }

    private static boolean validDeliveryTerms(DeliveryTerms terms) {
        return terms == null
                || validRange(terms.reportDueHours(), MAX_REPORT_DUE_HOURS)
                && validRange(terms.mediaRetentionDays(), MAX_MEDIA_RETENTION_DAYS);
    }

    private static boolean validRange(Integer value, int max) {
        return value == null || value > 0 && value <= max;
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
        Set<DeliveryFileType> fileTypes = new HashSet<>();
        return deliverables.stream().allMatch(item ->
                item != null
                        && validText(item.code(), 1, 64)
                        && validText(item.name(), 1, 120)
                        && codes.add(item.code().trim())
                        && (item.fileType() == null || fileTypes.add(item.fileType())));
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

    private static String digestPublish(UUID reservationId, PublishPlanCommand command) {
        return digest(List.of(
                reservationId,
                command.providerId(),
                command.validUntil(),
                command.visits(),
                String.valueOf(command.summary()),
                String.valueOf(command.scopeSummary()),
                command.serviceMethods(),
                String.valueOf(command.deliveryTerms()),
                String.valueOf(command.changeNote()),
                command.planItems(),
                command.deliverables(),
                command.currency()));
    }

    private static String digestConfirm(UUID planId, ConfirmPlanCommand command) {
        return digest(List.of(planId, command.agreementVersion(), command.agreementAccepted()));
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

    private ServicePlanView toView(StoredServicePlan plan) {
        return new ServicePlanView(
                plan.id(),
                plan.planNo(),
                plan.reservation().id(),
                plan.providerId(),
                store.findProvider(plan.providerId()).orElse(null),
                plan.status(),
                plan.planVersion(),
                plan.validUntil(),
                plan.serviceDate(),
                plan.summary(),
                plan.terms(),
                plan.planItems(),
                plan.deliverables(),
                plan.totalAmountMinor(),
                plan.currency(),
                plan.publishedAt(),
                plan.confirmedAt(),
                plan.confirmedBy() == null
                        ? null
                        : new PersonRef(
                                plan.confirmedBy(),
                                store.findDisplayName(plan.confirmedBy()).orElse("")),
                plan.serviceOrderId(),
                plan.version());
    }

    private ServiceOrderView toView(StoredServiceOrder order) {
        return ServiceOrderView.from(order, store);
    }
}

record PublishPlanCommand(
        UUID providerId,
        Instant validUntil,
        List<LocalDate> visits,
        String summary,
        String scopeSummary,
        List<CodeLabel> serviceMethods,
        DeliveryTerms deliveryTerms,
        String changeNote,
        List<PlanItemInput> planItems,
        List<DeliverableInput> deliverables,
        String currency
) {
}

record ConfirmPlanCommand(String agreementVersion, boolean agreementAccepted) {
}

record PlanItemInput(String code, String name, int quantity, String unit, long unitPriceMinor) {
}

record DeliverableInput(String code, String name, DeliveryFileType fileType) {
}

record PlanDeliverable(String code, String label, DeliveryFileType fileType) {
}

record DeliveryTerms(Integer reportDueHours, Integer mediaRetentionDays) {
}

record PlanVisit(int visitNo, LocalDate plannedDate) {
}

enum PaymentMode {
    NONE,
    NODES
}

enum PaymentNodeType {
    DOWN_PAYMENT,
    PER_VISIT
}

record PlanPaymentNode(
        int nodeNo,
        PaymentNodeType nodeType,
        Integer visitNo,
        long amountMinor
) {
}

record PlanTerms(
        List<PlanVisit> visits,
        String scopeSummary,
        List<CodeLabel> serviceMethods,
        DeliveryTerms deliveryTerms,
        String changeNote,
        List<PlanPaymentNode> paymentNodes
) {
}

record ProviderSummary(UUID providerId, String name, String certificationLabel) {
}

record CustomerParty(String organizationName, String operatorName) {
}

record PersonRef(UUID subjectId, String displayName) {
}

record PlanVersionSummary(
        UUID id,
        String planNo,
        int planVersion,
        PlanStatus status,
        long totalAmountMinor,
        List<PlanItem> planItems,
        String changeNote,
        Instant publishedAt
) {
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
        PlanTerms terms,
        List<PlanItem> planItems,
        List<PlanDeliverable> deliverables,
        long totalAmountMinor,
        String currency,
        Instant publishedAt,
        Instant confirmedAt,
        UUID confirmedBy,
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
                terms,
                planItems,
                deliverables,
                totalAmountMinor,
                currency,
                publishedAt,
                confirmedAt,
                confirmedBy,
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
                terms,
                planItems,
                deliverables,
                totalAmountMinor,
                currency,
                publishedAt,
                confirmedAt,
                confirmedBy,
                serviceOrderId,
                version);
    }

    StoredServicePlan confirmed(Instant at, UUID actorId, UUID orderId) {
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
                terms,
                planItems,
                deliverables,
                totalAmountMinor,
                currency,
                publishedAt,
                at,
                actorId,
                orderId,
                version + 1);
    }
}

record ServicePlanView(
        UUID id,
        String planNo,
        UUID reservationId,
        UUID providerId,
        ProviderSummary provider,
        PlanStatus status,
        int planVersion,
        Instant validUntil,
        LocalDate serviceDate,
        String summary,
        PlanTerms terms,
        List<PlanItem> planItems,
        List<PlanDeliverable> deliverables,
        long totalAmountMinor,
        String currency,
        Instant publishedAt,
        Instant confirmedAt,
        PersonRef confirmedBy,
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
        PaymentMode paymentMode,
        ServiceOrderStatus status,
        ServiceSnapshot serviceSnapshot,
        List<OrderAssignment> assignments,
        List<OrderProgressEvent> progress,
        List<StoredServiceVisit> visits,
        List<OrderPaymentNode> paymentNodes,
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
            PaymentMode paymentMode,
            ServiceOrderStatus status,
            ServiceSnapshot serviceSnapshot,
            List<StoredServiceVisit> visits,
            List<OrderPaymentNode> paymentNodes,
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
                paymentMode,
                status,
                serviceSnapshot,
                List.of(),
                List.of(),
                visits,
                paymentNodes,
                createdAt,
                null,
                null,
                version);
    }

    Optional<StoredServiceVisit> visit(UUID visitId) {
        return visits.stream().filter(visit -> visit.id().equals(visitId)).findFirst();
    }
}

enum ServiceVisitStatus {
    PENDING_SCHEDULE,
    SCHEDULED,
    IN_PROGRESS,
    DELIVERED,
    ACCEPTED
}

record StoredServiceVisit(
        UUID id,
        UUID serviceOrderId,
        int visitNo,
        LocalDate plannedDate,
        Instant plannedStartAt,
        ServiceVisitStatus status,
        List<OrderProgressEvent> progress,
        Instant startedAt,
        Instant deliveredAt,
        Instant acceptedAt,
        int version
) {
    static StoredServiceVisit planned(UUID id, UUID serviceOrderId, PlanVisit visit) {
        return new StoredServiceVisit(
                id,
                serviceOrderId,
                visit.visitNo(),
                visit.plannedDate(),
                null,
                ServiceVisitStatus.PENDING_SCHEDULE,
                List.of(),
                null,
                null,
                null,
                0);
    }

    StoredServiceVisit scheduled(Instant nextPlannedStartAt) {
        return new StoredServiceVisit(
                id,
                serviceOrderId,
                visitNo,
                plannedDate,
                nextPlannedStartAt,
                ServiceVisitStatus.SCHEDULED,
                progress,
                startedAt,
                deliveredAt,
                acceptedAt,
                version + 1);
    }

    StoredServiceVisit withProgress(
            ServiceVisitStatus nextStatus,
            List<OrderProgressEvent> nextProgress,
            Instant nextStartedAt
    ) {
        return new StoredServiceVisit(
                id,
                serviceOrderId,
                visitNo,
                plannedDate,
                plannedStartAt,
                nextStatus,
                List.copyOf(nextProgress),
                nextStartedAt,
                deliveredAt,
                acceptedAt,
                version + 1);
    }

    StoredServiceVisit delivered(Instant at) {
        return new StoredServiceVisit(
                id,
                serviceOrderId,
                visitNo,
                plannedDate,
                plannedStartAt,
                ServiceVisitStatus.DELIVERED,
                progress,
                startedAt,
                at,
                acceptedAt,
                version + 1);
    }

    StoredServiceVisit accepted(Instant at) {
        return new StoredServiceVisit(
                id,
                serviceOrderId,
                visitNo,
                plannedDate,
                plannedStartAt,
                ServiceVisitStatus.ACCEPTED,
                progress,
                startedAt,
                deliveredAt,
                at,
                version + 1);
    }

    boolean serviceCompleted() {
        return progress.stream()
                .anyMatch(event -> event.eventCode() == OrderProgressCode.SERVICE_COMPLETED);
    }
}

record OrderPaymentNode(
        int nodeNo,
        PaymentNodeType nodeType,
        Integer visitNo,
        UUID visitId,
        long amountMinor,
        Instant paidAt
) {
}

record OrderPaymentNodeView(
        int nodeNo,
        PaymentNodeType nodeType,
        Integer visitNo,
        long amountMinor,
        boolean payable,
        Instant paidAt
) {
    static OrderPaymentNodeView from(OrderPaymentNode node, List<StoredServiceVisit> visits) {
        boolean due = node.nodeType() == PaymentNodeType.DOWN_PAYMENT
                || visits.stream().anyMatch(visit ->
                        visit.id().equals(node.visitId())
                                && visit.status() == ServiceVisitStatus.ACCEPTED);
        boolean payable = due && node.paidAt() == null;
        return new OrderPaymentNodeView(
                node.nodeNo(),
                node.nodeType(),
                node.visitNo(),
                node.amountMinor(),
                payable,
                node.paidAt());
    }
}

record ServiceOrderView(
        UUID id,
        String serviceOrderNo,
        UUID reservationId,
        UUID planId,
        UUID customerId,
        UUID providerId,
        ProviderSummary provider,
        CustomerParty customerParty,
        PaymentMode paymentMode,
        List<OrderPaymentNodeView> paymentNodes,
        ServiceOrderStatus status,
        ServiceSnapshot serviceSnapshot,
        List<OrderAssignment> assignments,
        List<OrderProgressEvent> progress,
        List<StoredServiceVisit> visits,
        Instant createdAt,
        Instant deliveredAt,
        Instant acceptedAt,
        int version
) {
    static ServiceOrderView from(StoredServiceOrder order, PlanStore store) {
        return new ServiceOrderView(
                order.id(),
                order.serviceOrderNo(),
                order.reservationId(),
                order.planId(),
                order.customerId(),
                order.providerId(),
                store.findProvider(order.providerId()).orElse(null),
                store.findCustomerParty(order.reservationId()).orElse(null),
                order.paymentMode(),
                order.paymentNodes().stream()
                        .map(node -> OrderPaymentNodeView.from(node, order.visits()))
                        .toList(),
                order.status(),
                order.serviceSnapshot(),
                order.assignments(),
                order.progress(),
                order.visits(),
                order.createdAt(),
                order.deliveredAt(),
                order.acceptedAt(),
                order.version());
    }
}

enum AssignmentType {
    TEAM_MEMBER,
    DEVICE
}

record OrderAssignment(
        AssignmentType type,
        UUID resourceId,
        String displayName,
        String roleLabel,
        String contactMobileMasked
) {
}

/** The real mobile only travels to the store, which encrypts it; views carry the mask. */
record AssignmentWrite(OrderAssignment assignment, String contactMobile) {
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

    default Optional<StoredServiceVisit> findVisit(UUID visitId) {
        return Optional.empty();
    }

    /** Serializes commands on one service order; a no-op outside a real database. */
    default void lockOrder(UUID orderId) {
    }

    default List<PlanVersionSummary> findPlanVersions(UUID reservationId) {
        return List.of();
    }

    default Optional<ProviderSummary> findProvider(UUID providerId) {
        return Optional.empty();
    }

    default Optional<CustomerParty> findCustomerParty(UUID reservationId) {
        return Optional.empty();
    }

    default Optional<String> findDisplayName(UUID subjectId) {
        return Optional.empty();
    }

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
            List<AssignmentWrite> assignments,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        return PlanWriteResult.STATE_CONFLICT;
    }

    default PlanWriteResult schedule(
            StoredServiceOrder order,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit scheduledVisit,
            PlanIdempotencyEntry entry,
            Instant at,
            CurrentSubject actor,
            String requestId
    ) {
        return PlanWriteResult.STATE_CONFLICT;
    }

    default PlanWriteResult appendProgress(
            StoredServiceOrder expectedOrder,
            ServiceOrderStatus nextOrderStatus,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit updatedVisit,
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

final class VisitStateInvalidException extends PlanFlowException {
    VisitStateInvalidException() {
        super(PlanFlowError.VISIT_STATE_INVALID);
    }
}

final class VisitOutOfOrderException extends PlanFlowException {
    VisitOutOfOrderException() {
        super(PlanFlowError.VISIT_OUT_OF_ORDER);
    }
}
