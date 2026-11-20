package com.lowaltitude.inspection.activity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
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
import com.lowaltitude.inspection.activity.application.ActivityPaymentPort.OpenPayment;
import com.lowaltitude.inspection.activity.application.ActivityPaymentPort.OpenedPayment;
import com.lowaltitude.inspection.activity.application.ActivityPaymentPort.RefundProgress;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.CheckinRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.EnrollmentKeyRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.EnrollmentRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.PaidEnrollmentRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.ParticipantRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.SessionRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.TicketRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 活动报名：参与人、报名、凭证、取消、核销（契约 contracts/openapi-activity.yaml 1.2.0）。
 *
 * <p>规则（用户 2026-11-17 确认“按原型先做”）：
 * 未成年人由监护人账号添加为“子女”后代为报名；报名在场次开始前可以取消；候补、团队报名都不在这一步。
 *
 * <p>收费报名（E09，docs/redesign/03-App实现任务计划.md §20.1）：按人收费、单价大于 0、主办机构能在线收款的活动。
 * 提交时就占名额、在同一个事务里建交易订单，报名单和凭证都是“待付款”（凭证不给二维码、不能核销）；
 * 付款截止 15 分钟且不晚于报名截止。付款只认交易模块转来的渠道结果：到账后凭证生效；超时或取消关闭后名额释放；
 * 关闭之后才到账的，场次没开始、名额够、这些人没另报时恢复，否则交易模块整笔退回。
 * 已付款的在场次开始前可以取消其中几位，每位按报名时的单价原路退款（提交即退，不用主办方确认），已签到的不能退。
 *
 * <p>名额只在锁住场次那一行之后增减。同时动交易订单和报名单的路径一律先锁交易订单、再锁场次和报名单。
 * 直接依赖 mapper 接口，测试里用内存实现替换。
 */
public class ActivityEnrollmentService {

    /** 收费报名的付款时限（设计假设 3）。 */
    static final Duration PAY_WINDOW = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(ActivityEnrollmentService.class);
    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");

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
    private final ActivityPaymentPort payments;
    private final TransactionOperations tx;
    private final ActivityEnrollmentNotifier notifier;

    /** 没有接交易模块：只能免费报名。 */
    ActivityEnrollmentService(
            ActivityEnrollmentMapper mapper,
            Clock clock,
            Supplier<String> ticketCodes
    ) {
        this(mapper, clock, ticketCodes, ActivityPaymentPort.unavailable(), TransactionOperations.withoutTransaction());
    }

    /**
     * @param tx 取消报名时用：待付款的要先在事务外关交易订单（要调渠道），其余的在一个事务里取消
     */
    ActivityEnrollmentService(
            ActivityEnrollmentMapper mapper,
            Clock clock,
            Supplier<String> ticketCodes,
            ActivityPaymentPort payments,
            TransactionOperations tx
    ) {
        this(mapper, clock, ticketCodes, payments, tx, ActivityEnrollmentNotifier.none());
    }

