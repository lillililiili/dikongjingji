package com.lowaltitude.inspection.checkin.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityCheckinDesk;
import com.lowaltitude.inspection.experience.application.VenueCheckinDesk;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 核销工作台（S3-3，契约 contracts/openapi-checkin-workbench.yaml）：把活动模块和体验模块各自的一半合在一起。
 * 凭证号按前缀交给对应的模块（ACT- 活动、VEN- 场馆）；工作台上正在核销的场次先由它所属的模块确认是本机构的。
 * 两个模块都没接上时（比如没有数据库的测试上下文）这个机构什么都不能核销。
 *
 * <p>被拦下的核销在模块里已经提交了记录，这里再抛 {@link CheckinRejectedException}，
 * 这样 409 里带的那条记录是落了库的（控制器不在事务里）。
 */
@Component
public class CheckinWorkbench {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ObjectProvider<ActivityCheckinDesk> activityDesk;
    private final ObjectProvider<VenueCheckinDesk> venueDesk;
    private final Clock clock;

    @Autowired
    public CheckinWorkbench(
            ObjectProvider<ActivityCheckinDesk> activityDesk,
            ObjectProvider<VenueCheckinDesk> venueDesk
    ) {
        this(activityDesk, venueDesk, Clock.systemUTC());
    }

    CheckinWorkbench(
            ObjectProvider<ActivityCheckinDesk> activityDesk,
            ObjectProvider<VenueCheckinDesk> venueDesk,
            Clock clock
    ) {
        this.activityDesk = activityDesk;
        this.venueDesk = venueDesk;
        this.clock = clock;
    }

    public CheckinWorkbenchView workbench(UUID organizationId, LocalDate requested) {
        LocalDate date = dateOrToday(requested);
        ActivityCheckinDesk activities = activityDesk.getIfAvailable();
        VenueCheckinDesk venues = venueDesk.getIfAvailable();
        List<CheckinKind> kinds = new ArrayList<>();
        List<CheckinSessionView> sessions = new ArrayList<>();
        if (activities != null && activities.verifies(organizationId)) {
            kinds.add(CheckinKind.ACTIVITY);
            activities.sessions(organizationId, date).forEach(session -> sessions.add(new CheckinSessionView(
                    CheckinKind.ACTIVITY,
                    session.sessionId(),
                    session.title(),
                    session.placeName(),
                    session.label(),
                    session.startsAt(),
                    session.endsAt(),
                    session.opensAt(),
                    session.closesAt(),
                    CheckinPhase.valueOf(session.phase().name()),
                    session.expectedPeople(),
                    session.checkedInPeople())));
        }
        if (venues != null && venues.verifies(organizationId)) {
            kinds.add(CheckinKind.VENUE);
            venues.sessions(organizationId, date).forEach(session -> sessions.add(new CheckinSessionView(
                    CheckinKind.VENUE,
                    session.sessionId(),
                    session.title(),
                    session.placeName(),
                    null,
                    session.startsAt(),
                    session.endsAt(),
                    session.opensAt(),
                    session.closesAt(),
                    CheckinPhase.valueOf(session.phase().name()),
                    session.expectedPeople(),
                    session.checkedInPeople())));
        }
        sessions.sort(Comparator.comparing(CheckinSessionView::startsAt)
                .thenComparing(view -> view.kind().ordinal())
                .thenComparing(CheckinSessionView::sessionId));
        return new CheckinWorkbenchView(date, List.copyOf(kinds), List.copyOf(sessions));
    }

    /** 本人这一天的记录，两个模块的合在一起，新的在前。 */
    public CheckinRecordListView records(UUID operatorId, UUID organizationId, LocalDate requested) {
        LocalDate date = dateOrToday(requested);
        List<CheckinRecordView> items = new ArrayList<>();
        ActivityCheckinDesk activities = activityDesk.getIfAvailable();
        VenueCheckinDesk venues = venueDesk.getIfAvailable();
        if (activities != null) {
            activities.records(operatorId, organizationId, date)
                    .forEach(entry -> items.add(toView(entry, organizationId)));
        }
        if (venues != null) {
            venues.records(operatorId, organizationId, date)
                    .forEach(entry -> items.add(toView(entry, organizationId)));
        }
        items.sort(Comparator.comparing(CheckinRecordView::occurredAt).reversed()
                .thenComparing(CheckinRecordView::recordId, Comparator.reverseOrder()));
        return new CheckinRecordListView(date, List.copyOf(items));
    }

    public CheckinRecordView record(UUID operatorId, UUID organizationId, CheckinKind kind, UUID recordId) {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        return kind == CheckinKind.ACTIVITY
                ? toView(requireActivities().record(operatorId, organizationId, recordId), organizationId)
                : toView(requireVenues().record(operatorId, organizationId, recordId), organizationId);
    }

