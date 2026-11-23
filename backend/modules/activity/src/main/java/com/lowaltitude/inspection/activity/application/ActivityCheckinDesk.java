package com.lowaltitude.inspection.activity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.activity.application.ActivityCheckinException.Reason;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper.DeskSessionRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper.RecordRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper.SessionLockRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper.TicketRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 核销工作台（S3-3，docs/redesign/08-后续开发计划.md §5 D8）里活动凭证的一半：本机构的场次、
 * 操作员本人的核销记录、按凭证号核销（被拦下的也记一行）、异常的人工处理。场馆的一半在体验模块，
 * 两半由启动模块的工作台接口合在一起（契约 contracts/openapi-checkin-workbench.yaml）。
 *
 * <p>核销的判断顺序：不是本机构主办或承办的 → 已取消 → 未付款 → 已核销过（重复）→ 场次已结束（过期）
 * → 不是工作台正在核销的场次（场次不符）→ 还没到签到时间（不记录，直接拒绝）→ 核销。
 * 登记放行只用于“场次不符”和“过期”，而且当前场次必须是同一个活动的另一场、正在签到、有余位：
 * 凭证改到当前场次并签到，原场次的名额让出来。
 */
public class ActivityCheckinDesk {

    public enum Kind { ACTIVITY, VENUE }

    public enum Method { SCAN, MANUAL }

    public enum Outcome { CHECKED_IN, REPEATED, EXPIRED, OTHER_ORGANIZATION, SESSION_MISMATCH, CANCELLED, UNPAID }

    public enum Action { RELEASE, REJECT, BLOCK }

    public enum Phase { UPCOMING, OPEN, ENDED }

    /** 工作台上正在核销的场次。 */
    public record Current(Kind kind, UUID sessionId) {
    }

    public record SessionRef(UUID sessionId, String title, String label, OffsetDateTime startsAt) {
    }

    public record DeskSession(
            UUID sessionId,
            String title,
            String placeName,
            String label,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime opensAt,
            OffsetDateTime closesAt,
            Phase phase,
            int expectedPeople,
            int checkedInPeople
    ) {
    }

    public record Resolution(
            Action action,
            boolean identityChecked,
            String note,
            OffsetDateTime resolvedAt,
            SessionRef admittedSession
    ) {
    }

    /** 一条工作台记录。currentSession 只在当前场次是活动场次时有，场馆场次由工作台接口补上。 */
    public record Entry(
            UUID recordId,
            Outcome outcome,
            Method method,
            OffsetDateTime occurredAt,
            String codeTail,
            String holderName,
            int people,
            Long amountMinor,
            SessionRef ticketSession,
            Kind currentKind,
            UUID currentSessionId,
            SessionRef currentSession,
            OffsetDateTime firstCheckedInAt,
            List<Action> allowedActions,
            Resolution resolution
    ) {
    }

    static final Pattern CODE = Pattern.compile("^ACT-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$");
    static final Duration DEFAULT_OPENS_BEFORE = Duration.ofHours(1);
    /** 同一个结果在这段时间内连扫只记一行。 */
    static final Duration REPEAT_SCAN_WINDOW = Duration.ofMinutes(2);
    static final int NOTE_LIMIT = 200;

    private final ActivityCheckinMapper mapper;
    private final Clock clock;

