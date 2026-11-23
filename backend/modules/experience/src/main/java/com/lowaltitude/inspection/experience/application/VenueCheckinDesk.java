package com.lowaltitude.inspection.experience.application;

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

import com.lowaltitude.inspection.experience.application.VenueCheckinException.Reason;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper.BookingRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper.DeskSessionRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper.RecordRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper.SessionLockRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 核销工作台（S3-3，docs/redesign/08-后续开发计划.md §5 D8）里到馆预约的一半：本机构场馆的场次、
 * 操作员本人的核销记录、按核销码核销整单（被拦下的也记一行）、异常的人工处理。活动凭证的一半在活动模块，
 * 两半由启动模块的工作台接口合在一起（契约 contracts/openapi-checkin-workbench.yaml）。
 *
 * <p>核销的判断顺序：不是本机构运营的场馆 → 已取消 → 已核销过（重复）→ 场次已结束（过期）
 * → 不是工作台正在核销的场次（场次不符）→ 还没到核销时间（不记录，直接拒绝）→ 核销。
 * 登记放行只用于“场次不符”和“过期”，而且当前场次必须是同一个项目的另一场、正在核销、余位够整单的人：
 * 预约改到当前场次并核销，原场次的名额让出来（和改期一样一出一进，但核销码不换）。
 */
public class VenueCheckinDesk {

    public enum Kind { ACTIVITY, VENUE }

    public enum Method { SCAN, MANUAL }

    /** 到馆预约付款前没有核销码，所以不会有“未付款”。 */
    public enum Outcome { CHECKED_IN, REPEATED, EXPIRED, OTHER_ORGANIZATION, SESSION_MISMATCH, CANCELLED }

    public enum Action { RELEASE, REJECT, BLOCK }

    public enum Phase { UPCOMING, OPEN, ENDED }

    public record Current(Kind kind, UUID sessionId) {
    }

    public record SessionRef(UUID sessionId, String title, OffsetDateTime startsAt) {
    }

