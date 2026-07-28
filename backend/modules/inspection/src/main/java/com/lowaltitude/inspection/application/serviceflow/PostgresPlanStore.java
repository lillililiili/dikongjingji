package com.lowaltitude.inspection.application.serviceflow;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.reservation.AesGcmSensitiveDataCipher;
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
                                        row.createdAt(),
                                        row.version()))));
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
            CodeLabel item = plan.deliverables().get(index);
            mapper.insertPlanDeliverable(
                    plan.id(),
                    item.code(),
                    item.label(),
                    index);
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
                actor.subjectId(),
                confirmedAt) != 1) {
            return PlanWriteResult.STATE_CONFLICT;
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
                        mapper.findPlanItems(row.id()).stream()
                                .map(item -> new PlanItem(
                                        item.code(),
                                        item.name(),
                                        item.quantity(),
                                        item.unit(),
                                        item.unitPriceMinor(),
                                        item.lineAmountMinor()))
                                .toList(),
                        mapper.findPlanDeliverables(row.id()).stream()
                                .map(item -> new CodeLabel(item.code(), item.label()))
                                .toList(),
                        row.totalAmountMinor(),
                        row.currency(),
                        row.publishedAt(),
                        row.confirmedAt(),
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
