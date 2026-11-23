package com.lowaltitude.inspection.activity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.EnrollmentKeyRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.ParticipantRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.SessionRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper.MemberRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper.WaitlistLockRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper.WaitlistRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 候补（S5-3，docs/redesign/08-后续开发计划.md §5“候补、团队报名、改期”，契约 contracts/openapi-activity.yaml 1.4.0）。
 *
 * <p>规则：满员且活动允许候补的场次（ActivityEnrollmentStatus.FULL_WAITLIST）可以排队，一条候补带 1–10 位参与人，
 * 候补期间不收费，随时可以放弃。名额释放时按加入先后转正（{@link ActivityWaitlistQueue}），转正后名额保留 15 分钟、
 * 且不晚于场次开始：收费的在这段时间里去付款（这时才建报名单和交易订单，付款截止就是保留截止），
 * 免费的确认报名（提交即报名成功）。超时没确认的、放弃的把名额放回给下一位；场次开始时还在排队的候补结束。
 * 团队报名不支持候补。
 *
 * <p>加锁顺序和报名一样：先锁场次，再锁候补；有交易订单的路径先锁交易订单（这里只在建新订单时用到交易模块）。
 */
public class ActivityWaitlistService {

    private static final Logger log = LoggerFactory.getLogger(ActivityWaitlistService.class);
    private static final int MAX_PEOPLE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int EXPIRE_BATCH = 100;

    private final ActivityEnrollmentMapper enrollments;
    private final ActivityWaitlistMapper mapper;
    private final ActivityEnrollmentService enrollmentService;
    private final ActivitySeatsReleased queue;
    private final ActivityPaymentPort payments;
    private final TransactionOperations tx;
    private final Clock clock;

    public ActivityWaitlistService(
            ActivityEnrollmentMapper enrollments,
            ActivityWaitlistMapper mapper,
            ActivityEnrollmentService enrollmentService,
            ActivitySeatsReleased queue,
            ActivityPaymentPort payments,
            TransactionOperations tx,
            Clock clock
    ) {
        this.enrollments = enrollments;
        this.mapper = mapper;
        this.enrollmentService = enrollmentService;
        this.queue = queue;
        this.payments = payments;
        this.tx = tx;
        this.clock = clock;
    }

    /** 加入候补。同一个幂等键重复提交返回第一次的结果（created 为 false）。 */
    public WaitlistResult join(UUID accountId, UUID idempotencyKey, JoinWaitlistCommand command) {
        requireAccount(accountId);
        if (idempotencyKey == null
                || command == null
                || command.activityId() == null
                || command.sessionId() == null
                || command.participantIds() == null
                || command.participantIds().isEmpty()
                || command.participantIds().size() > MAX_PEOPLE
                || command.participantIds().stream().anyMatch(Objects::isNull)
                || !command.noticeAgreed()) {
            throw new IllegalArgumentException("waitlist is invalid");
        }
        List<UUID> participantIds = command.participantIds().stream().distinct().sorted().toList();
        if (participantIds.size() != command.participantIds().size()) {
            throw new IllegalArgumentException("participants are duplicated");
        }
        String requestHash = hash("waitlist|" + command.activityId() + "|" + command.sessionId() + "|"
                + participantIds.stream().map(UUID::toString).collect(Collectors.joining(",")));
        return tx.execute(status -> doJoin(accountId, idempotencyKey, requestHash, command, participantIds));
    }

