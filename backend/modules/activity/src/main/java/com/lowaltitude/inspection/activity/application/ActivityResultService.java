package com.lowaltitude.inspection.activity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityResultMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityResultMapper.RosterRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityResultMapper.SessionRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityResultMapper.TicketLockRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 机构工作台的“活动结果”（S5-3）：主办或承办机构的操作员给已签到的参与人录入完成情况、成绩和证书说明，
 * 客户在凭证和订单页看到；第一次录入时给报名的账号发一条站内信（同一张报名单只发一次）。
 *
 * <p>只列已经开始的场次（最近 180 天）。名单里的姓名只给姓（和核销工作台一样），参与人资料只对所属账号可见；
 * 凭证号只给后 4 位用来核对。机构只从令牌里取，别的机构的场次和凭证当作不存在。
 */
public class ActivityResultService {

    static final Duration LOOKBACK = Duration.ofDays(180);
    static final int MAX_SCORE_LENGTH = 40;
    static final int MAX_CERTIFICATE_LENGTH = 100;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Logger log = LoggerFactory.getLogger(ActivityResultService.class);

    private final ActivityResultMapper mapper;
    private final ActivityEnrollmentMapper enrollments;
    private final ActivityEnrollmentNotifier notifier;
    private final Clock clock;