    public record DeskSession(
            UUID sessionId,
            String title,
            String placeName,
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

    /** 一条工作台记录。currentSession 只在当前场次是场馆场次时有，活动场次由工作台接口补上。 */
    public record Entry(
            UUID recordId,
            Outcome outcome,
            Method method,
            OffsetDateTime occurredAt,
            String codeTail,
            String holderName,
            int people,
            Long amountMinor,
            SessionRef bookingSession,
            Kind currentKind,
            UUID currentSessionId,
            SessionRef currentSession,
            OffsetDateTime firstCheckedInAt,
            List<Action> allowedActions,
            Resolution resolution
    ) {
    }

    static final Pattern CODE = Pattern.compile("^VEN-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$");
    static final Duration REPEAT_SCAN_WINDOW = Duration.ofMinutes(2);
    static final int NOTE_LIMIT = 200;

    private final VenueCheckinMapper mapper;
    private final Clock clock;

    public VenueCheckinDesk(VenueCheckinMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 这个机构的操作员能不能用工作台核销到馆预约：机构运营场馆。 */
    public boolean verifies(UUID organizationId) {
        return organizationId != null && mapper.operatesVenues(organizationId);
    }

    public List<DeskSession> sessions(UUID organizationId, LocalDate date) {
        if (organizationId == null) {
            throw new VenueCheckinException(Reason.FORBIDDEN);
        }
        OffsetDateTime now = now();
        return mapper.findSessions(organizationId, dayStart(date), dayStart(date.plusDays(1))).stream()
                .map(row -> toSession(row, now))
                .toList();
    }

    public Optional<SessionRef> sessionRef(UUID organizationId, UUID sessionId) {
        if (organizationId == null || sessionId == null) {
            return Optional.empty();
        }
        DeskSessionRow row = mapper.findOwnSession(organizationId, sessionId);
        return row == null ? Optional.empty() : Optional.of(new SessionRef(row.sessionId(), row.title(), row.startsAt()));
    }

    public List<Entry> records(UUID operatorId, UUID organizationId, LocalDate date) {
        requireOperator(operatorId, organizationId);
        return mapper.findRecords(operatorId, organizationId, dayStart(date), dayStart(date.plusDays(1))).stream()
                .map(VenueCheckinDesk::toEntry)
                .toList();
    }

    public Entry record(UUID operatorId, UUID organizationId, UUID recordId) {
        requireOperator(operatorId, organizationId);
        RecordRow row = recordId == null ? null : mapper.findRecord(operatorId, organizationId, recordId);
        if (row == null) {
            throw new VenueCheckinException(Reason.NOT_FOUND);
        }
        return toEntry(row);
    }

    /**
     * 按核销码核销整单。核销成功和被拦下都返回记录（outcome 说明是哪一种），都已落库；
     * 没有这个码、还没到核销时间时抛出，不记录。
     *
     * @param current 工作台上正在核销的场次；为空时不判断场次不符。是场馆场次时必须是本机构的。
     */
    @Transactional
    public Entry verify(UUID operatorId, UUID organizationId, String codeText, Method method, Current current) {
        requireOperator(operatorId, organizationId);
        String code = normalizeCode(codeText);
        if (method == null || (current != null && (current.kind() == null || current.sessionId() == null))) {
            throw new IllegalArgumentException("verification is invalid");
        }
        if (current != null && current.kind() == Kind.VENUE
                && mapper.findOwnSession(organizationId, current.sessionId()) == null) {
            throw new IllegalArgumentException("current session is not this organization's");
        }
        BookingRow booking = mapper.lockBookingByCode(code);
        if (booking == null) {
            throw new VenueCheckinException(Reason.NOT_FOUND);
        }
        OffsetDateTime now = now();
        Outcome outcome = outcomeOf(booking, organizationId, current, now);
        if (outcome == Outcome.CHECKED_IN) {
            if (now.isBefore(booking.startsAt().minus(VenueBookingService.CHECKIN_OPENS_BEFORE))) {
                throw new VenueCheckinException(Reason.NOT_OPEN);
            }
            mapper.checkIn(booking.bookingId(), operatorId, now);
            mapper.insertEvent(booking.bookingId(), "CHECKED_IN", operatorId, null, null, now);
        } else {
            RecordRow recent = mapper.findRecentRejection(
                    operatorId,
                    organizationId,
                    booking.bookingId(),
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
                booking.bookingId(),
                own ? booking.sessionId() : null,
                code.substring(code.length() - 4),
                method.name(),
                current == null ? null : current.kind().name(),
                current == null ? null : current.sessionId(),
                outcome.name(),
                own ? booking.people() : 1,
                own ? booking.amountMinor() : null,
                outcome == Outcome.REPEATED ? booking.checkedInAt() : null,
                now);
        return toEntry(mapper.findRecord(operatorId, organizationId, recordId));
    }

    /** 人工处理一条被拦下的记录，只能登记一次；登记放行要先人工核验身份。 */
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
            throw new VenueCheckinException(Reason.NOT_FOUND);
        }
        if (row.resolution() != null) {
            if (Action.valueOf(row.resolution()) == action) {
                return toEntry(row);
            }
            throw new VenueCheckinException(Reason.ALREADY_RESOLVED);
        }
        if (!allowedActions(row).contains(action)) {
            throw new VenueCheckinException(Reason.ACTION_NOT_ALLOWED);
        }
        OffsetDateTime now = now();
        UUID admitted = action == Action.RELEASE ? release(operatorId, row, now) : null;
        int updated = mapper.resolveRecord(operatorId, recordId, action.name(), identityChecked, note, admitted, now);
        if (updated != 1) {
            throw new VenueCheckinException(Reason.ALREADY_RESOLVED);
        }
        return toEntry(mapper.findRecord(operatorId, organizationId, recordId));
    }

    private UUID release(UUID operatorId, RecordRow row, OffsetDateTime now) {
        UUID original = row.bookingSessionId();
        UUID target = row.currentSessionId();
        UUID first = original.compareTo(target) < 0 ? original : target;
        UUID second = first.equals(original) ? target : original;
        SessionLockRow firstSession = mapper.lockSession(first);
        SessionLockRow secondSession = mapper.lockSession(second);
        SessionLockRow targetSession = first.equals(target) ? firstSession : secondSession;
        BookingRow booking = mapper.lockBooking(row.bookingId());
        if (booking == null || !VenueBookingRecord.CONFIRMED.equals(booking.status())
                || !original.equals(booking.sessionId())) {
            throw new VenueCheckinException(Reason.RELEASE_NOT_ALLOWED);
        }
        if (targetSession == null || !"ACTIVE".equals(targetSession.status())
                || !targetSession.projectId().equals(booking.projectId())
                || now.isBefore(targetSession.startsAt().minus(VenueBookingService.CHECKIN_OPENS_BEFORE))
                || !now.isBefore(targetSession.endsAt())) {
            throw new VenueCheckinException(Reason.RELEASE_NOT_ALLOWED);
        }
        if (targetSession.bookedCount() + booking.people() > targetSession.capacity()) {
            throw new VenueCheckinException(Reason.SEATS_NOT_ENOUGH);
        }
        if (mapper.countOtherActiveParticipants(booking.bookingId(), target) > 0) {
            throw new VenueCheckinException(Reason.RELEASE_NOT_ALLOWED);
        }
        mapper.moveAndCheckIn(booking.bookingId(), target, operatorId, now);
        mapper.moveParticipants(booking.bookingId(), target, now);
        mapper.addBooked(original, -booking.people(), now);
        mapper.addBooked(target, booking.people(), now);
        mapper.insertEvent(booking.bookingId(), "RESCHEDULED", operatorId, original, target, now);
        mapper.insertEvent(booking.bookingId(), "CHECKED_IN", operatorId, null, null, now);
        return target;
    }

    // ---- 规则 ----

    static Outcome outcomeOf(BookingRow booking, UUID organizationId, Current current, OffsetDateTime now) {
        if (!organizationId.equals(booking.operatorId())) {
            return Outcome.OTHER_ORGANIZATION;
        }
        if (VenueBookingRecord.CANCELLED.equals(booking.status())) {
            return Outcome.CANCELLED;
        }
        if (VenueBookingRecord.CHECKED_IN.equals(booking.status())) {
            return Outcome.REPEATED;
        }
        if (!now.isBefore(booking.endsAt())) {
            return Outcome.EXPIRED;
        }
        if (current != null && (current.kind() != Kind.VENUE || !current.sessionId().equals(booking.sessionId()))) {
            return Outcome.SESSION_MISMATCH;
        }
        return Outcome.CHECKED_IN;
    }

    static List<Action> allowedActions(RecordRow row) {
        if (row.resolution() != null) {
            return List.of();
        }
        return switch (Outcome.valueOf(row.outcome())) {
            case CHECKED_IN -> List.of();
            case SESSION_MISMATCH, EXPIRED -> {
                boolean sameProject = Kind.VENUE.name().equals(row.currentSessionKind())
                        && row.currentProjectId() != null
                        && row.currentProjectId().equals(row.bookingProjectId())
                        && !row.currentSessionId().equals(row.bookingSessionId());
                yield sameProject ? List.of(Action.RELEASE, Action.REJECT) : List.of(Action.REJECT);
            }
            case REPEATED, OTHER_ORGANIZATION, CANCELLED -> List.of(Action.BLOCK);
        };
    }

    static String normalizeCode(String codeText) {
        String code = codeText == null ? "" : codeText.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code is invalid");
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

    static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        return new String(Character.toChars(trimmed.codePointAt(0))) + "**";
    }

    private static DeskSession toSession(DeskSessionRow row, OffsetDateTime now) {
        OffsetDateTime opens = row.startsAt().minus(VenueBookingService.CHECKIN_OPENS_BEFORE);
        Phase phase = now.isBefore(opens) ? Phase.UPCOMING : now.isBefore(row.endsAt()) ? Phase.OPEN : Phase.ENDED;
        return new DeskSession(
                row.sessionId(),
                row.title(),
                row.placeName(),
                row.startsAt(),
                row.endsAt(),
                opens,
                row.endsAt(),
                phase,
                row.expectedPeople(),
                row.checkedInPeople());
    }

    static Entry toEntry(RecordRow row) {
        SessionRef bookingSession = row.bookingSessionId() == null
                ? null
                : new SessionRef(row.bookingSessionId(), row.bookingTitle(), row.bookingStartsAt());
        Kind currentKind = row.currentSessionKind() == null ? null : Kind.valueOf(row.currentSessionKind());
        SessionRef currentSession = currentKind == Kind.VENUE && row.currentTitle() != null
                ? new SessionRef(row.currentSessionId(), row.currentTitle(), row.currentStartsAt())
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
                                : new SessionRef(row.admittedSessionId(), row.admittedTitle(), row.admittedStartsAt()));
        return new Entry(
                row.recordId(),
                Outcome.valueOf(row.outcome()),
                Method.valueOf(row.method()),
                row.occurredAt(),
                row.codeTail(),
                maskName(row.holderName()),
                row.people(),
                row.amountMinor(),
                bookingSession,
                currentKind,
                row.currentSessionId(),
                currentSession,
                row.firstCheckedInAt(),
                allowedActions(row),
                resolution);
    }

    private static void requireOperator(UUID operatorId, UUID organizationId) {
        if (operatorId == null || organizationId == null) {
            throw new VenueCheckinException(Reason.FORBIDDEN);
        }
    }

    private static OffsetDateTime dayStart(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        return date.atStartOfDay(ExperienceCatalogReadService.VENUE_ZONE).toOffsetDateTime();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
