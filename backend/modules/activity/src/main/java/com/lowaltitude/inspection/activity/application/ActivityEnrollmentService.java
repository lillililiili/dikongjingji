package com.lowaltitude.inspection.activity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.CheckinRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.EnrollmentKeyRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.EnrollmentRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.ParticipantRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.SessionRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.TicketRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 免费活动报名：参与人、报名、凭证、取消、核销（契约 contracts/openapi-activity.yaml 1.1.0）。
 *
 * <p>规则（用户 2026-11-17 确认“按原型先做”）：
 * 未成年人由监护人账号添加为“子女”后代为报名；免费报名在场次开始前可以取消；
 * 只有免费活动能在这里报名，收费、询价、候补、团队报名都不在这一步。
 *
 * <p>名额只在锁住场次那一行之后增减。直接依赖 mapper 接口，测试里用内存实现替换。
 */
public class ActivityEnrollmentService {

    static final int MAX_CHILDREN = 10;
    static final int MAX_PARTICIPANTS_PER_ENROLLMENT = 10;
    static final int MAX_NAME_LENGTH = 20;
    /** “本人”按成年人处理；“子女”必须未满这个年龄。 */
    static final int ADULT_AGE = 18;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String DEFAULT_SELF_NAME = "本人";
    private static final Pattern TICKET_CODE =
            Pattern.compile("^ACT-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$");

    private final ActivityEnrollmentMapper mapper;
    private final Clock clock;
    private final Supplier<String> ticketCodes;

    ActivityEnrollmentService(
            ActivityEnrollmentMapper mapper,
            Clock clock,
            Supplier<String> ticketCodes
    ) {
        this.mapper = mapper;
        this.clock = clock;
        this.ticketCodes = ticketCodes;
    }

    // ---- 参与人 ----

    /**
     * 当前账号的参与人，“本人”在最前。第一次读取时按账号显示名补上“本人”。
     * 带 sessionId 时，每个人附上能不能报这一场以及原因。
     */
    @Transactional
    public ParticipantListView listParticipants(UUID accountId, String accountName, UUID sessionId) {
        requireAccount(accountId);
        mapper.ensureSelf(accountId, selfName(accountName));
        SessionRow session = null;
        Set<UUID> enrolled = Set.of();
        if (sessionId != null) {
            session = mapper.findSession(sessionId);
            if (session == null || !isListed(session)) {
                throw new ActivityEnrollmentException(Error.NOT_FOUND);
            }
            enrolled = Set.copyOf(mapper.findEnrolledParticipantIds(sessionId, accountId));
        }
        LocalDate today = today();
        List<ParticipantView> items = new ArrayList<>();
        for (ParticipantRow row : mapper.findParticipants(accountId)) {
            items.add(toParticipant(row, today, session, enrolled));
        }
        return new ParticipantListView(items);
    }

    @Transactional
    public ParticipantView addChild(
            UUID accountId,
            String nameText,
            String birthMonthText,
            boolean guardianConfirmed
    ) {
        requireAccount(accountId);
        String name = requireName(nameText);
        LocalDate birthMonth = parseBirthMonth(birthMonthText);
        LocalDate today = today();
        if (birthMonth.isAfter(today) || ageOn(birthMonth, today) >= ADULT_AGE) {
            throw new IllegalArgumentException("child must be a minor");
        }
        if (!guardianConfirmed) {
            throw new IllegalArgumentException("guardian confirmation is required");
        }
        if (mapper.countChildren(accountId) >= MAX_CHILDREN) {
            throw new ActivityEnrollmentException(Error.PARTICIPANT_LIMIT);
        }
        UUID participantId = mapper.insertChild(accountId, name, birthMonth, now());
        if (participantId == null) {
            throw new ActivityEnrollmentException(Error.PARTICIPANT_DUPLICATE);
        }
        return toParticipant(requireParticipant(accountId, participantId), today, null, Set.of());
    }