    public ActivityCheckinDesk(ActivityCheckinMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 这个机构的操作员能不能用工作台核销活动凭证：机构主办或承办过活动。 */
    public boolean verifies(UUID organizationId) {
        return organizationId != null && mapper.hostsActivities(organizationId);
    }

    /** 本机构在北京时间这一天开始的场次。 */
    public List<DeskSession> sessions(UUID organizationId, LocalDate date) {
        requireOrganization(organizationId);
        OffsetDateTime now = now();
        return mapper.findSessions(organizationId, dayStart(date), dayStart(date.plusDays(1))).stream()
                .map(row -> toSession(row, now))
                .toList();
    }

    /** 本机构的一个活动场次；别的机构的、不存在的为空。 */
    public Optional<SessionRef> sessionRef(UUID organizationId, UUID sessionId) {
        if (organizationId == null || sessionId == null) {
            return Optional.empty();
        }
        DeskSessionRow row = mapper.findOwnSession(organizationId, sessionId);
        return row == null
                ? Optional.empty()
                : Optional.of(new SessionRef(row.sessionId(), row.title(), row.label(), row.startsAt()));
    }

    public List<Entry> records(UUID operatorId, UUID organizationId, LocalDate date) {
        requireOperator(operatorId, organizationId);
        return mapper.findRecords(operatorId, organizationId, dayStart(date), dayStart(date.plusDays(1))).stream()
                .map(ActivityCheckinDesk::toEntry)
                .toList();
    }

    public Entry record(UUID operatorId, UUID organizationId, UUID recordId) {
        requireOperator(operatorId, organizationId);
        RecordRow row = recordId == null ? null : mapper.findRecord(operatorId, organizationId, recordId);
        if (row == null) {
            throw new ActivityCheckinException(Reason.NOT_FOUND);
        }
        return toEntry(row);
    }

    /**
     * 按凭证号核销。核销成功和被拦下都返回记录（outcome 说明是哪一种），都已落库；
     * 没有这张凭证、还没到签到时间时抛出，不记录。
     *
     * @param current 工作台上正在核销的场次；为空时不判断场次不符。是活动场次时必须是本机构的。
     */
    @Transactional
    public Entry verify(UUID operatorId, UUID organizationId, String codeText, Method method, Current current) {
        requireOperator(operatorId, organizationId);
        String code = normalizeCode(codeText);
        if (method == null || (current != null && (current.kind() == null || current.sessionId() == null))) {
            throw new IllegalArgumentException("verification is invalid");
        }
        if (current != null && current.kind() == Kind.ACTIVITY
                && mapper.findOwnSession(organizationId, current.sessionId()) == null) {
            throw new IllegalArgumentException("current session is not this organization's");
        }
        TicketRow ticket = mapper.lockTicketByCode(code);
        if (ticket == null) {
            throw new ActivityCheckinException(Reason.NOT_FOUND);
        }
        OffsetDateTime now = now();
        Outcome outcome = outcomeOf(ticket, organizationId, current, now);
        if (outcome == Outcome.CHECKED_IN) {
            OffsetDateTime opensAt = opensAt(ticket.startsAt(), ticket.checkInOpensAt());
            if (now.isBefore(opensAt)) {
                throw new ActivityCheckinException(Reason.NOT_OPEN);
            }
            mapper.checkIn(ticket.ticketId(), operatorId, now);
            mapper.insertCheckedInEvent(ticket.enrollmentId(), ticket.ticketId(), operatorId, now);
        } else {
            RecordRow recent = mapper.findRecentRejection(
                    operatorId,
                    organizationId,
                    ticket.ticketId(),
                    outcome.name(),
                    current == null ? null : current.sessionId(),
                    now.minus(REPEAT_SCAN_WINDOW));
            if (recent != null) {
                return toEntry(recent);
            }
        }
        boolean own = outcome != Outcome.OTHER_ORGANIZATION;
        UUID recordId = mapper.insertRecord(
                organizationId,
                operatorId,
                ticket.ticketId(),
                own ? ticket.sessionId() : null,
                code.substring(code.length() - 4),
                method.name(),
                current == null ? null : current.kind().name(),
                current == null ? null : current.sessionId(),
                outcome.name(),
                own ? (ticket.unitAmountMinor() == null ? 0L : ticket.unitAmountMinor()) : null,
                outcome == Outcome.REPEATED ? ticket.checkedInAt() : null,
                now);
        return toEntry(mapper.findRecord(operatorId, organizationId, recordId));
    }

    /**
     * 人工处理一条被拦下的记录，只能登记一次：用同一种处理方式再提交返回原来的结果，换一种处理方式拒绝。
     * 登记放行要先人工核验身份。
     */
    @Transactional
    public Entry resolve(
            UUID operatorId,
            UUID organizationId,
            UUID recordId,
            Action action,
            boolean identityChecked,
            String noteText
    ) {
        requireOperator(operatorId, organizationId);
        if (recordId == null || action == null) {
            throw new IllegalArgumentException("resolution is invalid");
        }
        String note = normalizeNote(noteText);
        if (action == Action.RELEASE && !identityChecked) {
            throw new IllegalArgumentException("identity must be checked before release");
        }
        RecordRow row = mapper.lockRecord(operatorId, organizationId, recordId);
        if (row == null) {
            throw new ActivityCheckinException(Reason.NOT_FOUND);
        }
        if (row.resolution() != null) {
            if (Action.valueOf(row.resolution()) == action) {
                return toEntry(row);
            }
            throw new ActivityCheckinException(Reason.ALREADY_RESOLVED);
        }
        if (!allowedActions(row).contains(action)) {
            throw new ActivityCheckinException(Reason.ACTION_NOT_ALLOWED);
        }
        OffsetDateTime now = now();
        UUID admitted = null;
        if (action == Action.RELEASE) {
            admitted = release(operatorId, row, now);
        }
        int updated = mapper.resolveRecord(
                operatorId,
                recordId,
                action.name(),
                identityChecked,
                note,
                admitted,
                now);
        if (updated != 1) {
            throw new ActivityCheckinException(Reason.ALREADY_RESOLVED);
        }
        return toEntry(mapper.findRecord(operatorId, organizationId, recordId));
    }

    /** 凭证改到当前场次并签到；返回当前场次。 */
    private UUID release(UUID operatorId, RecordRow row, OffsetDateTime now) {
        UUID original = row.ticketSessionId();
        UUID target = row.currentSessionId();
        // 两个场次按 ID 从小到大锁，和报名、取消一样先锁场次再锁凭证。
        UUID first = original.compareTo(target) < 0 ? original : target;
        UUID second = first.equals(original) ? target : original;
        SessionLockRow firstSession = mapper.lockSession(first);
        SessionLockRow secondSession = mapper.lockSession(second);
        SessionLockRow targetSession = first.equals(target) ? firstSession : secondSession;
        TicketRow ticket = mapper.lockTicket(row.ticketId());
        if (ticket == null || !"ACTIVE".equals(ticket.status()) || !original.equals(ticket.sessionId())) {
            throw new ActivityCheckinException(Reason.RELEASE_NOT_ALLOWED);
        }
        if (targetSession == null || !"ACTIVE".equals(targetSession.status())
                || !targetSession.activityId().equals(ticket.activityId())
                || now.isBefore(opensAt(targetSession.startsAt(), targetSession.checkInOpensAt()))
                || !now.isBefore(closesAt(targetSession.startsAt(), targetSession.endsAt()))) {
            throw new ActivityCheckinException(Reason.RELEASE_NOT_ALLOWED);
        }
        if (targetSession.enrolledCount() + 1 > targetSession.capacity()) {
            throw new ActivityCheckinException(Reason.SEATS_NOT_ENOUGH);
        }
        if (mapper.countOtherTickets(target, ticket.participantId(), ticket.ticketId()) > 0) {
            throw new ActivityCheckinException(Reason.RELEASE_NOT_ALLOWED);
        }
        mapper.moveAndCheckIn(ticket.ticketId(), target, operatorId, now);
        mapper.addEnrolled(original, -1, now);
        mapper.addEnrolled(target, 1, now);
        mapper.insertCheckedInEvent(ticket.enrollmentId(), ticket.ticketId(), operatorId, now);
        return target;
    }

    // ---- 规则 ----

    static Outcome outcomeOf(TicketRow ticket, UUID organizationId, Current current, OffsetDateTime now) {
        if (!organizationId.equals(ticket.organizerId()) && !organizationId.equals(ticket.coOrganizerId())) {
            return Outcome.OTHER_ORGANIZATION;
        }
        switch (ticket.status()) {
            case "CANCELLED" -> {
                return Outcome.CANCELLED;
            }
            case "PENDING_PAYMENT" -> {
                return Outcome.UNPAID;
            }
            case "CHECKED_IN" -> {
                return Outcome.REPEATED;
            }
            default -> {
                // ACTIVE：往下看时间和场次。
            }
        }
        if (!now.isBefore(closesAt(ticket.startsAt(), ticket.endsAt()))) {
            return Outcome.EXPIRED;
        }
        if (current != null && (current.kind() != Kind.ACTIVITY || !current.sessionId().equals(ticket.sessionId()))) {
            return Outcome.SESSION_MISMATCH;
        }
        return Outcome.CHECKED_IN;
    }

    /**
     * 场次不符、过期：当前场次是同一个活动的另一场时可以登记放行，否则只能不予核销；
     * 重复、非本机构、已取消、未付款一律拦截。核销成功和已处理的没有可做的。
     */
    static List<Action> allowedActions(RecordRow row) {
        if (row.resolution() != null) {
            return List.of();
        }
        return switch (Outcome.valueOf(row.outcome())) {
            case CHECKED_IN -> List.of();
            case SESSION_MISMATCH, EXPIRED -> {
                boolean sameActivity = Kind.ACTIVITY.name().equals(row.currentSessionKind())
                        && row.currentActivityId() != null
                        && row.currentActivityId().equals(row.ticketActivityId())
                        && !row.currentSessionId().equals(row.ticketSessionId());
                yield sameActivity ? List.of(Action.RELEASE, Action.REJECT) : List.of(Action.REJECT);
            }
            case REPEATED, OTHER_ORGANIZATION, CANCELLED, UNPAID -> List.of(Action.BLOCK);
        };
    }

    static String normalizeCode(String codeText) {
        String code = codeText == null ? "" : codeText.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("ticket code is invalid");
        }
        return code;
    }