    /** @param notifier 报名成功时告诉报名的账号（R6-5） */
    ActivityEnrollmentService(
            ActivityEnrollmentMapper mapper,
            Clock clock,
            Supplier<String> ticketCodes,
            ActivityPaymentPort payments,
            TransactionOperations tx,
            ActivityEnrollmentNotifier notifier
    ) {
        this.mapper = mapper;
        this.clock = clock;
        this.ticketCodes = ticketCodes;
        this.payments = payments;
        this.tx = tx;
        this.notifier = notifier;
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
        boolean paid = isPaid(session);
        if (!paid && !ActivityPriceMode.FREE.name().equals(session.priceMode())) {
            throw new ActivityEnrollmentException(Error.ENROLLMENT_UNSUPPORTED);
        }
        // 主办方收不了款时，换人、换场次都没有用，所以最先判断。
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

        UUID enrollmentId;
        String ticketStatus;
        if (paid) {
            enrollmentId = enrollPaid(accountId, idempotencyKey, requestHash, session, participants.size(),
                    command.expectedAmountMinor(), now);
            ticketStatus = TicketStatus.PENDING_PAYMENT.name();
        } else {
            enrollmentId = mapper.insertEnrollment(
                    accountId,
                    session.activityId(),
                    session.sessionId(),
                    idempotencyKey,
                    requestHash,
                    now);
            ticketStatus = TicketStatus.ACTIVE.name();
        }
        for (ParticipantRow row : participants) {
            mapper.insertTicket(
                    enrollmentId,
                    session.sessionId(),
                    row.id(),
                    row.name(),
                    row.relation(),
                    isChild(row) ? ageOn(row.birthMonth(), sessionDate) : null,
                    ticketCodes.get(),
                    ticketStatus,
                    now);
        }
        mapper.insertEvent(enrollmentId, null, "ENROLLED", accountId, now);
        mapper.addEnrolled(session.sessionId(), participants.size(), now);
        if (!paid) {
            // 免费报名提交即成功；收费报名等付款到账（onPaymentConfirmed）再说。
            notifyEnrolled(accountId, enrollmentId, session, participants.size(), false, now);
        }
        return new EnrollmentResult(readEnrollment(accountId, enrollmentId), true);
    }

    /**
     * 收费报名：核对页面带来的应付金额，在同一个事务里先建交易订单（业务单 ID 就是报名单 ID），再写待付款的报名单。
     * 付款截止是 15 分钟后，且不晚于报名截止（报名截止不晚于场次开始）。返回报名单 ID。
     */
    private UUID enrollPaid(
            UUID accountId,
            UUID idempotencyKey,
            String requestHash,
            SessionRow session,
            int people,
            Long expectedAmountMinor,
            OffsetDateTime now
    ) {
        if (expectedAmountMinor == null) {
            throw new IllegalArgumentException("expected amount is required for a paid activity");
        }
        long unit = session.priceAmountMinor();
        long amount = Math.multiplyExact(unit, people);
        if (expectedAmountMinor != amount) {
            throw new ActivityEnrollmentException(Error.AMOUNT_CHANGED);
        }
        OffsetDateTime payBefore = now.plus(PAY_WINDOW);
        if (session.enrollmentClosesAt().isBefore(payBefore)) {
            payBefore = session.enrollmentClosesAt();
        }
        UUID enrollmentId = UUID.randomUUID();
        OpenedPayment opened = payments.open(new OpenPayment(
                enrollmentId,
                accountId,
                session.organizerId(),
                session.cityCode().trim(),
                session.activityId(),
                session.sessionId(),
                session.title(),
                sessionName(session),
                session.coverUrl(),
                unit,
                people,
                payBefore.toInstant()));
        mapper.insertPaidEnrollment(
                enrollmentId,
                accountId,
                session.activityId(),
                session.sessionId(),
                idempotencyKey,
                requestHash,
                unit,
                amount,
                opened.tradeOrderId(),
                OffsetDateTime.ofInstant(opened.payBefore(), clock.getZone()),
                now);
        return enrollmentId;
    }

    /** 订单上显示的场次：北京时间的日期和开始时间，有场次名时跟在后面。 */
    private static String sessionName(SessionRow session) {
        String time = SESSION_TIME.format(session.startsAt().atZoneSameInstant(ActivityCatalogReadService.ACTIVITY_ZONE));
        String label = session.label() == null ? "" : session.label().trim();
        return label.isEmpty() ? time : time + " " + label;
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
        List<TicketRow> tickets = mapper.findTickets(List.of(enrollmentId));
        return toEnrollment(row, tickets, refundsOf(tickets), now());
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
        Map<UUID, RefundProgress> refunds = refundsOf(tickets.values().stream().flatMap(List::stream).toList());
        List<EnrollmentView> items = rows.stream()
                .map(row -> toEnrollment(
                        row, tickets.getOrDefault(row.enrollmentId(), List.of()), refunds, now))
                .toList();
        return new EnrollmentPageView(page, size, total, (long) page * size < total, items);
    }

