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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityChangeMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityChangeMapper.ChangeRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityChangeMapper.EnrollmentExtrasRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityChangeMapper.ResultRow;
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
    /** 团队报名（S5-3）：领队代报 2–20 人；核对名单时最多带 40 行（含不符合条件、要移除的）。 */
    static final int MIN_TEAM_SIZE = 2;
    static final int MAX_TEAM_SIZE = 20;
    static final int MAX_TEAM_INPUT = 40;
    static final int MAX_TEAM_NAME_LENGTH = 20;
    private static final Pattern PHONE = Pattern.compile("^1[0-9]{10}$");

    private final ActivityEnrollmentMapper mapper;
    private final Clock clock;
    private final Supplier<String> ticketCodes;
    private final ActivityPaymentPort payments;
    private final TransactionOperations tx;
    private final ActivityEnrollmentNotifier notifier;
    /** 团队报名、改期、退改记录、结果记录（S5-3）；没接时这些都不可用，旧的报名照常。 */
    private final ActivityChangeMapper changes;
    /** 名额放回来时给候补转正（S5-3）。 */
    private final ActivitySeatsReleased seatsReleased;

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
        this(mapper, clock, ticketCodes, payments, tx, notifier, null, ActivitySeatsReleased.none());
    }

    /**
     * @param changes       团队报名、改期、退改记录和结果记录（S5-3）
     * @param seatsReleased 名额放回来时给候补转正（S5-3）
     */
    ActivityEnrollmentService(
            ActivityEnrollmentMapper mapper,
            Clock clock,
            Supplier<String> ticketCodes,
            ActivityPaymentPort payments,
            TransactionOperations tx,
            ActivityEnrollmentNotifier notifier,
            ActivityChangeMapper changes,
            ActivitySeatsReleased seatsReleased
    ) {
        this.changes = changes;
        this.seatsReleased = seatsReleased;
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
            if (session == null || !isEnrollable(session)) {
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

    /**
     * 个人信息导出（S5-8）：本账号保存的参与人，只读，不补“本人”。只给出参与人自己的资料（姓名、关系、出生年月、照片授权）。
     */
    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> exportParticipants(UUID accountId) {
        List<java.util.Map<String, Object>> items = new ArrayList<>();
        for (ParticipantRow row : mapper.findParticipants(accountId)) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("name", row.name());
            item.put("relation", row.relation());
            item.put("birthMonth", row.birthMonth() == null ? null : row.birthMonth().toString().substring(0, 7));
            item.put("photoUseAuthorized", row.photoUseAuthorized());
            items.add(item);
        }
        return items;
    }

    /** 还没开始的报名有几张（注销条件“没有待参加的报名”，S5-8）。 */
    @Transactional(readOnly = true)
    public long countUpcomingEnrollments(UUID accountId) {
        return mapper.countEnrollments(accountId, EnrollmentStatus.UPCOMING.name(), now());
    }

    /** 注销到期去标识（S5-8，在调用方的事务里）：参与人姓名清掉，凭证上的姓名快照改成“已注销用户”。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void anonymizeForClosure(UUID accountId) {
        mapper.anonymizeParticipants(accountId, now());
        mapper.anonymizeTicketNames(accountId);
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
        if (!isEnrollable(session)) {
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
                session.waitlistAllowed(),
                session.saleStopped());
        if (status == ActivityEnrollmentStatus.CLOSED || status == ActivityEnrollmentStatus.ENDED) {
            throw new ActivityEnrollmentException(Error.ENROLLMENT_NOT_OPEN);
        }

        Set<UUID> enrolled = Set.copyOf(
                mapper.findEnrolledParticipantIds(session.sessionId(), accountId));
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

        UUID enrollmentId = createEnrollment(accountId, idempotencyKey, requestHash, session, participants,
                command.participantIds(), command.expectedAmountMinor(), null, true, now);
        return new EnrollmentResult(readEnrollment(accountId, enrollmentId), true);
    }

    private UUID createEnrollment(
            UUID accountId,
            UUID idempotencyKey,
            String requestHash,
            SessionRow session,
            List<ParticipantRow> participants,
            Long expectedAmountMinor,
            OffsetDateTime payBefore,
            boolean takeSeats,
            OffsetDateTime now
    ) {
        return createEnrollment(accountId, idempotencyKey, requestHash, session, participants, null,
                expectedAmountMinor, payBefore, takeSeats, now);
    }

    /**
     * 建报名单和凭证（个人报名、团队报名、候补转正共用）。收费的先建交易订单，凭证待付款；免费的提交即成功。
     *
     * @param submittedOrder 页面提交参与人的顺序，交易订单上的姓名照它排（交易契约 participantNames：报名顺序）；
     *                       凭证仍按 participants（参与人 ID 排好的顺序）生成。为空时姓名也按 participants 排
     * @param payBefore 付款截止；为空时按 15 分钟且不晚于报名截止算（候补转正带上保留截止时间）
     * @param takeSeats 是否占名额：候补转正时名额已经为它占住，不再加
     */
    private UUID createEnrollment(
            UUID accountId,
            UUID idempotencyKey,
            String requestHash,
            SessionRow session,
            List<ParticipantRow> participants,
            List<UUID> submittedOrder,
            Long expectedAmountMinor,
            OffsetDateTime payBefore,
            boolean takeSeats,
            OffsetDateTime now
    ) {
        boolean paid = isPaid(session);
        LocalDate sessionDate = dateOf(session.startsAt());
        UUID enrollmentId;
        String ticketStatus;
        if (paid) {
            enrollmentId = enrollPaid(accountId, idempotencyKey, requestHash, session,
                    namesInOrder(participants, submittedOrder), expectedAmountMinor, payBefore, now);
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
                    hasBirthMonth(row) ? ageOn(row.birthMonth(), sessionDate) : null,
                    ticketCodes.get(),
                    ticketStatus,
                    now);
        }
        mapper.insertEvent(enrollmentId, null, takeSeats ? "ENROLLED" : "ENROLLED_FROM_WAITLIST", accountId, now);
        if (takeSeats) {
            mapper.addEnrolled(session.sessionId(), participants.size(), now);
        }
        if (!paid) {
            // 免费报名提交即成功；收费报名等付款到账（onPaymentConfirmed）再说。
            notifyEnrolled(accountId, enrollmentId, session, participants.size(), false, now);
        }
        return enrollmentId;
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
            List<String> participantNames,
            Long expectedAmountMinor,
            OffsetDateTime payBeforeOverride,
            OffsetDateTime now
    ) {
        int people = participantNames.size();
        if (expectedAmountMinor == null) {
            throw new IllegalArgumentException("expected amount is required for a paid activity");
        }
        long unit = session.priceAmountMinor();
        long amount = Math.multiplyExact(unit, people);
        if (expectedAmountMinor != amount) {
            throw new ActivityEnrollmentException(Error.AMOUNT_CHANGED);
        }
        OffsetDateTime payBefore = payBeforeOverride;
        if (payBefore == null) {
            payBefore = now.plus(PAY_WINDOW);
            if (session.enrollmentClosesAt().isBefore(payBefore)) {
                payBefore = session.enrollmentClosesAt();
            }
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
                payBefore.toInstant(),
                participantNames));
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
        return toEnrollment(row, tickets, refundsOf(tickets), now(),
                extrasOf(accountId, List.of(enrollmentId), tickets).get(enrollmentId));
    }

    /** 团队、改期、结果、退改记录（S5-3）；没接时都是空的。 */
    private Map<UUID, Extras> extrasOf(UUID accountId, List<UUID> enrollmentIds, List<TicketRow> tickets) {
        Map<UUID, Extras> out = new LinkedHashMap<>();
        if (changes == null || enrollmentIds.isEmpty()) {
            return out;
        }
        Map<UUID, EnrollmentExtrasRow> rows = changes.findExtras(accountId, enrollmentIds).stream()
                .collect(Collectors.toMap(EnrollmentExtrasRow::enrollmentId, row -> row));
        List<UUID> ticketIds = tickets.stream().map(TicketRow::ticketId).toList();
        Map<UUID, ResultRow> results = ticketIds.isEmpty()
                ? Map.of()
                : changes.findResults(ticketIds).stream().collect(Collectors.toMap(ResultRow::ticketId, row -> row));
        Map<UUID, List<ChangeRow>> changeRows = changes.findChanges(accountId, enrollmentIds).stream()
                .collect(Collectors.groupingBy(ChangeRow::enrollmentId));
        for (UUID enrollmentId : enrollmentIds) {
            out.put(enrollmentId, new Extras(
                    rows.get(enrollmentId), results, changeRows.getOrDefault(enrollmentId, List.of())));
        }
        return out;
    }

    private record Extras(EnrollmentExtrasRow row, Map<UUID, ResultRow> results, List<ChangeRow> changes) {
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
        List<TicketRow> allTickets = tickets.values().stream().flatMap(List::stream).toList();
        Map<UUID, RefundProgress> refunds = refundsOf(allTickets);
        Map<UUID, Extras> extras = extrasOf(
                accountId, rows.stream().map(EnrollmentRow::enrollmentId).toList(), allTickets);
        List<EnrollmentView> items = rows.stream()
                .map(row -> toEnrollment(
                        row, tickets.getOrDefault(row.enrollmentId(), List.of()), refunds, now,
                        extras.get(row.enrollmentId())))
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
        return cancel(accountId, enrollmentId, ticketIds, null);
    }

    /**
     * 同上，带页面选的申请原因（S5-3，可以不选）。已付款或免费的取消记一条退改记录（退改详情页按它显示）；
     * 待付款的整张关单不记。
     */
    public EnrollmentView cancel(UUID accountId, UUID enrollmentId, List<UUID> ticketIds, String reasonCode) {
        requireAccount(accountId);
        ChangeReason reason = ChangeReason.parse(reasonCode);
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
        return tx.execute(status -> cancelTickets(accountId, enrollmentId, ticketIds, reason));
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
    private EnrollmentView cancelTickets(UUID accountId, UUID enrollmentId, List<UUID> ticketIds, ChangeReason reason) {
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
        UUID refundId = null;
        if (paid && !cancelled.isEmpty()) {
            refundId = refundCancelled(accountId, row, cancelled, now);
        }
        if (changes != null && !cancelled.isEmpty()) {
            UUID changeId = changes.insertChange(
                    enrollmentId,
                    accountId,
                    ChangeKind.CANCEL.name(),
                    reason == null ? null : reason.name(),
                    cancelled.size(),
                    session.sessionId(),
                    null,
                    refundId,
                    refundId == null ? null : Math.multiplyExact(row.unitAmountMinor(), cancelled.size()),
                    now);
            changes.markCancelledBy(enrollmentId, cancelled, changeId, now);
        }
        if (mapper.cancelEnrollmentIfEmpty(enrollmentId, now) > 0) {
            mapper.insertEvent(enrollmentId, null, "ENROLLMENT_CANCELLED", accountId, now);
        }
        if (!cancelled.isEmpty()) {
            seatsReleased.released(session.sessionId(), now);
        }
        return readEnrollment(accountId, enrollmentId);
    }

    /**
     * 这次取消的几位一起退一笔：每位按报名时的单价。幂等键由这几张凭证算出——同一张凭证只能取消一次，
     * 所以同一笔退款不会建两次。
     */
    private UUID refundCancelled(UUID accountId, EnrollmentRow row, List<UUID> cancelled, OffsetDateTime now) {
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
        return refundId;
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
                // 运营停售之后不再恢复（等同新报名），整笔退款。
                || session.saleStopped()
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
        if (released > 0) {
            seatsReleased.released(session.sessionId(), now);
        }
    }

    // ---- 团队报名（S5-3） ----

    /**
     * 提交前核对团队名单：每一行按场次当天算年龄、看是不是已经报过这一场、名单里有没有重复，不写任何数据。
     * 已有的参与人用 participantId；现填的给姓名和出生年月，和领队名下同名同出生年月的子女或队员算同一个人。
     */
    @Transactional(readOnly = true)
    public TeamCheckView checkTeam(UUID accountId, UUID sessionId, List<TeamMemberInput> members) {
        requireAccount(accountId);
        requireChanges();
        if (sessionId == null || members == null || members.isEmpty() || members.size() > MAX_TEAM_INPUT) {
            throw new IllegalArgumentException("team roster is invalid");
        }
        SessionRow session = mapper.findSession(sessionId);
        if (session == null || !isEnrollable(session)) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        Set<UUID> enrolled = Set.copyOf(mapper.findEnrolledParticipantIds(sessionId, accountId));
        LocalDate sessionDate = dateOf(session.startsAt());
        Set<String> seen = new HashSet<>();
        List<TeamMemberCheckView> rows = new ArrayList<>();
        int eligible = 0;
        for (TeamMemberInput input : members) {
            ResolvedMember member = resolveMember(accountId, input, false, null);
            TeamMemberReason reason;
            if (!seen.add(member.identity())) {
                reason = TeamMemberReason.DUPLICATED;
            } else {
                reason = TeamMemberReason.of(eligibility(member.row(), session,
                        member.row().id() == null ? Set.of() : enrolled));
            }
            if (reason == TeamMemberReason.ELIGIBLE) {
                eligible++;
            }
            rows.add(new TeamMemberCheckView(
                    member.row().id(),
                    member.row().name(),
                    member.row().birthMonth() == null ? null : YearMonth.from(member.row().birthMonth()).toString(),
                    member.row().birthMonth() == null ? null : ageOn(member.row().birthMonth(), sessionDate),
                    reason == TeamMemberReason.ELIGIBLE,
                    reason));
        }
        return new TeamCheckView(rows, eligible);
    }

    /**
     * 团队报名：领队代报 2–20 人，一张团队报名单，一人一码；收费的领队一次付清（15 分钟内），之后可以按人退。
     * 不支持候补：名额不够时回 SESSION_FULL。现填的队员记成领队名下的“队员”（MEMBER），下次团队报名直接用。
     * 活动没打开团队报名（运营端开关）时回 ENROLLMENT_UNSUPPORTED。
     * 同一个幂等键重复提交返回第一次的结果。
     */
    @Transactional
    public EnrollmentResult enrollTeam(UUID accountId, UUID idempotencyKey, TeamEnrollCommand command) {
        requireAccount(accountId);
        requireChanges();
        if (idempotencyKey == null
                || command == null
                || command.activityId() == null
                || command.sessionId() == null
                || command.members() == null
                || command.members().size() < MIN_TEAM_SIZE
                || command.members().size() > MAX_TEAM_SIZE
                || !command.noticeAgreed()
                || !command.guardianConsent()) {
            throw new IllegalArgumentException("team enrollment is invalid");
        }
        String teamName = requireText(command.teamName(), MAX_TEAM_NAME_LENGTH, "team name");
        String leaderName = requireName(command.leaderName());
        String leaderPhone = command.leaderPhone() == null ? "" : command.leaderPhone().trim();
        if (!PHONE.matcher(leaderPhone).matches()) {
            throw new IllegalArgumentException("leader phone is invalid");
        }
        String requestHash = hashText("team|" + command.activityId() + "|" + command.sessionId() + "|" + teamName
                + "|" + leaderName + "|" + leaderPhone + "|" + command.members().stream()
                        .map(TeamMemberInput::identityText)
                        .collect(Collectors.joining(",")));

        SessionRow session = mapper.lockSession(command.sessionId());
        if (session == null || !session.activityId().equals(command.activityId())) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        EnrollmentKeyRow replayed = mapper.findEnrollmentByKey(accountId, idempotencyKey);
        if (replayed != null) {
            if (!requestHash.equals(replayed.requestHash().trim())) {
                throw new ActivityEnrollmentException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new EnrollmentResult(readEnrollment(accountId, replayed.id()), false);
        }
        if (!isEnrollable(session)) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        // 运营端关掉了团队报名（S6-02）：不能再团队报名；已有的团队报名单照常（上面的重放也照常返回）。
        if (!session.teamEnrollmentAllowed()) {
            throw new ActivityEnrollmentException(Error.ENROLLMENT_UNSUPPORTED);
        }
        boolean paid = isPaid(session);
        if (!paid && !ActivityPriceMode.FREE.name().equals(session.priceMode())) {
            throw new ActivityEnrollmentException(Error.ENROLLMENT_UNSUPPORTED);
        }
        if (paid && !payments.merchantCanCollect(session.organizerId())) {
            throw new ActivityEnrollmentException(Error.PAYMENT_UNAVAILABLE);
        }
        OffsetDateTime now = now();
        ActivityEnrollmentStatus status = sessionStatus(session, now);
        if (status == ActivityEnrollmentStatus.CLOSED || status == ActivityEnrollmentStatus.ENDED) {
            throw new ActivityEnrollmentException(Error.ENROLLMENT_NOT_OPEN);
        }
        Set<UUID> enrolled = Set.copyOf(mapper.findEnrolledParticipantIds(session.sessionId(), accountId));
        Set<UUID> seen = new HashSet<>();
        List<ParticipantRow> participants = new ArrayList<>();
        for (TeamMemberInput input : command.members()) {
            ParticipantRow row = resolveMember(accountId, input, true, now).row();
            if (!seen.add(row.id())) {
                throw new ActivityEnrollmentException(Error.PARTICIPANT_DUPLICATE);
            }
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
        UUID enrollmentId = createEnrollment(accountId, idempotencyKey, requestHash, session, participants,
                command.expectedAmountMinor(), null, true, now);
        changes.markTeam(enrollmentId, accountId);
        changes.insertTeam(enrollmentId, teamName, leaderName, leaderPhone, now);
        return new EnrollmentResult(readEnrollment(accountId, enrollmentId), true);
    }

    /**
     * 名单里的一行：给了 participantId 的读领队名下的参与人；现填的按姓名和出生年月找领队名下的子女或队员，
     * 找不到时 create 为 true 就建一个队员（只在提交时），否则返回一个还没有 ID 的临时参与人。
     */
    private ResolvedMember resolveMember(UUID accountId, TeamMemberInput input, boolean create, OffsetDateTime now) {
        if (input == null) {
            throw new IllegalArgumentException("team member is invalid");
        }
        if (input.participantId() != null) {
            ParticipantRow row = requireParticipant(accountId, input.participantId());
            return new ResolvedMember(row, "id:" + row.id());
        }
        String name = requireName(input.name());
        LocalDate birthMonth = parseBirthMonth(input.birthMonth());
        if (birthMonth.isAfter(today())) {
            throw new IllegalArgumentException("birth month is in the future");
        }
        UUID existing = changes.findParticipantByIdentity(accountId, name, birthMonth);
        if (existing == null && create) {
            existing = changes.insertMember(accountId, name, birthMonth, now);
            if (existing == null) {
                existing = changes.findParticipantByIdentity(accountId, name, birthMonth);
            }
        }
        if (existing != null) {
            ParticipantRow row = requireParticipant(accountId, existing);
            return new ResolvedMember(row, "id:" + row.id());
        }
        return new ResolvedMember(
                new ParticipantRow(null, ParticipantRelation.MEMBER.name(), name, birthMonth, false),
                "new:" + name + "|" + birthMonth);
    }

    private record ResolvedMember(ParticipantRow row, String identity) {
    }

    // ---- 改期（S5-3） ----

    /**
     * 场次开始前把整张报名单还没用的凭证改到同一活动的另一场（要能报名、名额够、这些人在那一场没有另报、年龄仍符合）。
     * 立即生效，不用主办方确认：原凭证作废，新场次一人一码重新发放，名额跟着转过去；收费的不补差、不退款
     * （同一活动同价）。已经在那一场了再提交一次没有变化。待付款、已取消的不能改；有人已签到的不能改。
     */
    public EnrollmentView reschedule(UUID accountId, UUID enrollmentId, UUID targetSessionId, String reasonCode) {
        requireAccount(accountId);
        requireChanges();
        if (enrollmentId == null || targetSessionId == null) {
            throw new IllegalArgumentException("reschedule is invalid");
        }
        ChangeReason reason = ChangeReason.parse(reasonCode);
        return tx.execute(status -> doReschedule(accountId, enrollmentId, targetSessionId, reason));
    }

    private EnrollmentView doReschedule(UUID accountId, UUID enrollmentId, UUID targetSessionId, ChangeReason reason) {
        EnrollmentRow row = mapper.findEnrollment(accountId, enrollmentId);
        if (row == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        if (row.sessionId().equals(targetSessionId)) {
            return readEnrollment(accountId, enrollmentId);
        }
        if (!EnrollmentRecord.CONFIRMED.equals(row.status())) {
            throw new ActivityEnrollmentException(Error.RESCHEDULE_NOT_ALLOWED);
        }
        // 先锁交易订单，再按 ID 顺序锁两个场次（两张报名单对调场次时也不会互相等）。
        if (row.tradeOrderId() != null) {
            payments.lockOrder(row.tradeOrderId());
        }
        SessionRow from;
        SessionRow to;
        if (row.sessionId().compareTo(targetSessionId) < 0) {
            from = mapper.lockSession(row.sessionId());
            to = mapper.lockSession(targetSessionId);
        } else {
            to = mapper.lockSession(targetSessionId);
            from = mapper.lockSession(row.sessionId());
        }
        if (to == null || !to.activityId().equals(row.activityId()) || !isListed(to)) {
            throw new ActivityEnrollmentException(Error.RESCHEDULE_NOT_ALLOWED);
        }
        OffsetDateTime now = now();
        if (!now.isBefore(from.startsAt())) {
            throw new ActivityEnrollmentException(Error.RESCHEDULE_NOT_ALLOWED);
        }
        ActivityEnrollmentStatus target = sessionStatus(to, now);
        if (target == ActivityEnrollmentStatus.FULL || target == ActivityEnrollmentStatus.FULL_WAITLIST) {
            throw new ActivityEnrollmentException(Error.SESSION_FULL);
        }
        if (target != ActivityEnrollmentStatus.OPEN) {
            throw new ActivityEnrollmentException(Error.RESCHEDULE_NOT_ALLOWED);
        }
        List<TicketRow> tickets = mapper.findTickets(List.of(enrollmentId));
        if (tickets.stream().anyMatch(ticket -> TicketStatus.CHECKED_IN.name().equals(ticket.status()))) {
            throw new ActivityEnrollmentException(Error.RESCHEDULE_NOT_ALLOWED);
        }
        List<TicketRow> active = tickets.stream()
                .filter(ticket -> TicketStatus.ACTIVE.name().equals(ticket.status()))
                .toList();
        if (active.isEmpty()) {
            throw new ActivityEnrollmentException(Error.RESCHEDULE_NOT_ALLOWED);
        }
        if (to.enrolledCount() + active.size() > to.capacity()) {
            throw new ActivityEnrollmentException(Error.SESSION_FULL);
        }
        Set<UUID> enrolledThere = Set.copyOf(mapper.findEnrolledParticipantIds(to.sessionId(), accountId));
        LocalDate newDate = dateOf(to.startsAt());
        List<Integer> ages = new ArrayList<>();
        for (TicketRow ticket : active) {
            if (enrolledThere.contains(ticket.participantId())) {
                throw new ActivityEnrollmentException(Error.PARTICIPANT_ALREADY_ENROLLED);
            }
            // 删掉了的子女读不到出生年月，按凭证上的年龄（报名那一场当天的）判断。
            ParticipantRow participant = mapper.findParticipant(accountId, ticket.participantId());
            Integer age = participant != null && hasBirthMonth(participant)
                    ? Integer.valueOf(ageOn(participant.birthMonth(), newDate))
                    : ticket.participantAge();
            if (participant != null) {
                ParticipantEligibilityReason eligible = eligibility(participant, to, Set.of());
                if (eligible != ParticipantEligibilityReason.ELIGIBLE) {
                    throw new ActivityEnrollmentException(Error.PARTICIPANT_NOT_ELIGIBLE);
                }
            } else if (age != null && ((to.minAge() != null && age < to.minAge())
                    || (to.maxAge() != null && age > to.maxAge()))) {
                throw new ActivityEnrollmentException(Error.PARTICIPANT_NOT_ELIGIBLE);
            }
            ages.add(age);
        }
        UUID changeId = changes.insertChange(
                enrollmentId,
                accountId,
                ChangeKind.RESCHEDULE.name(),
                reason == null ? null : reason.name(),
                active.size(),
                from.sessionId(),
                to.sessionId(),
                null,
                null,
                now);
        for (int index = 0; index < active.size(); index++) {
            TicketRow ticket = active.get(index);
            if (changes.cancelForReschedule(enrollmentId, ticket.ticketId(), changeId, now) == 0) {
                throw new ActivityEnrollmentException(Error.RESCHEDULE_NOT_ALLOWED);
            }
            changes.insertRescheduledTicket(
                    enrollmentId,
                    to.sessionId(),
                    ticket.participantId(),
                    ticket.participantName(),
                    ticket.participantRelation(),
                    ticket.participantRelation().equals(ParticipantRelation.SELF.name()) ? null : ages.get(index),
                    ticketCodes.get(),
                    changeId,
                    now);
        }
        if (changes.moveEnrollment(enrollmentId, accountId, from.sessionId(), to.sessionId(), now) == 0) {
            throw new ActivityEnrollmentException(Error.RESCHEDULE_NOT_ALLOWED);
        }
        mapper.addEnrolled(from.sessionId(), -active.size(), now);
        mapper.addEnrolled(to.sessionId(), active.size(), now);
        mapper.insertEvent(enrollmentId, null, "RESCHEDULED", accountId, now);
        seatsReleased.released(from.sessionId(), now);
        try {
            notifier.rescheduled(new ActivityEnrollmentNotifier.Rescheduled(
                    accountId, enrollmentId, changeId, to.title(), to.startsAt(), active.size(), now.toInstant()));
        } catch (RuntimeException exception) {
            log.error("telling account {} that enrollment {} was rescheduled failed", accountId, enrollmentId, exception);
        }
        return readEnrollment(accountId, enrollmentId);
    }

    // ---- 退改记录（S5-3） ----

    /** 一次取消或改期的详情：退款进度来自交易模块；改期的新凭证给凭证号。只读本账号的。 */
    @Transactional(readOnly = true)
    public ChangeDetailView readChange(UUID accountId, UUID enrollmentId, UUID changeId) {
        requireAccount(accountId);
        requireChanges();
        if (enrollmentId == null || changeId == null) {
            throw new IllegalArgumentException("change id is required");
        }
        EnrollmentRow enrollment = mapper.findEnrollment(accountId, enrollmentId);
        ChangeRow change = enrollment == null ? null : changes.findChange(accountId, enrollmentId, changeId);
        if (change == null) {
            throw new ActivityEnrollmentException(Error.NOT_FOUND);
        }
        Map<UUID, RefundProgress> refunds = change.refundId() == null
                ? Map.of()
                : payments.refundStates(List.of(change.refundId()));
        OffsetDateTime now = now();
        List<ChangeTicketView> tickets = changes.findChangeTickets(changeId).stream()
                .map(ticket -> new ChangeTicketView(
                        ticket.ticketId(),
                        ticket.participantName(),
                        ParticipantRelation.valueOf(ticket.participantRelation()),
                        ticket.participantAge(),
                        ticket.issued(),
                        ticket.issued() && !TicketStatus.CANCELLED.name().equals(ticket.status())
                                ? ticket.ticketCode()
                                : null,
                        ticketStatus(ticket.status(), ticket.issued()
                                && !sessionEnd(enrollment.startsAt(), enrollment.endsAt()).isAfter(now))))
                .toList();
        return new ChangeDetailView(
                toChange(change, refunds),
                enrollment.enrollmentId(),
                enrollment.title(),
                ActivityCatalogReadService.secureMedia(enrollment.coverUrl(), enrollment.title()),
                enrollment.organizerName(),
                tickets);
    }

    // ---- 候补转正（S5-3，ActivityWaitlistService 调用） ----

    /**
     * 按一条已转正的候补建报名单（在候补服务的事务里，场次已锁、名额已经为它占住）。幂等键就是候补 ID，
     * 重复调用返回同一张报名单。收费的付款截止就是保留截止，金额要和页面看到的一致。
     *
     * @return 报名单 ID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enrollFromWaitlist(
            UUID accountId,
            UUID waitlistId,
            SessionRow session,
            List<UUID> participantIds,
            Long expectedAmountMinor,
            OffsetDateTime holdUntil
    ) {
        List<UUID> ids = participantIds.stream().distinct().sorted().toList();
        String requestHash = hash(session.activityId(), session.sessionId(), ids);
        EnrollmentKeyRow replayed = mapper.findEnrollmentByKey(accountId, waitlistId);
        if (replayed != null) {
            return replayed.id();
        }
        boolean paid = isPaid(session);
        if (paid && !payments.merchantCanCollect(session.organizerId())) {
            throw new ActivityEnrollmentException(Error.PAYMENT_UNAVAILABLE);
        }
        Set<UUID> enrolled = Set.copyOf(mapper.findEnrolledParticipantIds(session.sessionId(), accountId));
        List<ParticipantRow> participants = new ArrayList<>();
        for (UUID participantId : ids) {
            ParticipantRow row = requireParticipant(accountId, participantId);
            if (enrolled.contains(row.id())) {
                throw new ActivityEnrollmentException(Error.PARTICIPANT_ALREADY_ENROLLED);
            }
            participants.add(row);
        }
        return createEnrollment(accountId, waitlistId, requestHash, session, participants, expectedAmountMinor,
                holdUntil, false, now());
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
        if (row.id() != null && enrolled.contains(row.id())) {
            return ParticipantEligibilityReason.ALREADY_ENROLLED;
        }
        Integer minAge = session.minAge();
        Integer maxAge = session.maxAge();
        if (!hasBirthMonth(row)) {
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

    /** 能接新报名：在公开目录里，并且主办机构没被挂起上架（身份迁移 V013）。改期、付款恢复只看 {@link #isListed}。 */
    private static boolean isEnrollable(SessionRow session) {
        return isListed(session) && !session.listingSuspended();
    }

    private static boolean isListed(SessionRow session) {
        return "PUBLISHED".equals(session.activityStatus())
                && "ACTIVE".equals(session.sessionStatus());
    }

    private static boolean isChild(ParticipantRow row) {
        return ParticipantRelation.CHILD.name().equals(row.relation());
    }

    /** 子女和团队队员有出生年月，按场次当天算周岁；“本人”没有，按成年人处理。 */
    private static boolean hasBirthMonth(ParticipantRow row) {
        return row.birthMonth() != null;
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
        boolean child = hasBirthMonth(row);
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
            OffsetDateTime now,
            Extras extras
    ) {
        Map<UUID, ResultRow> results = extras == null ? Map.of() : extras.results();
        // 改期作废的原凭证（S5-3）不在报名单上列出，退改详情里能看到。
        ticketRows = ticketRows.stream()
                .filter(ticket -> !TicketCancelCause.RESCHEDULED.name().equals(ticket.cancelCause()))
                .toList();
        boolean ended = !sessionEnd(row.startsAt(), row.endsAt()).isAfter(now);
        EnrollmentStatus status;
        if (EnrollmentRecord.CANCELLED.equals(row.status()) || EnrollmentRecord.CLOSED.equals(row.status())) {
            status = EnrollmentStatus.CANCELLED;
        } else {
            status = ended ? EnrollmentStatus.FINISHED : EnrollmentStatus.UPCOMING;
        }
        List<TicketView> tickets = ticketRows.stream()
                .map(ticket -> toTicket(ticket, ended, row.unitAmountMinor(), refunds, results.get(ticket.ticketId())))
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
                toPayment(row),
                extras == null || extras.row() == null
                        ? EnrollmentKind.INDIVIDUAL
                        : EnrollmentKind.valueOf(extras.row().kind()),
                toTeam(extras),
                toReschedule(extras),
                extras == null
                        ? List.of()
                        : extras.changes().stream().map(change -> toChange(change, refunds)).toList(),
                // 改期（S5-3）：已报名（不是待付款）、开始前、还有没用的凭证、没有人签到。
                extras != null
                        && !EnrollmentRecord.PENDING_PAYMENT.equals(row.status())
                        && status == EnrollmentStatus.UPCOMING
                        && now.isBefore(row.startsAt())
                        && tickets.stream().anyMatch(ticket -> ticket.status() == TicketStatus.ACTIVE)
                        && tickets.stream().noneMatch(ticket -> ticket.status() == TicketStatus.CHECKED_IN));
    }

    private static EnrollmentTeamView toTeam(Extras extras) {
        if (extras == null || extras.row() == null || extras.row().teamName() == null) {
            return null;
        }
        return new EnrollmentTeamView(
                extras.row().teamName(), extras.row().leaderName(), maskPhone(extras.row().leaderPhone()));
    }

    private static EnrollmentRescheduleView toReschedule(Extras extras) {
        if (extras == null || extras.row() == null || extras.row().previousSessionId() == null) {
            return null;
        }
        EnrollmentExtrasRow row = extras.row();
        return new EnrollmentRescheduleView(
                new EnrollmentSessionRefView(
                        row.previousSessionId(), blankToNull(row.previousSessionLabel()), row.previousStartsAt()),
                row.rescheduledAt());
    }

    private static ChangeSummaryView toChange(ChangeRow change, Map<UUID, RefundProgress> refunds) {
        RefundProgress progress = change.refundId() == null ? null : refunds.get(change.refundId());
        TicketRefundStatus refundStatus = change.refundId() == null
                ? null
                : progress == null ? TicketRefundStatus.REQUESTED : TicketRefundStatus.valueOf(progress.status());
        return new ChangeSummaryView(
                change.changeId(),
                ChangeKind.valueOf(change.kind()),
                change.reason() == null ? null : ChangeReason.valueOf(change.reason()),
                change.people(),
                new EnrollmentSessionRefView(
                        change.fromSessionId(), blankToNull(change.fromSessionLabel()), change.fromStartsAt()),
                change.toSessionId() == null
                        ? null
                        : new EnrollmentSessionRefView(
                                change.toSessionId(), blankToNull(change.toSessionLabel()), change.toStartsAt()),
                change.refundAmountMinor(),
                refundStatus,
                progress == null || progress.succeededAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(progress.succeededAt(), ActivityCatalogReadService.ACTIVITY_ZONE),
                change.createdAt());
    }

    /** 138****2026 */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return null;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
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
            Map<UUID, RefundProgress> refunds,
            ResultRow result
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
                refund,
                result == null
                        ? null
                        : new TicketResultView(
                                ResultCompletion.valueOf(result.completion()),
                                result.scoreText(),
                                result.certificateNote(),
                                result.recordedAt()));
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

    private static String requireText(String text, int maxLength, String what) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()
                || value.codePointCount(0, value.length()) > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return value;
    }

    private void requireChanges() {
        if (changes == null) {
            throw new ActivityEnrollmentException(Error.ENROLLMENT_UNSUPPORTED);
        }
    }

    private static ActivityEnrollmentStatus sessionStatus(SessionRow session, OffsetDateTime now) {
        return ActivityEnrollmentStatus.ofSession(
                now,
                session.startsAt(),
                session.endsAt(),
                session.enrollmentClosesAt(),
                session.capacity(),
                session.enrolledCount(),
                session.waitlistAllowed(),
                session.saleStopped());
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
        return hashText(activityId + "|" + sessionId + "|"
                + participantIds.stream().map(UUID::toString).collect(Collectors.joining(",")));
    }

    /** 参与人姓名按页面提交的顺序；没给顺序（团队报名、候补转正）时照 participants 的顺序。 */
    private static List<String> namesInOrder(List<ParticipantRow> participants, List<UUID> submittedOrder) {
        if (submittedOrder == null) {
            return participants.stream().map(ParticipantRow::name).toList();
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        participants.forEach(row -> names.put(row.id(), row.name()));
        return submittedOrder.stream().map(names::get).toList();
    }

    private static String hashText(String text) {
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

/** MEMBER（S5-3）：领队在团队报名时现填的队员，不出现在“参与人”页。 */
enum ParticipantRelation {
    SELF,
    CHILD,
    MEMBER
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

/** 凭证的取消原因（activity_enrollment_ticket.cancel_cause）。RESCHEDULED（S5-3）：改期后原凭证作废。 */
enum TicketCancelCause {
    BUYER,
    PAYMENT_CLOSED,
    RESCHEDULED
}

/** 报名单类型（S5-3）：个人报名 / 团队报名。 */
enum EnrollmentKind {
    INDIVIDUAL,
    TEAM
}

/** 退改记录的类型（S5-3）：取消（收费的按人原路退款）/ 改期。 */
enum ChangeKind {
    CANCEL,
    RESCHEDULE
}

/** 退改的申请原因（S5-3，页面可以不选）：时间冲突 / 身体不适 / 行程有变 / 其他原因。 */
enum ChangeReason {
    TIME_CONFLICT,
    UNWELL,
    PLAN_CHANGED,
    OTHER;

    static ChangeReason parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("change reason is invalid");
        }
    }
}

/** 团队名单一行的核对结果（S5-3）：参与人的四种，加上名单里重复了。 */
enum TeamMemberReason {
    ELIGIBLE,
    UNDER_MIN_AGE,
    OVER_MAX_AGE,
    ALREADY_ENROLLED,
    DUPLICATED;

    static TeamMemberReason of(ParticipantEligibilityReason reason) {
        return valueOf(reason.name());
    }
}

/** 结果记录的完成情况（S5-3）。 */
enum ResultCompletion {
    COMPLETED,
    INCOMPLETE
}

/** 报名单落库的状态（activity_enrollment.status）。 */
final class EnrollmentRecord {
    static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    static final String CONFIRMED = "CONFIRMED";
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

/**
 * S5-3 加了 kind、team（团队报名）、reschedule（最近一次改期前的场次）、changes（退改记录，最近的在前）、
 * reschedulable（现在能不能改期）。
 */
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
        EnrollmentPaymentView payment,
        EnrollmentKind kind,
        EnrollmentTeamView team,
        EnrollmentRescheduleView reschedule,
        List<ChangeSummaryView> changes,
        boolean reschedulable
) {

    /** S5-3 之前的形状：个人报名，没有改期和退改记录。 */
    EnrollmentView(
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
        this(enrollmentId, status, activity, session, location, createdAt, cancellableUntil, cancellable, tickets,
                payment, EnrollmentKind.INDIVIDUAL, null, null, List.of(), false);
    }
}

/** 团队报名的团队资料（S5-3）；领队手机打码。 */
record EnrollmentTeamView(String teamName, String leaderName, String leaderPhoneMasked) {
}

record EnrollmentSessionRefView(UUID sessionId, String label, OffsetDateTime startsAt) {
}

/** 最近一次改期（S5-3）：改之前的场次和改期时间。 */
record EnrollmentRescheduleView(EnrollmentSessionRefView previousSession, OffsetDateTime rescheduledAt) {
}

/**
 * 一次取消或改期（S5-3）。取消收费报名的带这一笔退款的金额、进度和到账时间（来自交易模块）；改期带新场次。
 */
record ChangeSummaryView(
        UUID changeId,
        ChangeKind kind,
        ChangeReason reason,
        int people,
        EnrollmentSessionRefView fromSession,
        EnrollmentSessionRefView toSession,
        Long refundAmountMinor,
        TicketRefundStatus refundStatus,
        OffsetDateTime refundSucceededAt,
        OffsetDateTime createdAt
) {
}

record ChangeDetailView(
        ChangeSummaryView change,
        UUID enrollmentId,
        String activityTitle,
        ActivityMediaView coverImage,
        String organizerName,
        List<ChangeTicketView> tickets
) {
}

/** issued 为 true 是这一次发出来的新凭证（改期），否则是这一次取消掉的凭证；只有还有效的新凭证给凭证号。 */
record ChangeTicketView(
        UUID ticketId,
        String participantName,
        ParticipantRelation relation,
        Integer age,
        boolean issued,
        String ticketCode,
        TicketStatus status
) {
}

/** 已有的参与人用 participantId；现填的给姓名和出生年月（YYYY-MM）。 */
record TeamMemberInput(UUID participantId, String name, String birthMonth) {

    String identityText() {
        if (participantId != null) {
            return "id:" + participantId;
        }
        return "new:" + (name == null ? "" : name.trim()) + "|" + (birthMonth == null ? "" : birthMonth.trim());
    }
}

record TeamEnrollCommand(
        UUID activityId,
        UUID sessionId,
        String teamName,
        String leaderName,
        String leaderPhone,
        List<TeamMemberInput> members,
        boolean noticeAgreed,
        boolean guardianConsent,
        Long expectedAmountMinor
) {
}

record TeamCheckView(List<TeamMemberCheckView> members, int eligibleCount) {
}

record TeamMemberCheckView(
        UUID participantId,
        String name,
        String birthMonth,
        Integer ageAtSession,
        boolean eligible,
        TeamMemberReason reason
) {
}

/** 主办方录的结果（S5-3）。 */
record TicketResultView(
        ResultCompletion completion,
        String scoreText,
        String certificateNote,
        OffsetDateTime recordedAt
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
        TicketRefundView refund,
        TicketResultView result
) {

    /** S5-3 之前的形状：没有结果记录。 */
    TicketView(
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
        this(ticketId, participantId, participantName, relation, age, ticketCode, status, checkedInAt, cancelledAt,
                refund, null);
    }
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