    static String normalizeNote(String noteText) {
        String note = noteText == null ? "" : noteText.trim();
        if (note.codePointCount(0, note.length()) > NOTE_LIMIT) {
            throw new IllegalArgumentException("note is too long");
        }
        return note.isEmpty() ? null : note;
    }

    /** 持有人姓名只给姓：第一个字加两个星号。 */
    static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        return new String(Character.toChars(trimmed.codePointAt(0))) + "**";
    }

    static OffsetDateTime opensAt(OffsetDateTime startsAt, OffsetDateTime checkInOpensAt) {
        return checkInOpensAt == null ? startsAt.minus(DEFAULT_OPENS_BEFORE) : checkInOpensAt;
    }

    /** 没有结束时间的场次到开始时间为止（和报名服务的核销窗口一致）。 */
    static OffsetDateTime closesAt(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return endsAt == null ? startsAt : endsAt;
    }

    private static DeskSession toSession(DeskSessionRow row, OffsetDateTime now) {
        OffsetDateTime opens = opensAt(row.startsAt(), row.checkInOpensAt());
        OffsetDateTime closes = closesAt(row.startsAt(), row.endsAt());
        Phase phase = now.isBefore(opens) ? Phase.UPCOMING : now.isBefore(closes) ? Phase.OPEN : Phase.ENDED;
        return new DeskSession(
                row.sessionId(),
                row.title(),
                row.placeName(),
                row.label(),
                row.startsAt(),
                row.endsAt(),
                opens,
                closes,
                phase,
                row.expectedPeople(),
                row.checkedInPeople());
    }

    static Entry toEntry(RecordRow row) {
        SessionRef ticketSession = row.ticketSessionId() == null
                ? null
                : new SessionRef(row.ticketSessionId(), row.ticketTitle(), row.ticketLabel(), row.ticketStartsAt());
        Kind currentKind = row.currentSessionKind() == null ? null : Kind.valueOf(row.currentSessionKind());
        SessionRef currentSession = currentKind == Kind.ACTIVITY && row.currentTitle() != null
                ? new SessionRef(row.currentSessionId(), row.currentTitle(), row.currentLabel(), row.currentStartsAt())
                : null;
        Resolution resolution = row.resolution() == null
                ? null
                : new Resolution(
                        Action.valueOf(row.resolution()),
                        row.identityChecked(),
                        row.resolutionNote(),
                        row.resolvedAt(),
                        row.admittedSessionId() == null
                                ? null
                                : new SessionRef(
                                        row.admittedSessionId(),
                                        row.admittedTitle(),
                                        row.admittedLabel(),
                                        row.admittedStartsAt()));
        return new Entry(
                row.recordId(),
                Outcome.valueOf(row.outcome()),
                Method.valueOf(row.method()),
                row.occurredAt(),
                row.codeTail(),
                maskName(row.holderName()),
                row.people(),
                row.amountMinor(),
                ticketSession,
                currentKind,
                row.currentSessionId(),
                currentSession,
                row.firstCheckedInAt(),
                allowedActions(row),
                resolution);
    }

    private static void requireOrganization(UUID organizationId) {
        if (organizationId == null) {
            throw new ActivityCheckinException(Reason.FORBIDDEN);
        }
    }

    private static void requireOperator(UUID operatorId, UUID organizationId) {
        if (operatorId == null || organizationId == null) {
            throw new ActivityCheckinException(Reason.FORBIDDEN);
        }
    }

    private static OffsetDateTime dayStart(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        return date.atStartOfDay(ActivityCatalogReadService.ACTIVITY_ZONE).toOffsetDateTime();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