    @Transactional
    public ParticipantView renameParticipant(UUID accountId, UUID participantId, String nameText) {
        requireAccount(accountId);
        String name = requireName(nameText);
        ParticipantRow row = requireParticipant(accountId, participantId);
        if (row.birthMonth() != null
                && mapper.countSameChild(accountId, name, row.birthMonth(), participantId) > 0) {
            throw new ActivityEnrollmentException(Error.PARTICIPANT_DUPLICATE);
        }
        mapper.renameParticipant(accountId, participantId, name, now());
        return toParticipant(requireParticipant(accountId, participantId), today(), null, Set.of());
    }

    /** “活动照片可公开使用”只对子女单独授权，可随时撤回。 */
    @Transactional
    public ParticipantView setPhotoUse(UUID accountId, UUID participantId, boolean authorized) {
        requireAccount(accountId);
        ParticipantRow row = requireParticipant(accountId, participantId);
        if (!isChild(row)) {
            throw new IllegalArgumentException("photo use applies to children only");
        }
        mapper.setPhotoUse(accountId, participantId, authorized, now());
        return toParticipant(requireParticipant(accountId, participantId), today(), null, Set.of());
    }

    @Transactional
    public void deleteChild(UUID accountId, UUID participantId) {
        requireAccount(accountId);
        ParticipantRow row = requireParticipant(accountId, participantId);
        if (!isChild(row)) {
            throw new IllegalArgumentException("the account holder cannot be removed");
        }
        mapper.deleteChild(accountId, participantId, now());
    }

    // ---- 报名 ----