    public ActivityResultService(
            ActivityResultMapper mapper,
            ActivityEnrollmentMapper enrollments,
            ActivityEnrollmentNotifier notifier,
            Clock clock
    ) {
        this.mapper = mapper;
        this.enrollments = enrollments;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ResultSessionPageView sessions(UUID organizationId, int page, int size) {
        requireOrganization(organizationId);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        OffsetDateTime now = now();
        OffsetDateTime since = now.minus(LOOKBACK);
        long total = mapper.countSessions(organizationId, now, since);
        List<ResultSessionView> items = mapper.findSessions(
                        organizationId, now, since, size, Math.multiplyExact(page - 1, size)).stream()
                .map(ActivityResultService::toSession)
                .toList();
        return new ResultSessionPageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public ResultRosterView roster(UUID organizationId, UUID sessionId) {
        requireOrganization(organizationId);
        if (sessionId == null) {
            throw new IllegalArgumentException("session id is required");
        }
        SessionRow session = mapper.findSession(organizationId, sessionId);
        if (session == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        List<ResultRosterEntryView> entries = mapper.findRoster(organizationId, sessionId).stream()
                .map(ActivityResultService::toEntry)
                .toList();
        return new ResultRosterView(toSession(session), !now().isBefore(session.startsAt()), entries);
    }

    /**
     * 录入或修改一位的结果。第一次录入 expectedVersion 不带；修改要带读到的 resultVersion，对不上回
     * ACT_RESULT_VERSION_CONFLICT。没签到的回 ACT_RESULT_NOT_ALLOWED。
     */
    @Transactional
    public ResultRosterEntryView record(
            UUID operatorId,
            UUID organizationId,
            UUID ticketId,
            RecordResultCommand command
    ) {
        requireOrganization(organizationId);
        if (operatorId == null || ticketId == null || command == null || command.completion() == null) {
            throw new IllegalArgumentException("result is invalid");
        }
        ResultCompletion completion = parseCompletion(command.completion());
        String score = optionalText(command.scoreText(), MAX_SCORE_LENGTH, "score");
        String certificate = optionalText(command.certificateNote(), MAX_CERTIFICATE_LENGTH, "certificate note");
        TicketLockRow ticket = mapper.lockTicket(organizationId, ticketId);
        if (ticket == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        if (!TicketStatus.CHECKED_IN.name().equals(ticket.status())) {
            throw new ActivityEnrollmentException(Error.RESULT_NOT_ALLOWED);
        }
        OffsetDateTime now = now();
        boolean first;
        if (ticket.resultVersion() == null) {
            if (command.expectedVersion() != null) {
                throw new ActivityEnrollmentException(Error.RESULT_VERSION_CONFLICT);
            }
            first = mapper.insertResult(ticketId, organizationId, completion.name(), score, certificate, operatorId,
                    now) != null;
            if (!first) {
                throw new ActivityEnrollmentException(Error.RESULT_VERSION_CONFLICT);
            }
        } else {
            if (command.expectedVersion() == null
                    || mapper.updateResult(ticketId, organizationId, completion.name(), score, certificate,
                            operatorId, command.expectedVersion(), now) == 0) {
                throw new ActivityEnrollmentException(Error.RESULT_VERSION_CONFLICT);
            }
            first = false;
        }
        enrollments.insertEvent(ticket.enrollmentId(), ticketId, "RESULT_RECORDED", operatorId, now);
        if (first) {
            try {
                notifier.resultRecorded(new ActivityEnrollmentNotifier.ResultRecorded(
                        ticket.accountId(), ticket.enrollmentId(), ticket.title(), now.toInstant()));
            } catch (RuntimeException exception) {
                log.error("telling account {} about the result of enrollment {} failed", ticket.accountId(),
                        ticket.enrollmentId(), exception);
            }
        }
        return mapper.findRoster(organizationId, ticket.sessionId()).stream()
                .filter(row -> row.ticketId().equals(ticketId))
                .findFirst()
                .map(ActivityResultService::toEntry)
                .orElseThrow(() -> new ActivityEnrollmentException(Error.NOT_FOUND));
    }

    private static ResultSessionView toSession(SessionRow row) {
        return new ResultSessionView(
                row.sessionId(),
                row.activityId(),
                row.title(),
                row.label() == null || row.label().isBlank() ? null : row.label().trim(),
                row.startsAt(),
                row.endsAt(),
                (int) row.enrolled(),
                (int) row.checkedIn(),
                (int) row.recorded());
    }

    private static ResultRosterEntryView toEntry(RosterRow row) {
        String code = row.ticketCode();
        return new ResultRosterEntryView(
                row.ticketId(),
                ActivityCheckinDesk.maskName(row.participantName()),
                row.participantAge(),
                code == null || code.length() < 4 ? null : code.substring(code.length() - 4),
                TicketStatus.CHECKED_IN.name().equals(row.status()),
                row.checkedInAt(),
                row.teamName(),
                row.completion() == null
                        ? null
                        : new TicketResultView(
                                ResultCompletion.valueOf(row.completion()),
                                row.scoreText(),
                                row.certificateNote(),
                                row.recordedAt()),
                row.resultVersion());
    }

    private static ResultCompletion parseCompletion(String code) {
        try {
            return ResultCompletion.valueOf(code.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("completion is invalid");
        }
    }

    private static String optionalText(String text, int maxLength, String what) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.codePointCount(0, value.length()) > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return value;
    }

    private static void requireOrganization(UUID organizationId) {
        if (organizationId == null) {
            throw new ActivityEnrollmentException(Error.FORBIDDEN);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}

record RecordResultCommand(String completion, String scoreText, String certificateNote, Integer expectedVersion) {
}

record ResultSessionView(
        UUID sessionId,
        UUID activityId,
        String activityTitle,
        String label,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        int enrolledPeople,
        int checkedInPeople,
        int recordedPeople
) {
}

record ResultSessionPageView(int page, int size, long total, boolean hasMore, List<ResultSessionView> items) {
}

/** @param started 场次开始了才能录结果 */
record ResultRosterView(ResultSessionView session, boolean started, List<ResultRosterEntryView> entries) {
}

/**
 * @param holderName   只给姓，如“王**”
 * @param codeTail     凭证号后 4 位
 * @param resultVersion 修改结果时带回来；还没录时为空
 */
record ResultRosterEntryView(
        UUID ticketId,
        String holderName,
        Integer age,
        String codeTail,
        boolean checkedIn,
        OffsetDateTime checkedInAt,
        String teamName,
        TicketResultView result,
        Integer resultVersion
) {
}
