package com.lowaltitude.inspection.application.serviceflow;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.reservation.AesGcmSensitiveDataCipher;
import com.lowaltitude.inspection.domain.files.DeliveryFileType;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.infrastructure.persistence.ServiceFlowPersistenceMapper;

final class PostgresPlanStore implements PlanStore {

    private final ServiceFlowPersistenceMapper mapper;
    private final AesGcmSensitiveDataCipher cipher;

    PostgresPlanStore(
            ServiceFlowPersistenceMapper mapper,
            AesGcmSensitiveDataCipher cipher
    ) {
        this.mapper = mapper;
        this.cipher = cipher;
    }

    @Override
    public Optional<PlanReservation> findReservation(UUID reservationId) {
        return mapper.findReservation(reservationId).map(this::toReservation);
    }

    @Override
    public Optional<StoredServicePlan> findCurrentPlan(UUID reservationId) {
        return mapper.findCurrentPlan(reservationId).flatMap(this::toPlan);
    }

    @Override
    public Optional<StoredServicePlan> findPlan(UUID planId) {
        return mapper.findPlan(planId).flatMap(this::toPlan);
    }

    @Override
    public Optional<StoredServiceOrder> findOrder(UUID orderId) {
        return mapper.findOrder(orderId).flatMap(row ->
                findReservation(row.reservationId()).flatMap(reservation ->
                        mapper.findPlan(row.planId()).map(plan ->
                                new StoredServiceOrder(
                                        row.id(),
                                        row.serviceOrderNo(),
                                        row.reservationId(),
                                        row.planId(),
                                        row.customerId(),
                                        row.providerId(),
                                        PaymentMode.valueOf(row.paymentMode()),
                                        ServiceOrderStatus.valueOf(row.status()),
                                        new ServiceSnapshot(
                                                reservation.serviceId(),
                                                reservation.serviceTitle(),
                                                reservation.serviceCoverUrl(),
                                                reservation.cityCode(),
                                                reservation.address(),
                                                reservation.longitude(),
                                                reservation.latitude(),
                                                reservation.lineLengthMeters(),
                                                plan.serviceDate(),
                                                reservation.inspectionItems()),
                                        mapper.findOrderAssignments(row.id()).stream()
                                                .map(item -> new OrderAssignment(
                                                        AssignmentType.valueOf(item.type()),
                                                        item.resourceId(),
                                                        item.displayName(),
                                                        item.roleLabel(),
                                                        item.contactMobileMasked(),
                                                        item.certifiedPilot()))
                                                .toList(),
                                        toProgress(mapper.findOrderProgress(row.id())),
                                        mapper.findOrderVisits(row.id()).stream()
                                                .map(this::toVisit)
                                                .toList(),
                                        mapper.findOrderPaymentNodes(row.id()).stream()
                                                .map(item -> new OrderPaymentNode(
                                                        item.nodeNo(),
                                                        PaymentNodeType.valueOf(item.nodeType()),
                                                        item.visitNo(),
                                                        item.visitId(),
                                                        item.amountMinor(),
                                                        item.paidAt()))
                                                .toList(),
                                        row.createdAt(),
                                        row.deliveredAt(),
                                        row.acceptedAt(),
                                        row.version()))));
    }

    @Override
    public Optional<StoredServiceVisit> findVisit(UUID visitId) {
        return mapper.findVisit(visitId).map(this::toVisit);
    }

    @Override
    public void lockOrder(UUID orderId) {
        mapper.lockOrder(orderId);
    }

    @Override
    public List<PlanVersionSummary> findPlanVersions(UUID reservationId) {
        return mapper.findPlanVersions(reservationId).stream()
                .map(row -> new PlanVersionSummary(
                        row.id(),
                        row.planNo(),
                        row.planVersion(),
                        PlanStatus.valueOf(row.status()),
                        row.totalAmountMinor(),
                        toPlanItems(row.id()),
                        row.changeNote(),
                        row.publishedAt()))
                .toList();
    }

    @Override
    public Optional<ProviderSummary> findProvider(UUID providerId) {
        return mapper.findProvider(providerId)
                .map(row -> new ProviderSummary(
                        row.providerId(),
                        row.name(),
                        row.certificationLabel(),
                        row.qualificationReviewed()));
    }

    @Override
    public Optional<CustomerParty> findCustomerParty(UUID reservationId) {
        return mapper.findCustomerParty(reservationId)
                .map(row -> new CustomerParty(row.organizationName(), row.operatorName()));
    }