    /**
     * 提交报名。同一个幂等键重复提交返回第一次的结果（created 为 false），不会多占名额。
     */
    @Transactional
    public EnrollmentResult enroll(UUID accountId, UUID idempotencyKey, EnrollCommand command) {
        requireAccount(accountId);
        if (idempotencyKey == null
                || command == null
                || command.activityId() == null
                || command.sessionId() == null
                || command.participantIds() == null
                || command.participantIds().isEmpty()
                || command.participantIds().size() > MAX_PARTICIPANTS_PER_ENROLLMENT
                || command.participantIds().stream().anyMatch(Objects::isNull)
                || !command.noticeAgreed()) {
            throw new IllegalArgumentException("enrollment is invalid");
        }
        List<UUID> participantIds = command.participantIds().stream().distinct().sorted().toList();
        if (participantIds.size() != command.participantIds().size()) {
            throw new IllegalArgumentException("participants are duplicated");
        }
        String requestHash = hash(command.activityId(), command.sessionId(), participantIds);

        SessionRow session = mapper.lockSession(command.sessionId());
        if (session == null || !session.activityId().equals(command.activityId())) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        // 拿到场次锁之后再查幂等键：两个相同的请求同时到，后到的在这里读到先到的结果。
        EnrollmentKeyRow replayed = mapper.findEnrollmentByKey(accountId, idempotencyKey);
        if (replayed != null) {
            if (!requestHash.equals(replayed.requestHash().trim())) {
                throw new ActivityEnrollmentException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new EnrollmentResult(readEnrollment(accountId, replayed.id()), false);
        }
        if (!isListed(session)) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        if (!ActivityPriceMode.FREE.name().equals(session.priceMode())) {
            throw new ActivityEnrollmentException(Error.ENROLLMENT_UNSUPPORTED);
        }
        OffsetDateTime now = now();
        ActivityEnrollmentStatus status = ActivityEnrollmentStatus.ofSession(
                now,
                session.startsAt(),
                session.endsAt(),
                session.enrollmentClosesAt(),
                session.capacity(),
                session.enrolledCount(),
                session.waitlistAllowed());
        if (status == ActivityEnrollmentStatus.CLOSED || status == ActivityEnrollmentStatus.ENDED) {
            throw new ActivityEnrollmentException(Error.ENROLLMENT_NOT_OPEN);
        }

        Set<UUID> enrolled = Set.copyOf(
                mapper.findEnrolledParticipantIds(session.sessionId(), accountId));
        LocalDate sessionDate = dateOf(session.startsAt());
        List<ParticipantRow> participants = new ArrayList<>();
        for (UUID participantId : participantIds) {
            ParticipantRow row = requireParticipant(accountId, participantId);
            ParticipantEligibilityReason reason = eligibility(row, session, enrolled);
            if (reason == ParticipantEligibilityReason.ALREADY_ENROLLED) {
                throw new ActivityEnrollmentException(Error.PARTICIPANT_ALREADY_ENROLLED);
            }
            if (reason != ParticipantEligibilityReason.ELIGIBLE) {
                throw new ActivityEnrollmentException(Error.PARTICIPANT_NOT_ELIGIBLE);
            }
            participants.add(row);
        }
        if (session.enrolledCount() + participants.size() > session.capacity()) {
            throw new ActivityEnrollmentException(Error.SESSION_FULL);
        }

        UUID enrollmentId = mapper.insertEnrollment(
                accountId,
                session.activityId(),
                session.sessionId(),
                idempotencyKey,
                requestHash,
                now);
        for (ParticipantRow row : participants) {
            mapper.insertTicket(
                    enrollmentId,
                    session.sessionId(),
                    row.id(),
                    row.name(),
                    row.relation(),
                    isChild(row) ? ageOn(row.birthMonth(), sessionDate) : null,
                    ticketCodes.get(),
                    now);
        }
        mapper.insertEvent(enrollmentId, null, "ENROLLED", accountId, now);
        mapper.addEnrolled(session.sessionId(), participants.size(), now);
        return new EnrollmentResult(readEnrollment(accountId, enrollmentId), true);
    }

    @Transactional(readOnly = true)
    public EnrollmentView readEnrollment(UUID accountId, UUID enrollmentId) {
        requireAccount(accountId);
        if (enrollmentId == null) {
            throw new IllegalArgumentException("enrollment id is required");
        }
        EnrollmentRow row = mapper.findEnrollment(accountId, enrollmentId);
        if (row == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        return toEnrollment(row, mapper.findTickets(List.of(enrollmentId)), now());
    }

    @Transactional(readOnly = true)
    public EnrollmentPageView listEnrollments(UUID accountId, String bucketCode, int page, int size) {
        requireAccount(accountId);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        EnrollmentStatus bucket = EnrollmentStatus.parse(bucketCode);
        OffsetDateTime now = now();
        long total = mapper.countEnrollments(accountId, bucket.name(), now);
        List<EnrollmentRow> rows = mapper.findEnrollmentPage(
                accountId, bucket.name(), now, size, Math.multiplyExact(page - 1, size));
        Map<UUID, List<TicketRow>> tickets = rows.isEmpty()
                ? Map.of()
                : mapper.findTickets(rows.stream().map(EnrollmentRow::enrollmentId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(TicketRow::enrollmentId));
        List<EnrollmentView> items = rows.stream()
                .map(row -> toEnrollment(
                        row, tickets.getOrDefault(row.enrollmentId(), List.of()), now))
                .toList();
        return new EnrollmentPageView(page, size, total, (long) page * size < total, items);
    }

    /**
     * 取消报名。ticketIds 为空表示取消这张报名单里所有还没用的凭证；场次开始后不能取消。
     * 已经取消的再取消一次没有变化。
     */
    @Transactional
    public EnrollmentView cancel(UUID accountId, UUID enrollmentId, List<UUID> ticketIds) {
        requireAccount(accountId);
        if (enrollmentId == null
                || (ticketIds != null && ticketIds.stream().anyMatch(Objects::isNull))) {
            throw new IllegalArgumentException("cancellation is invalid");
        }
        EnrollmentRow row = mapper.findEnrollment(accountId, enrollmentId);
        if (row == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        SessionRow session = mapper.lockSession(row.sessionId());
        List<TicketRow> tickets = mapper.findTickets(List.of(enrollmentId));
        boolean all = ticketIds == null || ticketIds.isEmpty();
        List<TicketRow> targets = tickets;
        if (!all) {
            Set<UUID> wanted = Set.copyOf(ticketIds);
            targets = tickets.stream().filter(ticket -> wanted.contains(ticket.ticketId())).toList();
            if (targets.size() != wanted.size()) {
                throw new IllegalArgumentException("ticket does not belong to the enrollment");
            }
            if (targets.stream().anyMatch(ticket -> "CHECKED_IN".equals(ticket.status()))) {
                throw new ActivityEnrollmentException(Error.CANCEL_NOT_ALLOWED);
            }
        }
        List<TicketRow> active = targets.stream()
                .filter(ticket -> "ACTIVE".equals(ticket.status()))
                .toList();
        if (active.isEmpty()) {
            return readEnrollment(accountId, enrollmentId);
        }
        OffsetDateTime now = now();
        if (!now.isBefore(session.startsAt())) {
            throw new ActivityEnrollmentException(Error.CANCEL_NOT_ALLOWED);
        }
        int released = 0;
        for (TicketRow ticket : active) {
            if (mapper.cancelTicket(enrollmentId, ticket.ticketId(), now) > 0) {
                mapper.insertEvent(enrollmentId, ticket.ticketId(), "TICKET_CANCELLED", accountId, now);
                released++;
            }
        }
        if (released > 0) {
            mapper.addEnrolled(session.sessionId(), -released, now);
        }
        if (mapper.cancelEnrollmentIfEmpty(enrollmentId, now) > 0) {
            mapper.insertEvent(enrollmentId, null, "ENROLLMENT_CANCELLED", accountId, now);
        }
        return readEnrollment(accountId, enrollmentId);
    }

    // ---- 核销 ----

    /**
     * 主办或承办机构的操作员按凭证号核销。别的机构的凭证当作不存在；
     * 同一张凭证再核销一次返回第一次的时间（repeated 为 true），不改任何数据。
     */
    @Transactional
    public CheckinView checkIn(UUID operatorId, UUID organizationId, String ticketCodeText) {
        if (operatorId == null || organizationId == null) {
            throw new ActivityEnrollmentException(Error.FORBIDDEN);
        }
        String ticketCode = ticketCodeText == null ? "" : ticketCodeText.trim().toUpperCase();
        if (!TICKET_CODE.matcher(ticketCode).matches()) {
            throw new IllegalArgumentException("ticket code is invalid");
        }
        CheckinRow row = mapper.lockTicketForOrganizer(ticketCode, organizationId);
        if (row == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        if ("CANCELLED".equals(row.status())) {
            throw new ActivityEnrollmentException(Error.TICKET_NOT_VALID);
        }
        if ("CHECKED_IN".equals(row.status())) {
            return toCheckin(row, row.checkedInAt(), true);
        }
        OffsetDateTime now = now();
        OffsetDateTime opensAt = row.checkInOpensAt() == null
                ? row.startsAt().minusHours(1)
                : row.checkInOpensAt();
        if (now.isBefore(opensAt) || !now.isBefore(sessionEnd(row.startsAt(), row.endsAt()))) {
            throw new ActivityEnrollmentException(Error.CHECKIN_NOT_OPEN);
        }
        mapper.checkIn(row.ticketId(), operatorId, now);
        mapper.insertEvent(row.enrollmentId(), row.ticketId(), "TICKET_CHECKED_IN", operatorId, now);
        return toCheckin(row, now, false);
    }

    // ---- 规则 ----

    /** 只知道出生年月，按月份算周岁。 */
    static int ageOn(LocalDate birthMonth, LocalDate date) {
        long years = ChronoUnit.YEARS.between(YearMonth.from(birthMonth), YearMonth.from(date));
        return (int) Math.max(0, years);
    }

    /**
     * 这个人能不能报这一场。年龄按场次当天算；“本人”不知道出生年月，按成年人处理，
     * 所以只在活动限定了未成年年龄上限时不能报。
     */
    private static ParticipantEligibilityReason eligibility(
            ParticipantRow row,
            SessionRow session,
            Set<UUID> enrolled
    ) {
        if (enrolled.contains(row.id())) {
            return ParticipantEligibilityReason.ALREADY_ENROLLED;
        }
        Integer minAge = session.minAge();
        Integer maxAge = session.maxAge();
        if (!isChild(row)) {
            return maxAge != null && maxAge < ADULT_AGE
                    ? ParticipantEligibilityReason.OVER_MAX_AGE
                    : ParticipantEligibilityReason.ELIGIBLE;
        }
        int age = ageOn(row.birthMonth(), dateOf(session.startsAt()));
        if (minAge != null && age < minAge) {
            return ParticipantEligibilityReason.UNDER_MIN_AGE;
        }
        if (maxAge != null && age > maxAge) {
            return ParticipantEligibilityReason.OVER_MAX_AGE;
        }
        return ParticipantEligibilityReason.ELIGIBLE;
    }

    private static boolean isListed(SessionRow session) {
        return "PUBLISHED".equals(session.activityStatus())
                && "ACTIVE".equals(session.sessionStatus());
    }

    private static boolean isChild(ParticipantRow row) {
        return ParticipantRelation.CHILD.name().equals(row.relation());
    }

    private static OffsetDateTime sessionEnd(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return endsAt == null ? startsAt : endsAt;
    }

    private static LocalDate dateOf(OffsetDateTime time) {
        return time.atZoneSameInstant(ActivityCatalogReadService.ACTIVITY_ZONE).toLocalDate();
    }

    // ---- 转换 ----

    private static ParticipantView toParticipant(
            ParticipantRow row,
            LocalDate today,
            SessionRow session,
            Set<UUID> enrolled
    ) {
        boolean child = isChild(row);
        ParticipantEligibilityView eligibility = null;
        if (session != null) {
            ParticipantEligibilityReason reason = eligibility(row, session, enrolled);
            eligibility = new ParticipantEligibilityView(
                    reason == ParticipantEligibilityReason.ELIGIBLE,
                    reason,
                    child ? ageOn(row.birthMonth(), dateOf(session.startsAt())) : null);
        }
        return new ParticipantView(
                row.id(),
                ParticipantRelation.valueOf(row.relation()),
                row.name(),
                child ? YearMonth.from(row.birthMonth()).toString() : null,
                child ? ageOn(row.birthMonth(), today) : null,
                row.photoUseAuthorized(),
                eligibility);
    }

    private static EnrollmentView toEnrollment(
            EnrollmentRow row,
            List<TicketRow> ticketRows,
            OffsetDateTime now
    ) {
        boolean ended = !sessionEnd(row.startsAt(), row.endsAt()).isAfter(now);
        EnrollmentStatus status;
        if ("CANCELLED".equals(row.status())) {
            status = EnrollmentStatus.CANCELLED;
        } else {
            status = ended ? EnrollmentStatus.FINISHED : EnrollmentStatus.UPCOMING;
        }
        List<TicketView> tickets = ticketRows.stream()
                .map(ticket -> new TicketView(
                        ticket.ticketId(),
                        ticket.participantId(),
                        ticket.participantName(),
                        ParticipantRelation.valueOf(ticket.participantRelation()),
                        ticket.participantAge(),
                        ticket.ticketCode(),
                        ticketStatus(ticket.status(), ended),
                        ticket.checkedInAt(),
                        ticket.cancelledAt()))
                .toList();
        boolean cancellable = status == EnrollmentStatus.UPCOMING
                && now.isBefore(row.startsAt())
                && tickets.stream().anyMatch(ticket -> ticket.status() == TicketStatus.ACTIVE);
        return new EnrollmentView(
                row.enrollmentId(),
                status,
                new EnrollmentActivityView(
                        row.activityId(),
                        ActivityType.valueOf(row.activityType()),
                        row.title(),
                        ActivityCatalogReadService.secureMedia(row.coverUrl(), row.title()),
                        row.cityCode().trim(),
                        row.organizerName()),
                new EnrollmentSessionView(
                        row.sessionId(),
                        row.sessionLabel() == null || row.sessionLabel().isBlank()
                                ? null
                                : row.sessionLabel().trim(),
                        row.startsAt(),
                        row.endsAt(),
                        row.checkInOpensAt()),
                new ActivityLocationView(
                        row.venueName(), row.venueAddress(), row.latitude(), row.longitude()),
                row.createdAt(),
                row.startsAt(),
                cancellable,
                tickets);
    }

    /** 没签到、场次也结束了的凭证算过期；这个状态按时间算，不落库。 */
    private static TicketStatus ticketStatus(String stored, boolean sessionEnded) {
        TicketStatus status = TicketStatus.valueOf(stored);
        return status == TicketStatus.ACTIVE && sessionEnded ? TicketStatus.EXPIRED : status;
    }

    private static CheckinView toCheckin(CheckinRow row, OffsetDateTime checkedInAt, boolean repeated) {
        return new CheckinView(
                row.ticketCode(),
                row.participantName(),
                row.activityTitle(),
                row.sessionLabel() == null || row.sessionLabel().isBlank() ? null : row.sessionLabel().trim(),
                row.startsAt(),
                checkedInAt,
                repeated);
    }

    // ---- 校验与工具 ----

    private ParticipantRow requireParticipant(UUID accountId, UUID participantId) {
        if (participantId == null) {
            throw new IllegalArgumentException("participant id is required");
        }
        ParticipantRow row = mapper.findParticipant(accountId, participantId);
        if (row == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        return row;
    }

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
    }

    private static String requireName(String text) {
        String name = text == null ? "" : text.trim();
        if (name.isEmpty()
                || name.length() > MAX_NAME_LENGTH
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("participant name is invalid");
        }
        return name;
    }

    private static String selfName(String accountName) {
        String name = accountName == null ? "" : accountName.trim();
        if (name.isEmpty() || name.chars().anyMatch(Character::isISOControl)) {
            return DEFAULT_SELF_NAME;
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private static LocalDate parseBirthMonth(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("birth month is required");
        }
        try {
            return YearMonth.parse(text.trim()).atDay(1);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("birth month is invalid");
        }
    }

    private static String hash(UUID activityId, UUID sessionId, List<UUID> participantIds) {
        String text = activityId + "|" + sessionId + "|"
                + participantIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private LocalDate today() {
        return dateOf(now());
    }
}

enum ParticipantRelation {
    SELF,
    CHILD
}

enum ParticipantEligibilityReason {
    ELIGIBLE,
    UNDER_MIN_AGE,
    OVER_MAX_AGE,
    ALREADY_ENROLLED
}

/** 报名单对用户的三种状态，也是“我的报名”的三个页签。 */
enum EnrollmentStatus {
    UPCOMING,
    FINISHED,
    CANCELLED;

    static EnrollmentStatus parse(String code) {
        if (code == null || code.isBlank()) {
            return UPCOMING;
        }
        try {
            return valueOf(code.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("enrollment status is invalid");
        }
    }
}

enum TicketStatus {
    ACTIVE,
    CHECKED_IN,
    EXPIRED,
    CANCELLED
}

record EnrollCommand(
        UUID activityId,
        UUID sessionId,
        List<UUID> participantIds,
        boolean noticeAgreed
) {
}

record EnrollmentResult(EnrollmentView enrollment, boolean created) {
}

record ParticipantListView(List<ParticipantView> items) {
}

record ParticipantView(
        UUID participantId,
        ParticipantRelation relation,
        String name,
        String birthMonth,
        Integer age,
        boolean photoUseAuthorized,
        ParticipantEligibilityView eligibility
) {
}

record ParticipantEligibilityView(
        boolean eligible,
        ParticipantEligibilityReason reason,
        Integer ageAtSession
) {
}

record EnrollmentPageView(
        int page,
        int size,
        long total,
        boolean hasMore,
        List<EnrollmentView> items
) {
}

record EnrollmentView(
        UUID enrollmentId,
        EnrollmentStatus status,
        EnrollmentActivityView activity,
        EnrollmentSessionView session,
        ActivityLocationView location,
        OffsetDateTime createdAt,
        OffsetDateTime cancellableUntil,
        boolean cancellable,
        List<TicketView> tickets
) {
}

record EnrollmentActivityView(
        UUID activityId,
        ActivityType type,
        String title,
        ActivityMediaView coverImage,
        String cityCode,
        String organizerName
) {
}

record EnrollmentSessionView(
        UUID sessionId,
        String label,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime checkInOpensAt
) {
}

record TicketView(
        UUID ticketId,
        UUID participantId,
        String participantName,
        ParticipantRelation relation,
        Integer age,
        String ticketCode,
        TicketStatus status,
        OffsetDateTime checkedInAt,
        OffsetDateTime cancelledAt
) {
}

record CheckinView(
        String ticketCode,
        String participantName,
        String activityTitle,
        String sessionLabel,
        OffsetDateTime sessionStartsAt,
        OffsetDateTime checkedInAt,
        boolean repeated
) {
}
