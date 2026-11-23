package com.lowaltitude.inspection.application.serviceflow;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.transaction.annotation.Transactional;

/**
 * 确认方案之后对某一次巡检的改动（S4-1）：客户申请改期、服务商确认或拒绝（C3），客户退回整改（B2）。
 * 规则见 {@link PaymentRules}。
 */
public class VisitChangeService {

    private static final int MAX_RESCHEDULE_REASON = 200;
    private static final int MAX_DECISION_NOTE = 200;
    private static final int MAX_REWORK_REASON = 500;

    private final PlanStore orders;
    private final DeliveryStore deliveries;
    private final ServiceOrderChangeStore changes;
    private final Clock clock;

    public VisitChangeService(
            PlanStore orders,
            DeliveryStore deliveries,
            ServiceOrderChangeStore changes,
            Clock clock
    ) {
        this.orders = orders;
        this.deliveries = deliveries;
        this.changes = changes;
        this.clock = clock;
    }

    // ---- 改期 ----

    /**
     * 客户为某一次巡检申请改期。该次巡检开始前 24 小时之前才能申请，同一次巡检同时只有一个待处理的申请；
     * 同一个人用同样的日期和原因重复提交时返回已有的申请。
     */
    @Transactional
    public VisitRescheduleRecord requestReschedule(
            CurrentSubject subject,
            UUID orderId,
            UUID visitId,
            int expectedVisitVersion,
            LocalDate requestedDate,
            String reason,
            String requestId
    ) {
        requireCustomer(subject, "inspection.plan.confirm.own");
        String normalizedReason = normalize(reason);
        if (orderId == null || visitId == null || expectedVisitVersion < 0 || requestedDate == null
                || normalizedReason == null || normalizedReason.length() > MAX_RESCHEDULE_REASON) {
            throw new PlanValidationException();
        }
        orders.lockOrder(orderId);
        StoredServiceOrder order = orders.findOrder(orderId).orElseThrow(PlanResourceNotFoundException::new);
        if (!order.customerId().equals(subject.subjectId())) {
            throw new PlanResourceNotFoundException();
        }
        StoredServiceVisit visit = order.visit(visitId).orElseThrow(PlanResourceNotFoundException::new);
        Optional<VisitRescheduleRecord> pending = changes.extras(orderId).latestReschedule(visitId)
                .filter(item -> item.status() == RescheduleStatus.REQUESTED);
        if (pending.isPresent()) {
            VisitRescheduleRecord open = pending.get();
            if (open.requestedDate().equals(requestedDate) && open.reason().equals(normalizedReason)) {
                return open;
            }
            throw new RescheduleNotAllowedException();
        }
        if (visit.version() != expectedVisitVersion) {
            throw new PlanVersionConflictException();
        }
        Instant now = clock.instant();
        if (!PaymentRules.reschedulable(order, visit, now)) {
            throw new RescheduleNotAllowedException();
        }
        requireDateFits(order, visit, requestedDate, now);
        VisitRescheduleRecord request = new VisitRescheduleRecord(
                UUID.randomUUID(),
                visitId,
                visit.plannedDate(),
                requestedDate,
                normalizedReason,
                RescheduleStatus.REQUESTED,
                null,
                now,
                null);
        if (!changes.insertReschedule(request, orderId, subject.subjectId())) {
            throw new RescheduleNotAllowedException();
        }
        changes.recordTransition("RESCHEDULE", request.id(), null, RescheduleStatus.REQUESTED.name(),
                "CUSTOMER", subject.subjectId(), requestId(requestId), now);
        return request;
    }

    /**
     * 服务商确认或拒绝改期。确认后这次巡检换成新日期、清掉排期回到待排期；新日期已经过了、这次巡检已经开工，
     * 或者和前后两次的顺序冲突时不能确认（只能拒绝）。已经按同样的决定处理过的原样返回。
     */
    @Transactional
    public VisitRescheduleRecord decideReschedule(
            CurrentSubject subject,
            UUID orderId,
            UUID visitId,
            UUID requestId,
            boolean approve,
            String note,
            String commandRequestId
    ) {
        requireProvider(subject);
        String normalizedNote = normalize(note);
        if (orderId == null || visitId == null || requestId == null
                || normalizedNote != null && normalizedNote.length() > MAX_DECISION_NOTE) {
            throw new PlanValidationException();
        }
        orders.lockOrder(orderId);
        StoredServiceOrder order = orders.findOrder(orderId).orElseThrow(PlanResourceNotFoundException::new);
        if (!order.providerId().equals(subject.organizationId())) {
            throw new PlanResourceNotFoundException();
        }
        StoredServiceVisit visit = order.visit(visitId).orElseThrow(PlanResourceNotFoundException::new);
        VisitRescheduleRecord request = changes.findReschedule(requestId)
                .filter(item -> item.visitId().equals(visitId))
                .orElseThrow(PlanResourceNotFoundException::new);
        RescheduleStatus decision = approve ? RescheduleStatus.APPROVED : RescheduleStatus.REJECTED;
        if (request.status() != RescheduleStatus.REQUESTED) {
            if (request.status() == decision) {
                return request;
            }
            throw new RescheduleStateInvalidException();
        }
        Instant now = clock.instant();
        if (approve) {
            boolean notStarted = visit.status() == ServiceVisitStatus.PENDING_SCHEDULE
                    || visit.status() == ServiceVisitStatus.SCHEDULED;
            if (!notStarted || order.status() == ServiceOrderStatus.CANCELLED) {
                throw new VisitStateInvalidException();
            }
            requireDateFits(order, visit, request.requestedDate(), now);
            if (!changes.rescheduleVisit(visitId, visit.version(), request.requestedDate(),
                    subject.subjectId(), now)) {
                throw new PlanVersionConflictException();
            }
            changes.recordTransition("SERVICE_VISIT", visitId, visit.status().name(),
                    ServiceVisitStatus.PENDING_SCHEDULE.name(), "PROVIDER_OPERATOR", subject.subjectId(),
                    requestId(commandRequestId), now);
        }
        if (!changes.decideReschedule(requestId, decision, normalizedNote, subject.subjectId(), now)) {
            throw new RescheduleStateInvalidException();
        }
        changes.recordTransition("RESCHEDULE", requestId, RescheduleStatus.REQUESTED.name(), decision.name(),
                "PROVIDER_OPERATOR", subject.subjectId(), requestId(commandRequestId), now);
        return changes.findReschedule(requestId).orElseThrow();
    }