    /** 核销成功返回记录；被拦下时抛 {@link CheckinRejectedException}（记录已落库）。 */
    public CheckinRecordView verify(
            UUID operatorId,
            UUID organizationId,
            String codeText,
            CheckinMethod method,
            CheckinKind sessionKind,
            UUID sessionId
    ) {
        String code = codeText == null ? "" : codeText.trim().toUpperCase(Locale.ROOT);
        if (method == null || (sessionKind == null) != (sessionId == null)) {
            throw new IllegalArgumentException("verification is invalid");
        }
        if (sessionKind != null && sessionRef(organizationId, sessionKind, sessionId).isEmpty()) {
            throw new IllegalArgumentException("current session is not this organization's");
        }
        CheckinRecordView record;
        if (code.startsWith("ACT-")) {
            ActivityCheckinDesk.Current current = sessionKind == null
                    ? null
                    : new ActivityCheckinDesk.Current(ActivityCheckinDesk.Kind.valueOf(sessionKind.name()), sessionId);
            record = toView(requireActivities().verify(
                    operatorId,
                    organizationId,
                    code,
                    ActivityCheckinDesk.Method.valueOf(method.name()),
                    current), organizationId);
        } else if (code.startsWith("VEN-")) {
            VenueCheckinDesk.Current current = sessionKind == null
                    ? null
                    : new VenueCheckinDesk.Current(VenueCheckinDesk.Kind.valueOf(sessionKind.name()), sessionId);
            record = toView(requireVenues().verify(
                    operatorId,
                    organizationId,
                    code,
                    VenueCheckinDesk.Method.valueOf(method.name()),
                    current), organizationId);
        } else {
            throw new IllegalArgumentException("code is invalid");
        }
        if (record.outcome() != CheckinOutcome.CHECKED_IN) {
            throw new CheckinRejectedException(record);
        }
        return record;
    }

    public CheckinRecordView resolve(
            UUID operatorId,
            UUID organizationId,
            CheckinKind kind,
            UUID recordId,
            CheckinAction action,
            boolean identityChecked,
            String note
    ) {
        if (kind == null || action == null) {
            throw new IllegalArgumentException("resolution is invalid");
        }
        return kind == CheckinKind.ACTIVITY
                ? toView(requireActivities().resolve(
                        operatorId,
                        organizationId,
                        recordId,
                        ActivityCheckinDesk.Action.valueOf(action.name()),
                        identityChecked,
                        note), organizationId)
                : toView(requireVenues().resolve(
                        operatorId,
                        organizationId,
                        recordId,
                        VenueCheckinDesk.Action.valueOf(action.name()),
                        identityChecked,
                        note), organizationId);
    }

    // ---- 合并 ----

    private Optional<CheckinSessionRefView> sessionRef(UUID organizationId, CheckinKind kind, UUID sessionId) {
        if (kind == CheckinKind.ACTIVITY) {
            ActivityCheckinDesk activities = activityDesk.getIfAvailable();
            return activities == null
                    ? Optional.empty()
                    : activities.sessionRef(organizationId, sessionId).map(CheckinWorkbench::toRef);
        }
        VenueCheckinDesk venues = venueDesk.getIfAvailable();
        return venues == null ? Optional.empty() : venues.sessionRef(organizationId, sessionId).map(CheckinWorkbench::toRef);
    }

    private CheckinRecordView toView(ActivityCheckinDesk.Entry entry, UUID organizationId) {
        CheckinSessionRefView current = entry.currentSession() != null
                ? toRef(entry.currentSession())
                : otherKindSession(organizationId, entry.currentKind() == null ? null : entry.currentKind().name(),
                        entry.currentSessionId());
        ActivityCheckinDesk.Resolution resolution = entry.resolution();
        return new CheckinRecordView(
                CheckinKind.ACTIVITY,
                entry.recordId(),
                CheckinOutcome.valueOf(entry.outcome().name()),
                CheckinMethod.valueOf(entry.method().name()),
                entry.occurredAt(),
                entry.codeTail(),
                entry.holderName(),
                entry.people(),
                entry.amountMinor(),
                entry.ticketSession() == null ? null : toRef(entry.ticketSession()),
                current,
                entry.firstCheckedInAt(),
                entry.allowedActions().stream().map(action -> CheckinAction.valueOf(action.name())).toList(),
                resolution == null ? null : new CheckinResolutionView(
                        CheckinAction.valueOf(resolution.action().name()),
                        resolution.identityChecked(),
                        resolution.note(),
                        resolution.resolvedAt(),
                        resolution.admittedSession() == null ? null : toRef(resolution.admittedSession())));
    }