    private WaitlistResult doJoin(
            UUID accountId,
            UUID idempotencyKey,
            String requestHash,
            JoinWaitlistCommand command,
            List<UUID> participantIds
    ) {
        SessionRow session = enrollments.lockSession(command.sessionId());
        if (session == null || !session.activityId().equals(command.activityId())) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        EnrollmentKeyRow replayed = mapper.findByKey(accountId, idempotencyKey);
        if (replayed != null) {
            if (!requestHash.equals(replayed.requestHash().trim())) {
                throw new ActivityEnrollmentException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new WaitlistResult(read(accountId, replayed.id()), false);
        }
        if (!isListed(session)) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        boolean paid = isPaid(session);
        if (!paid && !ActivityPriceMode.FREE.name().equals(session.priceMode())) {
            throw new ActivityEnrollmentException(Error.ENROLLMENT_UNSUPPORTED);
        }
        if (paid && !payments.merchantCanCollect(session.organizerId())) {
            throw new ActivityEnrollmentException(Error.PAYMENT_UNAVAILABLE);
        }
        OffsetDateTime now = now();
        ActivityEnrollmentStatus status = ActivityEnrollmentStatus.ofSession(
                now,
                session.startsAt(),
                session.endsAt(),
                session.enrollmentClosesAt(),
                session.capacity(),
                session.enrolledCount(),
                session.waitlistAllowed(),
                session.saleStopped());
        // 只有满员且允许候补的场次能排队；人数超过场次总名额的永远转不了正。
        if (status != ActivityEnrollmentStatus.FULL_WAITLIST || participantIds.size() > session.capacity()) {
            throw new ActivityEnrollmentException(Error.WAITLIST_NOT_AVAILABLE);
        }
        Set<UUID> enrolled = Set.copyOf(enrollments.findEnrolledParticipantIds(session.sessionId(), accountId));
        Set<UUID> waiting = new HashSet<>(mapper.findWaitingParticipantIds(session.sessionId(), accountId));
        LocalDate sessionDate = dateOf(session.startsAt());
        List<ParticipantRow> rows = new ArrayList<>();
        for (UUID participantId : participantIds) {
            ParticipantRow row = enrollments.findParticipant(accountId, participantId);
            // 团队队员只能跟团队报名，不能单独候补。
            if (row == null || ParticipantRelation.MEMBER.name().equals(row.relation())) {
                throw new ActivityEnrollmentException(Error.NOT_FOUND);
            }
            if (enrolled.contains(row.id())) {
                throw new ActivityEnrollmentException(Error.PARTICIPANT_ALREADY_ENROLLED);
            }
            if (waiting.contains(row.id())) {
                throw new ActivityEnrollmentException(Error.WAITLIST_ALREADY_JOINED);
            }
            if (!eligibleByAge(row, session, sessionDate)) {
                throw new ActivityEnrollmentException(Error.PARTICIPANT_NOT_ELIGIBLE);
            }
            rows.add(row);
        }
        UUID waitlistId = mapper.insertWaitlist(
                accountId,
                session.activityId(),
                session.sessionId(),
                idempotencyKey,
                requestHash,
                rows.size(),
                paid ? session.priceAmountMinor() : null,
                now);
        for (ParticipantRow row : rows) {
            mapper.insertMember(
                    waitlistId,
                    row.id(),
                    row.name(),
                    row.relation(),
                    row.birthMonth() == null ? null : ActivityEnrollmentService.ageOn(row.birthMonth(), sessionDate));
        }
        return new WaitlistResult(read(accountId, waitlistId), true);
    }

    public WaitlistView read(UUID accountId, UUID waitlistId) {
        requireAccount(accountId);
        if (waitlistId == null) {
            throw new IllegalArgumentException("waitlist id is required");
        }
        WaitlistRow row = mapper.findWaitlist(accountId, waitlistId);
        if (row == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        return toView(row, mapper.findMembers(List.of(waitlistId)), now());
    }

    /** @param active true：还在排队、已转正待确认或待付款的；false：全部 */
    public WaitlistPageView list(UUID accountId, boolean active, int page, int size) {
        requireAccount(accountId);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        long total = mapper.countWaitlist(accountId, active);
        List<WaitlistRow> rows = mapper.findWaitlistPage(accountId, active, size, Math.multiplyExact(page - 1, size));
        Map<UUID, List<MemberRow>> members = rows.isEmpty()
                ? Map.of()
                : mapper.findMembers(rows.stream().map(WaitlistRow::waitlistId).toList()).stream()
                        .collect(Collectors.groupingBy(MemberRow::waitlistId));
        OffsetDateTime now = now();
        List<WaitlistView> items = rows.stream()
                .map(row -> toView(row, members.getOrDefault(row.waitlistId(), List.of()), now))
                .toList();
        return new WaitlistPageView(page, size, total, (long) page * size < total, items);
    }

    /**
     * 放弃候补。排队中的直接放弃；已转正的放弃后名额马上给下一位；已经去付款（建了待付款报名单）的，
     * 先关掉报名单和交易订单（名额在关闭时放回），再放弃——查单发现已经付了款时不放弃，返回“已报名”。
     * 已经结束的再放弃一次没有变化。
     */
    public WaitlistView abandon(UUID accountId, UUID waitlistId) {
        requireAccount(accountId);
        if (waitlistId == null) {
            throw new IllegalArgumentException("waitlist id is required");
        }
        WaitlistView current = read(accountId, waitlistId);
        boolean closedPending = false;
        if (current.enrollmentId() != null && current.status() == WaitlistStatus.OFFERED) {
            // 待付款：先关单（不能在事务里，要调渠道）。
            enrollmentService.cancel(accountId, current.enrollmentId(), null);
            WaitlistView after = read(accountId, waitlistId);
            if (after.status() == WaitlistStatus.ENROLLED) {
                return after;
            }
            closedPending = true;
        }
        boolean abandonEnrolled = closedPending;
        tx.execute(status -> {
            WaitlistLockRow found = mapper.findOwnWaitlist(accountId, waitlistId);
            if (found == null) {
                throw new ActivityEnrollmentException(Error.NOT_FOUND);
            }
            enrollments.lockSession(found.sessionId());
            WaitlistLockRow row = mapper.lockWaitlist(waitlistId);
            OffsetDateTime now = now();
            switch (row.status()) {
                case "WAITING" -> mapper.abandon(waitlistId, now);
                case "OFFERED" -> {
                    if (mapper.abandon(waitlistId, now) > 0) {
                        mapper.addEnrolled(row.sessionId(), -row.people(), now);
                        queue.released(row.sessionId(), now);
                    }
                }
                // 已经建了报名单：只有它关闭了（上面已经关单）才算放弃。
                case "ENROLLED" -> {
                    if (abandonEnrolled) {
                        mapper.abandon(waitlistId, now);
                    }
                }
                default -> {
                }
            }
            return null;
        });
        return read(accountId, waitlistId);
    }

    /**
     * 转正后确认：免费的直接报名成功；收费的建待付款报名单和交易订单，之后到收银台付款
     * （返回的 payment.tradeOrderId）。重复确认返回同一张报名单。保留时间过了的回 ACT_WAITLIST_STATE_INVALID。
     *
     * @param expectedAmountMinor 页面看到的应付金额（分），收费的必填
     */
    public WaitlistView confirm(UUID accountId, UUID waitlistId, Long expectedAmountMinor) {
        requireAccount(accountId);
        if (waitlistId == null) {
            throw new IllegalArgumentException("waitlist id is required");
        }
        tx.execute(status -> {
            WaitlistLockRow found = mapper.findOwnWaitlist(accountId, waitlistId);
            if (found == null) {
                throw new ActivityEnrollmentException(Error.NOT_FOUND);
            }
            SessionRow session = enrollments.lockSession(found.sessionId());
            WaitlistLockRow row = mapper.lockWaitlist(waitlistId);
            if ("ENROLLED".equals(row.status())) {
                return null;
            }
            OffsetDateTime now = now();
            if (!"OFFERED".equals(row.status()) || !now.isBefore(row.offerExpiresAt())) {
                throw new ActivityEnrollmentException(Error.WAITLIST_STATE_INVALID);
            }
            if (isPaid(session) && expectedAmountMinor == null) {
                throw new IllegalArgumentException("expected amount is required for a paid activity");
            }
            List<UUID> participantIds = mapper.findMembers(List.of(waitlistId)).stream()
                    .map(MemberRow::participantId)
                    .toList();
            UUID enrollmentId = enrollmentService.enrollFromWaitlist(
                    accountId, waitlistId, session, participantIds, expectedAmountMinor, row.offerExpiresAt());
            mapper.markEnrolled(waitlistId, enrollmentId, now);
            return null;
        });
        return read(accountId, waitlistId);
    }

    /**
     * 定时任务：过了保留时间还没确认的转正作废、名额给下一位；场次开始时还在排队的候补结束。
     *
     * @return 作废的转正条数
     */
    public int expire() {
        OffsetDateTime now = now();
        int expired = 0;
        for (WaitlistLockRow candidate : mapper.findExpiredOffers(now, EXPIRE_BATCH)) {
            try {
                Integer done = tx.execute(status -> {
                    enrollments.lockSession(candidate.sessionId());
                    WaitlistLockRow row = mapper.lockWaitlist(candidate.waitlistId());
                    OffsetDateTime at = now();
                    if (!"OFFERED".equals(row.status()) || mapper.expireOffer(row.waitlistId(), at) == 0) {
                        return 0;
                    }
                    mapper.addEnrolled(row.sessionId(), -row.people(), at);
                    queue.released(row.sessionId(), at);
                    return 1;
                });
                expired += done == null ? 0 : done;
            } catch (RuntimeException failure) {
                log.warn("expiring waitlist offer {} failed; will retry", candidate.waitlistId(), failure);
            }
        }
        tx.execute(status -> mapper.endStartedWaiting(now()));
        return expired;
    }

    // ---- 规则与转换 ----

    private static boolean eligibleByAge(ParticipantRow row, SessionRow session, LocalDate sessionDate) {
        Integer minAge = session.minAge();
        Integer maxAge = session.maxAge();
        if (row.birthMonth() == null) {
            return maxAge == null || maxAge >= ActivityEnrollmentService.ADULT_AGE;
        }
        int age = ActivityEnrollmentService.ageOn(row.birthMonth(), sessionDate);
        return (minAge == null || age >= minAge) && (maxAge == null || age <= maxAge);
    }

    /**
     * 存的状态 → 页面上的状态。建了报名单的看报名单：待付款读成“已转正”（带付款信息），没付款就关闭了读成“已超时”，
     * 其余是“已报名”。还在排队但场次已经开始的读成“已结束”（定时任务稍后落库）。
     */
    static WaitlistView toView(WaitlistRow row, List<MemberRow> members, OffsetDateTime now) {
        WaitlistStatus status = switch (row.status()) {
            case "WAITING" -> now.isBefore(row.startsAt()) ? WaitlistStatus.WAITING : WaitlistStatus.ENDED;
            case "OFFERED" -> now.isBefore(row.offerExpiresAt()) ? WaitlistStatus.OFFERED : WaitlistStatus.EXPIRED;
            case "ENROLLED" -> switch (row.enrollmentStatus() == null ? "" : row.enrollmentStatus()) {
                case "PENDING_PAYMENT" -> WaitlistStatus.OFFERED;
                case "CLOSED" -> WaitlistStatus.EXPIRED;
                default -> WaitlistStatus.ENROLLED;
            };
            default -> WaitlistStatus.valueOf(row.status());
        };
        boolean paid = ActivityPriceMode.PER_PERSON.name().equals(row.priceMode())
                && row.priceAmountMinor() != null
                && row.priceAmountMinor() > 0;
        Long unit = row.unitAmountMinor() != null ? row.unitAmountMinor() : paid ? row.priceAmountMinor() : null;
        OffsetDateTime holdUntil = row.payBefore() != null ? row.payBefore() : row.offerExpiresAt();
        return new WaitlistView(
                row.waitlistId(),
                status,
                status == WaitlistStatus.WAITING ? (int) row.ahead() + 1 : null,
                status == WaitlistStatus.WAITING ? (int) row.ahead() : null,
                new EnrollmentActivityView(
                        row.activityId(),
                        ActivityType.valueOf(row.activityType()),
                        row.title(),
                        ActivityCatalogReadService.secureMedia(row.coverUrl(), row.title()),
                        row.cityCode().trim(),
                        row.organizerName()),
                new WaitlistSessionView(
                        row.sessionId(),
                        row.sessionLabel() == null || row.sessionLabel().isBlank() ? null : row.sessionLabel().trim(),
                        row.startsAt(),
                        row.endsAt(),
                        row.venueName()),
                members.stream()
                        .map(member -> new WaitlistMemberView(
                                member.participantId(),
                                member.participantName(),
                                ParticipantRelation.valueOf(member.participantRelation()),
                                member.participantAge()))
                        .toList(),
                row.people(),
                unit,
                unit == null ? null : Math.multiplyExact(unit, (long) row.people()),
                status == WaitlistStatus.OFFERED ? holdUntil : null,
                row.enrollmentId(),
                row.enrollmentId() != null && "PENDING_PAYMENT".equals(row.enrollmentStatus())
                        ? row.tradeOrderId()
                        : null,
                status == WaitlistStatus.WAITING || status == WaitlistStatus.OFFERED,
                row.createdAt(),
                row.offeredAt(),
                row.endedAt());
    }

    private static boolean isPaid(SessionRow session) {
        return ActivityPriceMode.PER_PERSON.name().equals(session.priceMode())
                && session.priceAmountMinor() != null
                && session.priceAmountMinor() > 0;
    }

    private static boolean isListed(SessionRow session) {
        return "PUBLISHED".equals(session.activityStatus()) && "ACTIVE".equals(session.sessionStatus());
    }

    private static LocalDate dateOf(OffsetDateTime time) {
        return time.atZoneSameInstant(ActivityCatalogReadService.ACTIVITY_ZONE).toLocalDate();
    }

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
    }

    private static String hash(String text) {
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
}

/**
 * 候补对用户的状态（契约 WaitlistStatus）：排队中 / 已转正（待确认或待付款）/ 已报名 / 已超时（转正后没在保留时间内
 * 确认或付款）/ 已结束（活动开始前没转正）/ 已放弃。
 */
enum WaitlistStatus {
    WAITING,
    OFFERED,
    ENROLLED,
    EXPIRED,
    ENDED,
    ABANDONED
}

record JoinWaitlistCommand(UUID activityId, UUID sessionId, List<UUID> participantIds, boolean noticeAgreed) {
}

record WaitlistResult(WaitlistView waitlist, boolean created) {
}

/**
 * @param position       排队中时是第几位（同一场还在排队的里面），其余为空
 * @param ahead          排队中时前面还有几位
 * @param unitAmountMinor 收费活动的单价（分）：建了报名单之前是加入时的价格，免费为空
 * @param holdUntil      已转正时名额保留到什么时候（收费的就是付款截止）
 * @param tradeOrderId   已经去付款、还没付的交易订单（去收银台用）
 * @param abandonable    现在能不能放弃
 */
record WaitlistView(
        UUID waitlistId,
        WaitlistStatus status,
        Integer position,
        Integer ahead,
        EnrollmentActivityView activity,
        WaitlistSessionView session,
        List<WaitlistMemberView> members,
        int people,
        Long unitAmountMinor,
        Long amountMinor,
        OffsetDateTime holdUntil,
        UUID enrollmentId,
        UUID tradeOrderId,
        boolean abandonable,
        OffsetDateTime createdAt,
        OffsetDateTime offeredAt,
        OffsetDateTime endedAt
) {
}

record WaitlistSessionView(UUID sessionId, String label, OffsetDateTime startsAt, OffsetDateTime endsAt,
                           String venueName) {
}

record WaitlistMemberView(UUID participantId, String name, ParticipantRelation relation, Integer age) {
}

record WaitlistPageView(int page, int size, long total, boolean hasMore, List<WaitlistView> items) {
}