    /** 新日期要晚于今天（北京时间）、晚于上一次、早于下一次的计划日期，且不同于原日期。 */
    private static void requireDateFits(
            StoredServiceOrder order,
            StoredServiceVisit visit,
            LocalDate requestedDate,
            Instant now
    ) {
        LocalDate today = LocalDate.ofInstant(now, PaymentRules.BEIJING);
        Optional<LocalDate> previous = order.visits().stream()
                .filter(other -> other.visitNo() == visit.visitNo() - 1)
                .map(StoredServiceVisit::plannedDate)
                .findFirst();
        Optional<LocalDate> next = order.visits().stream()
                .filter(other -> other.visitNo() == visit.visitNo() + 1)
                .map(StoredServiceVisit::plannedDate)
                .min(Comparator.naturalOrder());
        if (!requestedDate.isAfter(today)
                || requestedDate.equals(visit.plannedDate())
                || previous.filter(date -> !requestedDate.isAfter(date)).isPresent()
                || next.filter(date -> !requestedDate.isBefore(date)).isPresent()) {
            throw new PlanValidationException();
        }
    }

    // ---- 退回整改 ----

    /**
     * B2：客户对已交付、待验收的这一次退回整改，写明原因；服务商重新交付之后再验收。不自动退款。
     * 已经退回整改（还没重新交付）时，同样的原因再提交原样返回。
     */
    @Transactional
    public VisitReworkRecord returnForRework(
            CurrentSubject subject,
            UUID orderId,
            UUID visitId,
            int expectedVisitVersion,
            String reason,
            String requestId
    ) {
        requireCustomer(subject, "inspection.acceptance.create.own");
        String normalizedReason = normalize(reason);
        if (orderId == null || visitId == null || expectedVisitVersion < 0
                || normalizedReason == null || normalizedReason.length() > MAX_REWORK_REASON) {
            throw new PlanValidationException();
        }
        orders.lockOrder(orderId);
        StoredServiceOrder order = orders.findOrder(orderId).orElseThrow(PlanResourceNotFoundException::new);
        if (!order.customerId().equals(subject.subjectId())) {
            throw new PlanResourceNotFoundException();
        }
        StoredServiceVisit visit = order.visit(visitId).orElseThrow(PlanResourceNotFoundException::new);
        if (visit.status() == ServiceVisitStatus.REWORK) {
            Optional<VisitReworkRecord> latest = changes.extras(orderId).latestRework(visitId)
                    .filter(item -> item.resolvedAt() == null && item.reason().equals(normalizedReason));
            if (latest.isPresent()) {
                return latest.get();
            }
            throw new DeliveryStateInvalidException();
        }
        if (visit.version() != expectedVisitVersion) {
            throw new PlanVersionConflictException();
        }
        StoredDelivery delivery = deliveries.findDeliveryByVisit(visitId)
                .orElseThrow(DeliveryStateInvalidException::new);
        if (visit.status() != ServiceVisitStatus.DELIVERED
                || delivery.status() != DeliveryStatus.PUBLISHED
                || order.status() == ServiceOrderStatus.CANCELLED) {
            throw new DeliveryStateInvalidException();
        }
        Instant now = clock.instant();
        VisitReworkRecord rework = new VisitReworkRecord(
                UUID.randomUUID(),
                visitId,
                changes.deliveryRevision(delivery.id()),
                normalizedReason,
                now,
                null);
        StoredServiceVisit returned = new StoredServiceVisit(
                visit.id(),
                visit.serviceOrderId(),
                visit.visitNo(),
                visit.plannedDate(),
                visit.plannedStartAt(),
                ServiceVisitStatus.REWORK,
                visit.progress(),
                visit.startedAt(),
                null,
                visit.acceptedAt(),
                visit.version() + 1);
        ServiceOrderStatus nextOrderStatus = ServiceOrderProgression.derive(
                ServiceOrderProgression.replace(order.visits(), returned));
        if (!changes.returnForRework(order, nextOrderStatus, visit, delivery, rework, subject.subjectId(),
                requestId(requestId))) {
            throw new PlanVersionConflictException();
        }
        return rework;
    }

    // ---- 工具 ----

    private static void requireCustomer(CurrentSubject subject, String permission) {
        if (subject == null
                || !subject.roles().contains("CUSTOMER")
                || !subject.permissions().contains(permission)) {
            throw new PlanForbiddenException();
        }
    }

    private static void requireProvider(CurrentSubject subject) {
        if (subject == null
                || !subject.roles().contains("PROVIDER_OPERATOR")
                || !subject.permissions().contains("inspection.order.arrange.assigned")
                || subject.organizationId() == null) {
            throw new PlanForbiddenException();
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requestId(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }
}