    /**
     * 取消报名。ticketIds 为空表示取消这张报名单里所有还没用的凭证；场次开始后不能取消。
     * 已经取消的再取消一次没有变化。
     *
     * <p>收费报名（E09）：待付款的整张取消——在事务外关交易订单（发起过支付的先向渠道查单、关单），
     * 关闭后名额由 {@link #onPaymentClosed} 释放；查单发现已经付了款时，返回已付款的报名单，什么都不取消。
     * 已付款的先锁交易订单，再按凭证取消，这次取消的几位按报名时的单价一起退一笔款，提交后交给渠道。
     */
    public EnrollmentView cancel(UUID accountId, UUID enrollmentId, List<UUID> ticketIds) {
        requireAccount(accountId);
        if (enrollmentId == null
                || (ticketIds != null && ticketIds.stream().anyMatch(Objects::isNull))) {
            throw new IllegalArgumentException("cancellation is invalid");
        }
        EnrollmentRow row = tx.execute(status -> mapper.findEnrollment(accountId, enrollmentId));
        if (row == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        if (EnrollmentRecord.PENDING_PAYMENT.equals(row.status())) {
            cancelUnpaid(accountId, row, ticketIds);
            return tx.execute(status -> readEnrollment(accountId, enrollmentId));
        }
        return tx.execute(status -> cancelTickets(accountId, enrollmentId, ticketIds));
    }

    /** 待付款的报名单只能整张取消：交易订单是一整笔，没法只关其中几位。 */
    private void cancelUnpaid(UUID accountId, EnrollmentRow row, List<UUID> ticketIds) {
        if (ticketIds != null && !ticketIds.isEmpty()) {
            Set<UUID> all = tx.execute(status -> mapper.findTickets(List.of(row.enrollmentId())).stream()
                    .map(TicketRow::ticketId)
                    .collect(Collectors.toSet()));
            if (!all.containsAll(ticketIds)) {
                throw new IllegalArgumentException("ticket does not belong to the enrollment");
            }
            if (!Set.copyOf(ticketIds).equals(all)) {
                throw new ActivityEnrollmentException(Error.CANCEL_NOT_ALLOWED);
            }
        }
        payments.cancelUnpaid(accountId, row.tradeOrderId());
    }

    /** 在事务里取消还有效的凭证；收费报名先锁交易订单，取消后按凭证退款。 */
    private EnrollmentView cancelTickets(UUID accountId, UUID enrollmentId, List<UUID> ticketIds) {
        EnrollmentRow row = mapper.findEnrollment(accountId, enrollmentId);
        if (row == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        boolean paid = row.tradeOrderId() != null;
        if (paid) {
            payments.lockOrder(row.tradeOrderId());
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
        List<UUID> cancelled = new ArrayList<>();
        for (TicketRow ticket : active) {
            if (mapper.cancelTicket(enrollmentId, ticket.ticketId(), now) > 0) {
                mapper.insertEvent(enrollmentId, ticket.ticketId(), "TICKET_CANCELLED", accountId, now);
                cancelled.add(ticket.ticketId());
            }
        }
        if (!cancelled.isEmpty()) {
            mapper.addEnrolled(session.sessionId(), -cancelled.size(), now);
        }
        if (paid && !cancelled.isEmpty()) {
            refundCancelled(accountId, row, cancelled, now);
        }
        if (mapper.cancelEnrollmentIfEmpty(enrollmentId, now) > 0) {
            mapper.insertEvent(enrollmentId, null, "ENROLLMENT_CANCELLED", accountId, now);
        }
        return readEnrollment(accountId, enrollmentId);
    }

    /**
     * 这次取消的几位一起退一笔：每位按报名时的单价。幂等键由这几张凭证算出——同一张凭证只能取消一次，
     * 所以同一笔退款不会建两次。
     */
    private void refundCancelled(UUID accountId, EnrollmentRow row, List<UUID> cancelled, OffsetDateTime now) {
        long amount = Math.multiplyExact(row.unitAmountMinor(), cancelled.size());
        String key = "activity-refund:" + cancelled.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        UUID refundId = payments.requestRefund(
                row.tradeOrderId(),
                amount,
                "取消活动报名 " + cancelled.size() + " 人",
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                accountId);
        mapper.setTicketRefund(row.enrollmentId(), cancelled, refundId, now);
        mapper.insertEvent(row.enrollmentId(), null, "REFUND_REQUESTED", accountId, now);
    }

    // ---- 收费报名的付款结果（E09） ----

    /**
     * 交易订单付款到账（交易模块在同一个事务里调用，交易订单已锁）。待付款的报名单确认、凭证生效；
     * 已经确认过的直接收下；没付款就关闭了的，场次没开始、名额够、这些人在这一场没有另报时恢复，否则收不下。
     *
     * @return false 表示收不下，交易模块随即整笔原路退款，报名单保持“已关闭”
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onPaymentConfirmed(UUID tradeOrderId, UUID enrollmentId) {
        PaidEnrollmentRow found = mapper.findPaidEnrollment(enrollmentId, tradeOrderId);
        if (found == null) {
            log.error("trade order {} was paid for an unknown enrollment {}", tradeOrderId, enrollmentId);
            return false;
        }
        SessionRow session = mapper.lockSession(found.sessionId());
        PaidEnrollmentRow row = mapper.lockPaidEnrollment(enrollmentId, tradeOrderId);
        OffsetDateTime now = now();
        return switch (row.status()) {
            case EnrollmentRecord.PENDING_PAYMENT -> {
                mapper.confirmPayment(enrollmentId, now);
                int people = mapper.activatePendingTickets(enrollmentId, now);
                mapper.insertEvent(enrollmentId, null, "PAYMENT_CONFIRMED", null, now);
                notifyEnrolled(row.accountId(), enrollmentId, session, people, true, now);
                yield true;
            }
            case EnrollmentRecord.CLOSED -> restore(row, session, now);
            // 已经按付款处理过（之后可能又按凭证取消了）。
            default -> true;
        };
    }

    private boolean restore(PaidEnrollmentRow row, SessionRow session, OffsetDateTime now) {
        long closed = mapper.findTickets(List.of(row.enrollmentId())).stream()
                .filter(ticket -> "CANCELLED".equals(ticket.status()))
                .filter(ticket -> TicketCancelCause.PAYMENT_CLOSED.name().equals(ticket.cancelCause()))
                .count();
        if (closed == 0
                || !isListed(session)
                || !now.isBefore(session.startsAt())
                || session.enrolledCount() + closed > session.capacity()
                || mapper.countOtherActiveTickets(row.enrollmentId(), row.sessionId()) > 0) {
            log.warn("enrollment {} was paid after it closed and cannot be restored; it will be refunded",
                    row.enrollmentId());
            return false;
        }
        int restored = mapper.restoreClosedTickets(row.enrollmentId(), now);
        mapper.restoreClosed(row.enrollmentId(), now);
        mapper.addEnrolled(row.sessionId(), restored, now);
        mapper.insertEvent(row.enrollmentId(), null, "PAYMENT_RESTORED", null, now);
        notifyEnrolled(row.accountId(), row.enrollmentId(), session, restored, true, now);
        return true;
    }

    /** 报名成功（R6-5）：只给活动名、开始时间和人数。通知是附带的，出错只记日志，不让报名或入账失败。 */
    private void notifyEnrolled(UUID accountId, UUID enrollmentId, SessionRow session, int people, boolean paid,
                                OffsetDateTime now) {
        try {
            notifier.enrolled(new ActivityEnrollmentNotifier.Enrolled(
                    accountId, enrollmentId, session.title(), session.startsAt(), people, paid, now.toInstant()));
        } catch (RuntimeException exception) {
            log.error("telling account {} that enrollment {} succeeded failed", accountId, enrollmentId, exception);
        }
    }

    /**
     * 交易订单没付款就关闭了（超时或取消；交易模块在同一个事务里调用，交易订单已锁）：
     * 待付款的报名单关闭、凭证作废、名额释放。别的状态不动。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentClosed(UUID tradeOrderId, UUID enrollmentId) {
        PaidEnrollmentRow found = mapper.findPaidEnrollment(enrollmentId, tradeOrderId);
        if (found == null) {
            log.error("trade order {} closed for an unknown enrollment {}", tradeOrderId, enrollmentId);
            return;
        }
        SessionRow session = mapper.lockSession(found.sessionId());
        PaidEnrollmentRow row = mapper.lockPaidEnrollment(enrollmentId, tradeOrderId);
        if (!EnrollmentRecord.PENDING_PAYMENT.equals(row.status())) {
            return;
        }
        OffsetDateTime now = now();
        int released = mapper.cancelPendingTickets(enrollmentId, now);
        if (released > 0) {
            mapper.addEnrolled(session.sessionId(), -released, now);
        }
        mapper.closeUnpaid(enrollmentId, now);
        mapper.insertEvent(enrollmentId, null, "PAYMENT_CLOSED", null, now);
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
        // 取消了的、还没付款的（E09）都不能核销。
        if ("CANCELLED".equals(row.status()) || TicketStatus.PENDING_PAYMENT.name().equals(row.status())) {
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

    /** 能在线付款的活动：按人收费、单价大于 0。主办机构能不能收款另外判断。 */
    private static boolean isPaid(SessionRow session) {
        return ActivityPriceMode.PER_PERSON.name().equals(session.priceMode())
                && session.priceAmountMinor() != null
                && session.priceAmountMinor() > 0;
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

    /** 凭证上的退款进度：交易模块记着；拿不到时按“已申请”显示。 */
    private Map<UUID, RefundProgress> refundsOf(List<TicketRow> tickets) {
        List<UUID> refundIds = tickets.stream()
                .map(TicketRow::refundId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return refundIds.isEmpty() ? Map.of() : payments.refundStates(refundIds);
    }

    private static EnrollmentView toEnrollment(
            EnrollmentRow row,
            List<TicketRow> ticketRows,
            Map<UUID, RefundProgress> refunds,
            OffsetDateTime now
    ) {
        boolean ended = !sessionEnd(row.startsAt(), row.endsAt()).isAfter(now);
        EnrollmentStatus status;
        if (EnrollmentRecord.CANCELLED.equals(row.status()) || EnrollmentRecord.CLOSED.equals(row.status())) {
            status = EnrollmentStatus.CANCELLED;
        } else {
            status = ended ? EnrollmentStatus.FINISHED : EnrollmentStatus.UPCOMING;
        }
        List<TicketView> tickets = ticketRows.stream()
                .map(ticket -> toTicket(ticket, ended, row.unitAmountMinor(), refunds))
                .toList();
        // 待付款的也能取消（关交易订单）；已付款的按凭证取消并退款。
        boolean cancellable = status == EnrollmentStatus.UPCOMING
                && now.isBefore(row.startsAt())
                && tickets.stream().anyMatch(ticket -> ticket.status() == TicketStatus.ACTIVE
                        || ticket.status() == TicketStatus.PENDING_PAYMENT);
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
                tickets,
                toPayment(row));
    }

    private static EnrollmentPaymentView toPayment(EnrollmentRow row) {
        if (row.tradeOrderId() == null) {
            return new EnrollmentPaymentView(EnrollmentPaymentStatus.FREE, null, null, null, null);
        }
        EnrollmentPaymentStatus status = switch (row.status()) {
            case EnrollmentRecord.PENDING_PAYMENT -> EnrollmentPaymentStatus.PENDING_PAYMENT;
            case EnrollmentRecord.CLOSED -> EnrollmentPaymentStatus.CLOSED;
            // 已报名，或付过款之后全部取消了。
            default -> EnrollmentPaymentStatus.PAID;
        };
        return new EnrollmentPaymentView(
                status, row.tradeOrderId(), row.amountMinor(), row.unitAmountMinor(), row.payBefore());
    }

    /**
     * 待付款的凭证、没付款就作废的凭证不给凭证号（凭证号就是二维码的内容，付款前不能拿去签到）。
     * 收费报名取消后的凭证带上这一位的退款：金额是报名时的单价，进度按这次取消的那笔退款。
     */
    private static TicketView toTicket(
            TicketRow ticket,
            boolean sessionEnded,
            Long unitAmountMinor,
            Map<UUID, RefundProgress> refunds
    ) {
        boolean neverPaid = TicketStatus.PENDING_PAYMENT.name().equals(ticket.status())
                || TicketCancelCause.PAYMENT_CLOSED.name().equals(ticket.cancelCause());
        TicketRefundView refund = null;
        if (ticket.refundId() != null && unitAmountMinor != null) {
            RefundProgress progress = refunds.get(ticket.refundId());
            refund = new TicketRefundView(
                    progress == null ? TicketRefundStatus.REQUESTED : TicketRefundStatus.valueOf(progress.status()),
                    unitAmountMinor);
        }
        return new TicketView(
                ticket.ticketId(),
                ticket.participantId(),
                ticket.participantName(),
                ParticipantRelation.valueOf(ticket.participantRelation()),
                ticket.participantAge(),
                neverPaid ? null : ticket.ticketCode(),
                ticketStatus(ticket.status(), sessionEnded),
                ticket.checkedInAt(),
                ticket.cancelledAt(),
                refund);
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

/** 凭证状态（契约 TicketStatus）。待付款（E09）：凭证号已生成，付款前不给、不能核销。 */
enum TicketStatus {
    PENDING_PAYMENT,
    ACTIVE,
    CHECKED_IN,
    EXPIRED,
    CANCELLED
}

/** 凭证的取消原因（activity_enrollment_ticket.cancel_cause）。 */
enum TicketCancelCause {
    BUYER,
    PAYMENT_CLOSED
}

/** 报名单落库的状态（activity_enrollment.status）。 */
final class EnrollmentRecord {
    static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    static final String CANCELLED = "CANCELLED";
    static final String CLOSED = "CLOSED";

    private EnrollmentRecord() {
    }
}

/** 报名单的付款状态（契约 EnrollmentPaymentStatus）。 */
enum EnrollmentPaymentStatus {
    FREE,
    PENDING_PAYMENT,
    PAID,
    CLOSED
}

/** 凭证的退款进度（契约 TicketRefundStatus），和交易模块的退款状态一样。 */
enum TicketRefundStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    CLOSED,
    ABNORMAL
}

/**
 * @param expectedAmountMinor 页面看到的应付金额（分）；收费活动必填，和服务端按当前单价算的不一致时拒绝
 */
record EnrollCommand(
        UUID activityId,
        UUID sessionId,
        List<UUID> participantIds,
        boolean noticeAgreed,
        Long expectedAmountMinor
) {

    /** 免费活动不用带金额。 */
    EnrollCommand(UUID activityId, UUID sessionId, List<UUID> participantIds, boolean noticeAgreed) {
        this(activityId, sessionId, participantIds, noticeAgreed, null);
    }
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
        List<TicketView> tickets,
        EnrollmentPaymentView payment
) {
}

/**
 * 报名单的付款（E09）。免费报名只有 status = FREE，其余为 null；收费报名带交易订单（去收银台用）、
 * 总金额、单价和付款截止时间。
 */
record EnrollmentPaymentView(
        EnrollmentPaymentStatus status,
        UUID tradeOrderId,
        Long amountMinor,
        Long unitAmountMinor,
        OffsetDateTime payBefore
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
        OffsetDateTime cancelledAt,
        TicketRefundView refund
) {
}

/** 收费报名取消后这一位的退款（E09）：金额是报名时的单价。 */
record TicketRefundView(TicketRefundStatus status, long amountMinor) {
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