    @Override
    public Optional<String> findDisplayName(UUID subjectId) {
        return mapper.findDisplayName(subjectId);
    }

    @Override
    public Optional<PlanIdempotencyEntry> findIdempotency(
            UUID actorId,
            String operation,
            UUID key
    ) {
        return mapper.findIdempotency(actorId, operation, key)
                .map(row -> new PlanIdempotencyEntry(
                        actorId,
                        operation,
                        key,
                        row.requestDigest(),
                        row.resourceId()));
    }

    @Override
    public int nextPlanVersion(UUID reservationId) {
        return mapper.nextPlanVersion(reservationId);
    }

    @Override
    public PlanWriteResult publish(
            PlanReservation expectedReservation,
            StoredServicePlan plan,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        if (mapper.insertIdempotency(
                entry.actorId(),
                entry.operation(),
                entry.key(),
                entry.requestDigest(),
                "PLAN",
                entry.resourceId(),
                201) == 0) {
            return PlanWriteResult.IDEMPOTENCY_EXISTS;
        }
        if (mapper.advanceReservationForPlan(
                expectedReservation.id(),
                expectedReservation.version(),
                actor.subjectId()) == 0) {
            return PlanWriteResult.VERSION_CONFLICT;
        }
        mapper.withdrawPublishedPlan(expectedReservation.id(), actor.subjectId());
        mapper.insertPlan(
                plan.id(),
                plan.planNo(),
                plan.reservation().id(),
                plan.providerId(),
                plan.planVersion(),
                plan.summary(),
                plan.terms().scopeSummary(),
                ServiceFlowJson.codeLabels(plan.terms().serviceMethods()),
                plan.terms().deliveryTerms() == null
                        ? null
                        : plan.terms().deliveryTerms().reportDueHours(),
                plan.terms().deliveryTerms() == null
                        ? null
                        : plan.terms().deliveryTerms().mediaRetentionDays(),
                plan.terms().changeNote(),
                plan.serviceDate(),
                plan.validUntil(),
                plan.totalAmountMinor(),
                plan.publishedAt(),
                plan.version(),
                actor.subjectId());
        for (int index = 0; index < plan.planItems().size(); index++) {
            PlanItem item = plan.planItems().get(index);
            mapper.insertPlanItem(
                    plan.id(),
                    item.code(),
                    item.name(),
                    item.quantity(),
                    item.unit(),
                    item.unitPriceMinor(),
                    item.lineAmountMinor(),
                    index);
        }
        for (int index = 0; index < plan.deliverables().size(); index++) {
            PlanDeliverable item = plan.deliverables().get(index);
            mapper.insertPlanDeliverable(
                    plan.id(),
                    item.code(),
                    item.label(),
                    item.fileType() == null ? null : item.fileType().name(),
                    index);
        }
        for (PlanVisit visit : plan.terms().visits()) {
            mapper.insertPlanVisit(plan.id(), visit.visitNo(), visit.plannedDate());
        }
        for (PlanPaymentNode node : plan.terms().paymentNodes()) {
            mapper.insertPlanPaymentNode(
                    plan.id(),
                    node.nodeNo(),
                    node.nodeType().name(),
                    node.visitNo(),
                    node.amountMinor());
        }
        Instant at = plan.publishedAt();
        mapper.insertTransition(
                "RESERVATION",
                expectedReservation.id(),
                expectedReservation.status().name(),
                ReservationPlanStatus.PLAN_PUBLISHED.name(),
                "PROVIDER_OPERATOR",
                actor.subjectId(),
                requestId,
                at);
        mapper.insertTransition(
                "PLAN",
                plan.id(),
                null,
                PlanStatus.PUBLISHED.name(),
                "PROVIDER_OPERATOR",
                actor.subjectId(),
                requestId,
                at);
        mapper.insertOutbox("PLAN", plan.id(), "INSPECTION_PLAN_PUBLISHED", at);
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult confirm(
            StoredServicePlan expectedPlan,
            StoredServiceOrder order,
            PlanIdempotencyEntry entry,
            String agreementVersion,
            CurrentSubject actor,
            String requestId
    ) {
        if (mapper.insertIdempotency(
                entry.actorId(),
                entry.operation(),
                entry.key(),
                entry.requestDigest(),
                "SERVICE_ORDER",
                entry.resourceId(),
                200) == 0) {
            return PlanWriteResult.IDEMPOTENCY_EXISTS;
        }
        Instant confirmedAt = order.createdAt();
        if (mapper.confirmPlan(
                expectedPlan.id(),
                expectedPlan.version(),
                agreementVersion,
                confirmedAt,
                actor.subjectId()) == 0) {
            return resolveConfirmConflict(expectedPlan.id(), confirmedAt);
        }
        if (mapper.insertServiceOrder(
                order.id(),
                order.serviceOrderNo(),
                expectedPlan.id(),
                order.paymentMode().name(),
                actor.subjectId(),
                confirmedAt) != 1) {
            return PlanWriteResult.STATE_CONFLICT;
        }
        for (StoredServiceVisit visit : order.visits()) {
            mapper.insertServiceVisit(
                    visit.id(),
                    order.id(),
                    visit.visitNo(),
                    visit.plannedDate(),
                    visit.status().name(),
                    actor.subjectId(),
                    confirmedAt);
            mapper.insertTransition(
                    "SERVICE_VISIT",
                    visit.id(),
                    null,
                    visit.status().name(),
                    "CUSTOMER",
                    actor.subjectId(),
                    requestId,
                    confirmedAt);
        }
        for (OrderPaymentNode node : order.paymentNodes()) {
            mapper.insertOrderPaymentNode(
                    order.id(),
                    node.nodeNo(),
                    node.nodeType().name(),
                    node.visitId(),
                    node.amountMinor(),
                    confirmedAt);
        }
        mapper.insertTransition(
                "PLAN",
                expectedPlan.id(),
                PlanStatus.PUBLISHED.name(),
                PlanStatus.CONFIRMED.name(),
                "CUSTOMER",
                actor.subjectId(),
                requestId,
                confirmedAt);
        mapper.insertTransition(
                "SERVICE_ORDER",
                order.id(),
                null,
                ServiceOrderStatus.ARRANGING.name(),
                "CUSTOMER",
                actor.subjectId(),
                requestId,
                confirmedAt);
        mapper.insertOutbox(
                "SERVICE_ORDER",
                order.id(),
                "INSPECTION_PLAN_CONFIRMED",
                confirmedAt);
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult arrange(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder arrangedOrder,
            List<AssignmentWrite> assignments,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        if (mapper.insertIdempotency(
                entry.actorId(),
                entry.operation(),
                entry.key(),
                entry.requestDigest(),
                "SERVICE_ORDER",
                entry.resourceId(),
                200) == 0) {
            return PlanWriteResult.IDEMPOTENCY_EXISTS;
        }
        Instant updatedAt = arrangedOrder.progress()
                .get(arrangedOrder.progress().size() - 1)
                .occurredAt();
        if (mapper.updateOrderArrangement(
                expectedOrder.id(),
                expectedOrder.version(),
                actor.subjectId(),
                updatedAt) == 0) {
            return resolveOrderConflict(expectedOrder.id(), expectedOrder.version());
        }
        mapper.deleteOrderAssignments(expectedOrder.id());
        for (AssignmentWrite write : assignments) {
            OrderAssignment assignment = write.assignment();
            mapper.insertOrderAssignment(
                    expectedOrder.id(),
                    assignment.type().name(),
                    assignment.resourceId(),
                    assignment.displayName(),
                    assignment.roleLabel(),
                    write.contactMobile() == null
                            ? null
                            : cipher.encrypt(write.contactMobile())
                                    .getBytes(StandardCharsets.UTF_8),
                    assignment.contactMobileMasked(),
                    assignment.certifiedPilot(),
                    actor.subjectId(),
                    updatedAt);
        }
        OrderProgressEvent event =
                arrangedOrder.progress().get(arrangedOrder.progress().size() - 1);
        mapper.insertOrderProgress(
                event.id(),
                expectedOrder.id(),
                null,
                event.eventCode().name(),
                event.occurredAt(),
                event.message(),
                actor.subjectId(),
                updatedAt);
        mapper.insertOutbox(
                "SERVICE_ORDER",
                expectedOrder.id(),
                "INSPECTION_ORDER_ARRANGED",
                updatedAt);
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult schedule(
            StoredServiceOrder order,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit scheduledVisit,
            PlanIdempotencyEntry entry,
            Instant at,
            CurrentSubject actor,
            String requestId
    ) {
        if (mapper.insertIdempotency(
                entry.actorId(),
                entry.operation(),
                entry.key(),
                entry.requestDigest(),
                "SERVICE_VISIT",
                entry.resourceId(),
                200) == 0) {
            return PlanWriteResult.IDEMPOTENCY_EXISTS;
        }
        if (mapper.updateVisitSchedule(
                expectedVisit.id(),
                expectedVisit.version(),
                scheduledVisit.plannedStartAt(),
                actor.subjectId(),
                at) == 0) {
            return resolveVisitConflict(expectedVisit.id(), expectedVisit.version());
        }
        // Rescheduling keeps the status but still needs a record of who moved it and when.
        mapper.insertTransition(
                "SERVICE_VISIT",
                expectedVisit.id(),
                expectedVisit.status().name(),
                scheduledVisit.status().name(),
                "PROVIDER_OPERATOR",
                actor.subjectId(),
                requestId,
                at);
        mapper.insertOutbox(
                "SERVICE_VISIT",
                expectedVisit.id(),
                "INSPECTION_VISIT_SCHEDULED",
                at);
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult appendProgress(
            StoredServiceOrder expectedOrder,
            ServiceOrderStatus nextOrderStatus,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit updatedVisit,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        if (mapper.insertIdempotency(
                entry.actorId(),
                entry.operation(),
                entry.key(),
                entry.requestDigest(),
                "SERVICE_VISIT",
                entry.resourceId(),
                201) == 0) {
            return PlanWriteResult.IDEMPOTENCY_EXISTS;
        }
        OrderProgressEvent event =
                updatedVisit.progress().get(updatedVisit.progress().size() - 1);
        if (mapper.updateVisitProgress(
                expectedVisit.id(),
                expectedVisit.status().name(),
                updatedVisit.status().name(),
                expectedVisit.version(),
                event.eventCode().name(),
                event.occurredAt(),
                actor.subjectId()) == 0) {
            return resolveVisitConflict(expectedVisit.id(), expectedVisit.version());
        }
        if (expectedOrder.status() != nextOrderStatus) {
            if (mapper.updateOrderProgress(
                    expectedOrder.id(),
                    expectedOrder.status().name(),
                    nextOrderStatus.name(),
                    expectedOrder.version(),
                    event.eventCode().name(),
                    event.occurredAt(),
                    actor.subjectId(),
                    event.occurredAt()) == 0) {
                return resolveOrderConflict(expectedOrder.id(), expectedOrder.version());
            }
            mapper.insertTransition(
                    "SERVICE_ORDER",
                    expectedOrder.id(),
                    expectedOrder.status().name(),
                    nextOrderStatus.name(),
                    "PROVIDER_OPERATOR",
                    actor.subjectId(),
                    requestId,
                    event.occurredAt());
        }
        mapper.insertOrderProgress(
                event.id(),
                expectedOrder.id(),
                expectedVisit.id(),
                event.eventCode().name(),
                event.occurredAt(),
                event.message(),
                actor.subjectId(),
                event.occurredAt());
        if (expectedVisit.status() != updatedVisit.status()) {
            mapper.insertTransition(
                    "SERVICE_VISIT",
                    expectedVisit.id(),
                    expectedVisit.status().name(),
                    updatedVisit.status().name(),
                    "PROVIDER_OPERATOR",
                    actor.subjectId(),
                    requestId,
                    event.occurredAt());
        }
        mapper.insertOutbox(
                "SERVICE_VISIT",
                expectedVisit.id(),
                "INSPECTION_VISIT_" + event.eventCode().name(),
                event.occurredAt());
        return PlanWriteResult.SAVED;
    }

    private PlanWriteResult resolveConfirmConflict(UUID planId, Instant now) {
        Optional<ServiceFlowPersistenceMapper.PlanRow> current = mapper.findPlan(planId);
        if (current.isEmpty()) {
            return PlanWriteResult.STATE_CONFLICT;
        }
        if ("PUBLISHED".equals(current.get().status())
                && !current.get().validUntil().isAfter(now)) {
            return PlanWriteResult.EXPIRED;
        }
        if ("PUBLISHED".equals(current.get().status())) {
            return PlanWriteResult.VERSION_CONFLICT;
        }
        return PlanWriteResult.STATE_CONFLICT;
    }

    private PlanWriteResult resolveOrderConflict(UUID orderId, int expectedVersion) {
        Optional<ServiceFlowPersistenceMapper.ServiceOrderRow> current =
                mapper.findOrder(orderId);
        if (current.isPresent() && current.get().version() != expectedVersion) {
            return PlanWriteResult.VERSION_CONFLICT;
        }
        return PlanWriteResult.STATE_CONFLICT;
    }

    private PlanWriteResult resolveVisitConflict(UUID visitId, int expectedVersion) {
        Optional<ServiceFlowPersistenceMapper.ServiceVisitRow> current =
                mapper.findVisit(visitId);
        if (current.isPresent() && current.get().version() != expectedVersion) {
            return PlanWriteResult.VERSION_CONFLICT;
        }
        return PlanWriteResult.STATE_CONFLICT;
    }

    private StoredServiceVisit toVisit(ServiceFlowPersistenceMapper.ServiceVisitRow row) {
        return new StoredServiceVisit(
                row.id(),
                row.serviceOrderId(),
                row.visitNo(),
                row.plannedDate(),
                row.plannedStartAt(),
                ServiceVisitStatus.valueOf(row.status()),
                toProgress(mapper.findVisitProgress(row.id())),
                row.startedAt(),
                row.deliveredAt(),
                row.acceptedAt(),
                row.version());
    }

    private static List<OrderProgressEvent> toProgress(
            List<ServiceFlowPersistenceMapper.OrderProgressRow> rows
    ) {
        return rows.stream()
                .map(item -> new OrderProgressEvent(
                        item.id(),
                        OrderProgressCode.valueOf(item.eventCode()),
                        item.occurredAt(),
                        item.message()))
                .toList();
    }

    private List<PlanItem> toPlanItems(UUID planId) {
        return mapper.findPlanItems(planId).stream()
                .map(item -> new PlanItem(
                        item.code(),
                        item.name(),
                        item.quantity(),
                        item.unit(),
                        item.unitPriceMinor(),
                        item.lineAmountMinor()))
                .toList();
    }

    private PlanTerms toPlanTerms(ServiceFlowPersistenceMapper.PlanRow row) {
        return new PlanTerms(
                mapper.findPlanVisits(row.id()).stream()
                        .map(item -> new PlanVisit(item.visitNo(), item.plannedDate()))
                        .toList(),
                row.scopeSummary(),
                mapper.findPlanServiceMethods(row.id()).stream()
                        .map(item -> new CodeLabel(item.code(), item.label()))
                        .toList(),
                row.reportDueHours() == null && row.mediaRetentionDays() == null
                        ? null
                        : new DeliveryTerms(row.reportDueHours(), row.mediaRetentionDays()),
                row.changeNote(),
                mapper.findPlanPaymentNodes(row.id()).stream()
                        .map(item -> new PlanPaymentNode(
                                item.nodeNo(),
                                PaymentNodeType.valueOf(item.nodeType()),
                                item.visitNo(),
                                item.amountMinor()))
                        .toList());
    }

    private Optional<StoredServicePlan> toPlan(
            ServiceFlowPersistenceMapper.PlanRow row
    ) {
        return findReservation(row.reservationId()).map(reservation ->
                new StoredServicePlan(
                        row.id(),
                        row.planNo(),
                        reservation,
                        row.providerId(),
                        PlanStatus.valueOf(row.status()),
                        row.planVersion(),
                        row.validUntil(),
                        row.serviceDate(),
                        row.summary(),
                        toPlanTerms(row),
                        toPlanItems(row.id()),
                        mapper.findPlanDeliverables(row.id()).stream()
                                .map(item -> new PlanDeliverable(
                                        item.code(),
                                        item.label(),
                                        item.fileType() == null
                                                ? null
                                                : DeliveryFileType.valueOf(item.fileType())))
                                .toList(),
                        row.totalAmountMinor(),
                        row.currency(),
                        row.publishedAt(),
                        row.confirmedAt(),
                        row.confirmedBy(),
                        row.serviceOrderId(),
                        row.version()));
    }

    private PlanReservation toReservation(
            ServiceFlowPersistenceMapper.ReservationRow row
    ) {
        String encryptedAddress = new String(
                row.addressCiphertext(),
                StandardCharsets.UTF_8);
        return new PlanReservation(
                row.id(),
                row.reservationNo(),
                row.customerId(),
                row.providerId(),
                ReservationPlanStatus.valueOf(row.status()),
                row.serviceId(),
                row.serviceTitle(),
                row.serviceCoverUrl(),
                row.cityCode(),
                cipher.decrypt(encryptedAddress),
                row.longitude(),
                row.latitude(),
                row.lineLengthMeters(),
                mapper.findReservationItems(row.id()).stream()
                        .map(item -> new CodeLabel(item.code(), item.label()))
                        .toList(),
                row.version());
    }
}