    private CheckinRecordView toView(VenueCheckinDesk.Entry entry, UUID organizationId) {
        CheckinSessionRefView current = entry.currentSession() != null
                ? toRef(entry.currentSession())
                : otherKindSession(organizationId, entry.currentKind() == null ? null : entry.currentKind().name(),
                        entry.currentSessionId());
        VenueCheckinDesk.Resolution resolution = entry.resolution();
        return new CheckinRecordView(
                CheckinKind.VENUE,
                entry.recordId(),
                CheckinOutcome.valueOf(entry.outcome().name()),
                CheckinMethod.valueOf(entry.method().name()),
                entry.occurredAt(),
                entry.codeTail(),
                entry.holderName(),
                entry.people(),
                entry.amountMinor(),
                entry.bookingSession() == null ? null : toRef(entry.bookingSession()),
                current,
                entry.firstCheckedInAt(),
                entry.allowedActions().stream().map(action -> CheckinAction.valueOf(action.name())).toList(),
                resolution == null ? null : new CheckinResolutionView(
                        CheckinAction.valueOf(resolution.action().name()),
                        resolution.identityChecked(),
                        resolution.note(),
                        resolution.resolvedAt(),
                        resolution.admittedSession() == null ? null : toRef(resolution.admittedSession())));
    }

    /** 当前场次是另一个模块的：问那个模块要名称和时间（只认本机构的）。 */
    private CheckinSessionRefView otherKindSession(UUID organizationId, String kind, UUID sessionId) {
        if (kind == null || sessionId == null) {
            return null;
        }
        return sessionRef(organizationId, CheckinKind.valueOf(kind), sessionId).orElse(null);
    }

    private static CheckinSessionRefView toRef(ActivityCheckinDesk.SessionRef ref) {
        return new CheckinSessionRefView(CheckinKind.ACTIVITY, ref.sessionId(), ref.title(), ref.label(), ref.startsAt());
    }

    private static CheckinSessionRefView toRef(VenueCheckinDesk.SessionRef ref) {
        return new CheckinSessionRefView(CheckinKind.VENUE, ref.sessionId(), ref.title(), null, ref.startsAt());
    }

    private ActivityCheckinDesk requireActivities() {
        ActivityCheckinDesk desk = activityDesk.getIfAvailable();
        if (desk == null) {
            throw new IllegalStateException("activity check-in is not available");
        }
        return desk;
    }

    private VenueCheckinDesk requireVenues() {
        VenueCheckinDesk desk = venueDesk.getIfAvailable();
        if (desk == null) {
            throw new IllegalStateException("venue check-in is not available");
        }
        return desk;
    }

    private LocalDate dateOrToday(LocalDate requested) {
        return requested != null ? requested : OffsetDateTime.now(clock).atZoneSameInstant(ZONE).toLocalDate();
    }
}

enum CheckinKind { ACTIVITY, VENUE }

enum CheckinPhase { UPCOMING, OPEN, ENDED }

enum CheckinOutcome { CHECKED_IN, REPEATED, EXPIRED, OTHER_ORGANIZATION, SESSION_MISMATCH, CANCELLED, UNPAID }

enum CheckinMethod { SCAN, MANUAL }

enum CheckinAction { RELEASE, REJECT, BLOCK }

record CheckinWorkbenchView(LocalDate date, List<CheckinKind> kinds, List<CheckinSessionView> sessions) {
}

record CheckinSessionView(
        CheckinKind kind,
        UUID sessionId,
        String title,
        String placeName,
        String label,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime checkInOpensAt,
        OffsetDateTime checkInClosesAt,
        CheckinPhase phase,
        int expectedPeople,
        int checkedInPeople
) {
}

record CheckinSessionRefView(CheckinKind kind, UUID sessionId, String title, String label, OffsetDateTime startsAt) {
}

record CheckinRecordListView(LocalDate date, List<CheckinRecordView> items) {
}

record CheckinRecordView(
        CheckinKind kind,
        UUID recordId,
        CheckinOutcome outcome,
        CheckinMethod method,
        OffsetDateTime occurredAt,
        String codeTail,
        String holderName,
        int people,
        Long amountMinor,
        CheckinSessionRefView ticketSession,
        CheckinSessionRefView currentSession,
        OffsetDateTime firstCheckedInAt,
        List<CheckinAction> allowedActions,
        CheckinResolutionView resolution
) {
}

record CheckinResolutionView(
        CheckinAction action,
        boolean identityChecked,
        String note,
        OffsetDateTime resolvedAt,
        CheckinSessionRefView admittedSession
) {
}
