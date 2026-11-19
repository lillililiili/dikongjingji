package com.lowaltitude.inspection.experience.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.experience.application.VenueBookingException.Error;
import com.lowaltitude.inspection.experience.application.VenueParticipantPort.VenueParticipant;
import com.lowaltitude.inspection.experience.application.VenuePaymentPort.OpenPayment;
import com.lowaltitude.inspection.experience.application.VenuePaymentPort.OpenedPayment;
import com.lowaltitude.inspection.experience.application.VenuePaymentPort.RefundProgress;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper.BookingRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper.CheckinRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper.KeyRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper.ParticipantRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper.SessionRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper.StateRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 到馆预约（E10，契约 contracts/openapi-experience.yaml 1.1.0，规则见 docs/redesign/03-App实现任务计划.md §21.1）。
 *
 * <ul>
 *   <li>能约：场馆已发布、项目启用、场次没开始且有余位。标价大于 0 的要付款，场馆运营机构必须能在线收款；
 *       标价为 0 的提交即预约成功。</li>
 *   <li>参与人复用“本人和子女”；按项目最低年龄在场次当天算周岁（本人按成年人）；一单最多 10 人、不超过余位；
 *       同一个人同一场次只能有一张有效预约（待付款也算）。</li>
 *   <li>一单一码：付款成功（免费的提交即）生成核销码。付款时限 15 分钟，不晚于场次开始；超时或取消关闭、名额释放；
 *       关闭之后才到账的，场次没开始、余位够、这些人没另约时恢复，否则交易模块整单退回。</li>
 *   <li>改期：开场前改到同一项目的另一场（有余位），立即生效，旧码作废、换新码，不补差价。</li>
 *   <li>取消：待付款的关单；已付款（或免费）的开场前取消，整单原路全额退款。已核销、已开场的不能取消。</li>
 *   <li>没到馆：场次结束后读成“已过期”（按时间算，不落库），不退款。</li>
 *   <li>核销：场馆运营机构的操作员按码核销，开场前 30 分钟到场次结束，整单一次，重复核销返回第一次的时间。</li>
 * </ul>
 *
 * <p>名额只在锁住场次那一行之后增减。加锁顺序：交易订单 → 场次（两个场次按 ID 从小到大）→ 预约单。
 */
public class VenueBookingService {

    static final int MAX_PEOPLE = 10;
    /** 付款时限（设计假设 3）。 */
    static final Duration PAY_WINDOW = Duration.ofMinutes(15);
    /** 开场前多久开始可以核销。 */
    static final Duration CHECKIN_OPENS_BEFORE = Duration.ofMinutes(30);
    /** “本人”按成年人处理。 */
    static final int ADULT_AGE = 18;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String SOURCE_REFUND_REASON = "取消到馆预约";
    private static final Pattern CODE = Pattern.compile("^VEN-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$");
    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final Logger log = LoggerFactory.getLogger(VenueBookingService.class);

    private final VenueBookingMapper mapper;
    private final VenueParticipantPort participants;
    private final VenuePaymentPort payments;
    private final TransactionOperations tx;
    private final Clock clock;
    private final Supplier<String> codes;

    VenueBookingService(
            VenueBookingMapper mapper,
            VenueParticipantPort participants,
            VenuePaymentPort payments,
            TransactionOperations tx,
            Clock clock,
            Supplier<String> codes
    ) {
        this.mapper = mapper;
        this.participants = participants;
        this.payments = payments;
        this.tx = tx;
        this.clock = clock;
        this.codes = codes;
    }

    // ---- 参与人 ----

    /** 我的参与人，附上这一场能不能约和原因。 */
    @Transactional
    public VenueParticipantListView listParticipants(UUID accountId, String accountName, UUID sessionId) {
        requireAccount(accountId);
        if (sessionId == null) {
            throw new IllegalArgumentException("session id is required");
        }
        SessionRow session = mapper.findSession(sessionId);
        if (session == null || !isListed(session)) {
            throw new VenueBookingException(Error.NOT_FOUND);
        }
        List<VenueParticipant> people = participants.participants(accountId, accountName);
        Set<UUID> booked = people.isEmpty()
                ? Set.of()
                : Set.copyOf(mapper.findBookedParticipantIds(sessionId, accountId,
                        people.stream().map(VenueParticipant::participantId).toList()));
        LocalDate sessionDay = dateOf(session.startsAt());
        List<VenueParticipantView> items = people.stream()
                .map(person -> {
                    VenueEligibilityReason reason = eligibility(person, session, booked);
                    return new VenueParticipantView(
                            person.participantId(),
                            person.self() ? VenueParticipantRelation.SELF : VenueParticipantRelation.CHILD,
                            person.name(),
                            person.self() ? null : ageOn(person.birthMonth(), sessionDay),
                            reason == VenueEligibilityReason.ELIGIBLE,
                            reason);
                })
                .toList();
        return new VenueParticipantListView(sessionId, seatsLeft(session), MAX_PEOPLE, items);
    }

    // ---- 预约 ----

    /**
     * 提交预约。同一个幂等键重复提交返回第一次的预约（created 为 false），不会多占名额，也不会建第二张交易订单。
     */
    @Transactional
    public BookingResult book(UUID accountId, String accountName, UUID idempotencyKey, BookCommand command) {
        requireAccount(accountId);
        if (idempotencyKey == null
                || command == null
                || command.sessionId() == null
                || command.participantIds() == null
                || command.participantIds().isEmpty()
                || command.participantIds().size() > MAX_PEOPLE
                || command.participantIds().stream().anyMatch(Objects::isNull)
                || command.expectedAmountMinor() == null
                || command.expectedAmountMinor() < 0
                || !command.noticeAgreed()) {
            throw new IllegalArgumentException("booking is invalid");
        }
        List<UUID> participantIds = command.participantIds().stream().distinct().sorted().toList();
        if (participantIds.size() != command.participantIds().size()) {
            throw new IllegalArgumentException("participants are duplicated");
        }
        String requestHash = hash(command.sessionId() + "|"
                + participantIds.stream().map(UUID::toString).collect(Collectors.joining(",")));

        SessionRow session = mapper.lockSession(command.sessionId());
        if (session == null) {
            throw new VenueBookingException(Error.NOT_FOUND);
        }
        // 拿到场次锁之后再查幂等键：两个相同的请求同时到，后到的在这里读到先到的结果。
        KeyRow replayed = mapper.findBookingByKey(accountId, idempotencyKey);
        if (replayed != null) {
            if (!requestHash.equals(replayed.requestHash().trim())) {
                throw new VenueBookingException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new BookingResult(readBooking(accountId, replayed.id()), false);
        }
        if (!isListed(session)) {
            throw new VenueBookingException(Error.NOT_FOUND);
        }
        boolean paid = session.priceAmountMinor() > 0;
        // 场馆收不了款时，换人、换场次都没有用，所以最先判断。
        if (paid && !payments.merchantCanCollect(session.operatorId())) {
            throw new VenueBookingException(Error.PAYMENT_UNAVAILABLE);
        }
        OffsetDateTime now = now();
        if (!now.isBefore(session.startsAt())) {
            throw new VenueBookingException(Error.SESSION_NOT_OPEN);
        }

        Map<UUID, VenueParticipant> mine = participants.participants(accountId, accountName).stream()
                .collect(Collectors.toMap(VenueParticipant::participantId, Function.identity(), (a, b) -> a));
        List<VenueParticipant> chosen = new ArrayList<>();
        for (UUID participantId : participantIds) {
            VenueParticipant person = mine.get(participantId);
            if (person == null) {
                throw new VenueBookingException(Error.NOT_FOUND);
            }
            chosen.add(person);
        }
        Set<UUID> booked = Set.copyOf(mapper.findBookedParticipantIds(session.sessionId(), accountId, participantIds));
        for (VenueParticipant person : chosen) {
            VenueEligibilityReason reason = eligibility(person, session, booked);
            if (reason == VenueEligibilityReason.ALREADY_BOOKED) {
                throw new VenueBookingException(Error.PARTICIPANT_ALREADY_BOOKED);
            }
            if (reason != VenueEligibilityReason.ELIGIBLE) {
                throw new VenueBookingException(Error.PARTICIPANT_NOT_ELIGIBLE);
            }
        }
        if (session.bookedCount() + chosen.size() > session.capacity()) {
            throw new VenueBookingException(Error.SEATS_NOT_ENOUGH);
        }
        long unit = session.priceAmountMinor();
        long amount = Math.multiplyExact(unit, chosen.size());
        if (command.expectedAmountMinor() != amount) {
            throw new VenueBookingException(Error.AMOUNT_CHANGED);
        }

        UUID bookingId = UUID.randomUUID();
        if (paid) {
            OffsetDateTime payBefore = now.plus(PAY_WINDOW);
            if (session.startsAt().isBefore(payBefore)) {
                payBefore = session.startsAt();
            }
            OpenedPayment opened = payments.open(new OpenPayment(
                    bookingId,
                    accountId,
                    session.operatorId(),
                    session.cityCode().trim(),
                    session.projectId(),
                    session.sessionId(),
                    session.venueName() + " " + session.projectName(),
                    SESSION_TIME.format(session.startsAt().atZoneSameInstant(ExperienceCatalogReadService.VENUE_ZONE)),
                    session.coverUrl(),
                    unit,
                    chosen.size(),
                    payBefore.toInstant()));
            mapper.insertBooking(bookingId, accountId, session.venueId(), session.projectId(), session.sessionId(),
                    VenueBookingRecord.PENDING_PAYMENT, null, chosen.size(), unit, amount, opened.tradeOrderId(),
                    OffsetDateTime.ofInstant(opened.payBefore(), clock.getZone()), idempotencyKey, requestHash,
                    null, now);
        } else {
            // 标价为 0：提交即预约成功，马上给核销码。
            mapper.insertBooking(bookingId, accountId, session.venueId(), session.projectId(), session.sessionId(),
                    VenueBookingRecord.CONFIRMED, codes.get(), chosen.size(), 0, 0, null, null, idempotencyKey,
                    requestHash, now, now);
        }
        LocalDate sessionDay = dateOf(session.startsAt());
        for (VenueParticipant person : chosen) {
            mapper.insertParticipant(bookingId, session.sessionId(), person.participantId(), person.name(),
                    person.self() ? VenueParticipantRelation.SELF.name() : VenueParticipantRelation.CHILD.name(),
                    person.self() ? null : ageOn(person.birthMonth(), sessionDay), now);
        }
        mapper.addBooked(session.sessionId(), chosen.size(), now);
        mapper.insertEvent(bookingId, "BOOKED", accountId, null, null, now);
        return new BookingResult(readBooking(accountId, bookingId), true);
    }

    @Transactional(readOnly = true)
    public VenueBookingView readBooking(UUID accountId, UUID bookingId) {
        requireAccount(accountId);
        if (bookingId == null) {
            throw new IllegalArgumentException("booking id is required");
        }
        BookingRow row = mapper.findBooking(accountId, bookingId);
        if (row == null) {
            throw new VenueBookingException(Error.NOT_FOUND);
        }
        return toViews(List.of(row), now()).getFirst();
    }

    @Transactional(readOnly = true)
    public VenueBookingPageView listBookings(UUID accountId, String bucketCode, int page, int size) {
        requireAccount(accountId);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        VenueBookingBucket bucket = VenueBookingBucket.parse(bucketCode);
        OffsetDateTime now = now();
        long total = mapper.countBookings(accountId, bucket.name(), now);
        List<BookingRow> rows = mapper.findBookingPage(
                accountId, bucket.name(), now, size, Math.multiplyExact(page - 1, size));
        return new VenueBookingPageView(page, size, total, (long) page * size < total, toViews(rows, now));
    }

    // ---- 取消 ----

    /**
     * 取消。待付款的在事务外关交易订单（发起过支付的先向渠道查单、关单），关闭后名额由 {@link #onPaymentClosed} 释放；
     * 查单发现已经付了款时什么都不取消，返回已付款的预约。待到馆的开场前取消：名额释放，付过款的整单原路全额退款
     * （提交后交给渠道）。已经取消、关闭的再取消一次没有变化；已核销、已开场的不能取消。
     */
    public VenueBookingView cancel(UUID accountId, UUID bookingId) {
        requireAccount(accountId);
        if (bookingId == null) {
            throw new IllegalArgumentException("booking id is required");
        }
        BookingRow row = tx.execute(status -> mapper.findBooking(accountId, bookingId));
        if (row == null) {
            throw new VenueBookingException(Error.NOT_FOUND);
        }
        if (VenueBookingRecord.PENDING_PAYMENT.equals(row.status())) {
            payments.cancelUnpaid(accountId, row.tradeOrderId());
            return tx.execute(status -> readBooking(accountId, bookingId));
        }
        return tx.execute(status -> cancelConfirmed(accountId, bookingId));
    }

    private VenueBookingView cancelConfirmed(UUID accountId, UUID bookingId) {
        BookingRow found = mapper.findBooking(accountId, bookingId);
        if (found == null) {
            throw new VenueBookingException(Error.NOT_FOUND);
        }
        if (found.tradeOrderId() != null) {
            payments.lockOrder(found.tradeOrderId());
        }
        SessionRow session = mapper.lockSession(found.sessionId());
        StateRow booking = mapper.lockBooking(accountId, bookingId);
        switch (booking.status()) {
            case VenueBookingRecord.CANCELLED, VenueBookingRecord.CLOSED -> {
                return readBooking(accountId, bookingId);
            }
            case VenueBookingRecord.CONFIRMED -> {
                // 往下走。
            }
            default -> throw new VenueBookingException(Error.CANCEL_NOT_ALLOWED);
        }
        OffsetDateTime now = now();
        // 改期和取消都先锁场次；锁之前读到的场次可能已经被改期换掉，换了就按版本冲突让页面刷新。
        if (!booking.sessionId().equals(session.sessionId())) {
            throw new VenueBookingException(Error.VERSION_CONFLICT);
        }
        if (!now.isBefore(session.startsAt())) {
            throw new VenueBookingException(Error.CANCEL_NOT_ALLOWED);
        }
        mapper.cancelConfirmed(bookingId, now);
        mapper.releaseParticipants(bookingId, now);
        mapper.addBooked(session.sessionId(), -booking.people(), now);
        mapper.insertEvent(bookingId, "CANCELLED", accountId, null, null, now);
        if (booking.amountMinor() > 0) {
            UUID refundId = payments.requestRefund(
                    booking.tradeOrderId(),
                    booking.amountMinor(),
                    SOURCE_REFUND_REASON,
                    UUID.nameUUIDFromBytes(("venue-refund:" + bookingId).getBytes(StandardCharsets.UTF_8)),
                    accountId);
            mapper.setRefund(bookingId, refundId, now);
            mapper.insertEvent(bookingId, "REFUND_REQUESTED", accountId, null, null, now);
        }
        return readBooking(accountId, bookingId);
    }

    // ---- 改期 ----

    /**
     * 改到同一项目的另一场：开场前、目标场次没开始、有余位、这些人在目标场次没有别的有效预约。立即生效，
     * 两个场次的名额在锁内一出一进，换新码（旧码作废）。不补差价。
     * version 是页面上看到的预约版本，对不上说明预约已经变了；已经在目标场次上时原样返回（重复提交没有变化）。
     */
    @Transactional
    public VenueBookingView reschedule(UUID accountId, UUID bookingId, int version, UUID targetSessionId) {
        requireAccount(accountId);
        if (bookingId == null || targetSessionId == null) {
            throw new IllegalArgumentException("reschedule is invalid");
        }
        BookingRow found = mapper.findBooking(accountId, bookingId);
        if (found == null) {
            throw new VenueBookingException(Error.NOT_FOUND);
        }
        if (found.sessionId().equals(targetSessionId)) {
            return readBooking(accountId, bookingId);
        }
        if (found.tradeOrderId() != null) {
            payments.lockOrder(found.tradeOrderId());
        }
        // 两个场次按 ID 从小到大锁，两个人同时在这两场之间对调也不会互相等死。
        UUID first = found.sessionId().compareTo(targetSessionId) < 0 ? found.sessionId() : targetSessionId;
        UUID second = first.equals(found.sessionId()) ? targetSessionId : found.sessionId();
        SessionRow firstSession = mapper.lockSession(first);
        SessionRow secondSession = mapper.lockSession(second);
        SessionRow current = first.equals(found.sessionId()) ? firstSession : secondSession;
        SessionRow target = first.equals(targetSessionId) ? firstSession : secondSession;
        StateRow booking = mapper.lockBooking(accountId, bookingId);
        if (target == null) {
            throw new VenueBookingException(Error.NOT_FOUND);
        }
        if (booking.version() != version || !booking.sessionId().equals(found.sessionId())) {
            throw new VenueBookingException(Error.VERSION_CONFLICT);
        }
        OffsetDateTime now = now();
        if (!VenueBookingRecord.CONFIRMED.equals(booking.status()) || !now.isBefore(current.startsAt())) {
            throw new VenueBookingException(Error.RESCHEDULE_NOT_ALLOWED);
        }
        if (!target.projectId().equals(booking.projectId())) {
            throw new VenueBookingException(Error.RESCHEDULE_NOT_ALLOWED);
        }
        if (!isListed(target) || !now.isBefore(target.startsAt())) {
            throw new VenueBookingException(Error.SESSION_NOT_OPEN);
        }
        if (target.bookedCount() + booking.people() > target.capacity()) {
            throw new VenueBookingException(Error.SEATS_NOT_ENOUGH);
        }
        if (mapper.countOtherActiveParticipants(bookingId, target.sessionId()) > 0) {
            throw new VenueBookingException(Error.PARTICIPANT_ALREADY_BOOKED);
        }
        mapper.moveBooking(bookingId, target.sessionId(), codes.get(), version, now);
        mapper.moveParticipants(bookingId, target.sessionId(), now);
        mapper.addBooked(current.sessionId(), -booking.people(), now);
        mapper.addBooked(target.sessionId(), booking.people(), now);
        mapper.insertEvent(bookingId, "RESCHEDULED", accountId, current.sessionId(), target.sessionId(), now);
        return readBooking(accountId, bookingId);
    }

    // ---- 付款结果（交易模块在改交易订单状态的同一个事务里调用，交易订单已锁） ----

    /**
     * 付款到账。待付款的预约成为待到馆、生成核销码；已经处理过的直接收下；没付款就关闭了的，
     * 场次没开始、余位够、这些人在这一场没有另约时恢复，否则收不下。
     *
     * @return false 表示收不下，交易模块随即整单原路退款，预约保持“已关闭”
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onPaymentConfirmed(UUID tradeOrderId, UUID bookingId) {
        StateRow found = mapper.findPaidBooking(bookingId, tradeOrderId);
        if (found == null) {
            log.error("trade order {} was paid for an unknown venue booking {}", tradeOrderId, bookingId);
            return false;
        }
        SessionRow session = mapper.lockSession(found.sessionId());
        StateRow booking = mapper.lockPaidBooking(bookingId, tradeOrderId);
        OffsetDateTime now = now();
        return switch (booking.status()) {
            case VenueBookingRecord.PENDING_PAYMENT -> {
                mapper.confirmPayment(bookingId, codes.get(), now);
                mapper.insertEvent(bookingId, "PAYMENT_CONFIRMED", null, null, null, now);
                yield true;
            }
            case VenueBookingRecord.CLOSED -> restore(booking, session, now);
            // 已经按付款处理过（之后可能改期、取消或核销了）。
            default -> true;
        };
    }

    private boolean restore(StateRow booking, SessionRow session, OffsetDateTime now) {
        if (!isListed(session)
                || !now.isBefore(session.startsAt())
                || session.bookedCount() + booking.people() > session.capacity()
                || mapper.countOtherActiveParticipants(booking.bookingId(), session.sessionId()) > 0) {
            log.warn("venue booking {} was paid after it closed and cannot be restored; it will be refunded",
                    booking.bookingId());
            return false;
        }
        mapper.holdParticipants(booking.bookingId(), now);
        mapper.restoreClosed(booking.bookingId(), codes.get(), now);
        mapper.addBooked(session.sessionId(), booking.people(), now);
        mapper.insertEvent(booking.bookingId(), "PAYMENT_RESTORED", null, null, null, now);
        return true;
    }

    /** 交易订单没付款就关闭了（超时或取消）：待付款的预约关闭，名额释放。别的状态不动。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentClosed(UUID tradeOrderId, UUID bookingId) {
        StateRow found = mapper.findPaidBooking(bookingId, tradeOrderId);
        if (found == null) {
            log.error("trade order {} closed for an unknown venue booking {}", tradeOrderId, bookingId);
            return;
        }
        SessionRow session = mapper.lockSession(found.sessionId());
        StateRow booking = mapper.lockPaidBooking(bookingId, tradeOrderId);
        if (!VenueBookingRecord.PENDING_PAYMENT.equals(booking.status())) {
            return;
        }
        OffsetDateTime now = now();
        mapper.closeUnpaid(bookingId, now);
        mapper.releaseParticipants(bookingId, now);
        mapper.addBooked(session.sessionId(), -booking.people(), now);
        mapper.insertEvent(bookingId, "PAYMENT_CLOSED", null, null, null, now);
    }

    // ---- 核销 ----

    /**
     * 场馆运营机构的操作员按核销码核销整单。别的机构的码、改期后作废的旧码当作不存在；
     * 同一个码再核销一次返回第一次的时间（repeated 为 true），不改任何数据。
     */
    @Transactional
    public VenueCheckinView checkIn(UUID operatorId, UUID organizationId, String codeText) {
        if (operatorId == null || organizationId == null) {
            throw new VenueBookingException(Error.FORBIDDEN);
        }
        String code = codeText == null ? "" : codeText.trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code is invalid");
        }
        CheckinRow row = mapper.lockBookingByCode(code, organizationId);
        if (row == null) {
            throw new VenueBookingException(Error.NOT_FOUND);
        }
        if (VenueBookingRecord.CHECKED_IN.equals(row.status())) {
            return toCheckin(row, row.checkedInAt(), true);
        }
        if (!VenueBookingRecord.CONFIRMED.equals(row.status())) {
            throw new VenueBookingException(Error.CODE_NOT_VALID);
        }
        OffsetDateTime now = now();
        if (now.isBefore(row.startsAt().minus(CHECKIN_OPENS_BEFORE)) || !now.isBefore(row.endsAt())) {
            throw new VenueBookingException(Error.CHECKIN_NOT_OPEN);
        }
        mapper.checkIn(row.bookingId(), operatorId, now);
        mapper.insertEvent(row.bookingId(), "CHECKED_IN", operatorId, null, null, now);
        return toCheckin(row, now, false);
    }

    // ---- 规则 ----

    /** 只知道出生年月，按月份算周岁（和活动报名一样）。 */
    static int ageOn(LocalDate birthMonth, LocalDate date) {
        long years = ChronoUnit.YEARS.between(YearMonth.from(birthMonth), YearMonth.from(date));
        return (int) Math.max(0, years);
    }

    /**
     * 这个人能不能约这一场：这一场已有有效预约的优先；子女按场次当天的周岁和项目的最低年龄比；
     * “本人”按成年人处理，只有最低年龄高于 18 岁时不能约（确认不了）。
     */
    private static VenueEligibilityReason eligibility(VenueParticipant person, SessionRow session, Set<UUID> booked) {
        if (booked.contains(person.participantId())) {
            return VenueEligibilityReason.ALREADY_BOOKED;
        }
        Integer minAge = session.minAge();
        if (minAge == null) {
            return VenueEligibilityReason.ELIGIBLE;
        }
        int age = person.self() ? ADULT_AGE : ageOn(person.birthMonth(), dateOf(session.startsAt()));
        return age < minAge ? VenueEligibilityReason.UNDER_MIN_AGE : VenueEligibilityReason.ELIGIBLE;
    }

    private static boolean isListed(SessionRow session) {
        return "PUBLISHED".equals(session.venueStatus())
                && "ACTIVE".equals(session.projectStatus())
                && "ACTIVE".equals(session.sessionStatus());
    }

    private static int seatsLeft(SessionRow session) {
        return Math.max(0, session.capacity() - session.bookedCount());
    }

    private static LocalDate dateOf(OffsetDateTime time) {
        return time.atZoneSameInstant(ExperienceCatalogReadService.VENUE_ZONE).toLocalDate();
    }

    // ---- 转换 ----

    private List<VenueBookingView> toViews(List<BookingRow> rows, OffsetDateTime now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ParticipantRow>> people = mapper
                .findParticipants(rows.stream().map(BookingRow::bookingId).toList())
                .stream()
                .collect(Collectors.groupingBy(ParticipantRow::bookingId));
        List<UUID> refundIds = rows.stream().map(BookingRow::refundId).filter(Objects::nonNull).toList();
        Map<UUID, RefundProgress> refunds = refundIds.isEmpty() ? Map.of() : payments.refundStates(refundIds);
        return rows.stream()
                .map(row -> toView(row, people.getOrDefault(row.bookingId(), List.of()), refunds, now))
                .toList();
    }

    private static VenueBookingView toView(
            BookingRow row,
            List<ParticipantRow> people,
            Map<UUID, RefundProgress> refunds,
            OffsetDateTime now
    ) {
        VenueBookingStatus status = status(row.status(), !row.endsAt().isAfter(now));
        boolean beforeStart = now.isBefore(row.startsAt());
        VenueBookingRefundView refund = null;
        if (row.refundId() != null) {
            RefundProgress progress = refunds.get(row.refundId());
            refund = new VenueBookingRefundView(
                    progress == null ? VenueRefundStatus.REQUESTED : VenueRefundStatus.valueOf(progress.status()),
                    row.amountMinor());
        }
        return new VenueBookingView(
                row.bookingId(),
                status,
                new BookingVenueView(row.venueId(), row.venueName(), row.venueAddress(), blankToNull(row.venuePhone())),
                new BookingProjectView(row.projectId(), row.projectName(), row.durationMinutes()),
                new BookingSessionView(row.sessionId(), row.startsAt(), row.endsAt()),
                row.people(),
                people.stream()
                        .map(person -> new BookedParticipantView(
                                person.participantId(),
                                person.participantName(),
                                VenueParticipantRelation.valueOf(person.participantRelation()),
                                person.participantAge()))
                        .toList(),
                // 只有待到馆、已核销的给核销码；待付款、关闭、取消、过期的都不给。
                status == VenueBookingStatus.CONFIRMED || status == VenueBookingStatus.CHECKED_IN ? row.code() : null,
                toPayment(row),
                refund,
                row.createdAt(),
                row.checkedInAt(),
                row.cancelledAt(),
                (status == VenueBookingStatus.PENDING_PAYMENT || status == VenueBookingStatus.CONFIRMED) && beforeStart,
                status == VenueBookingStatus.CONFIRMED && beforeStart,
                row.version());
    }

    /** 待到馆的场次结束了还没核销：已过期，按时间算，不落库。 */
    private static VenueBookingStatus status(String stored, boolean sessionEnded) {
        VenueBookingStatus status = VenueBookingStatus.valueOf(stored);
        return status == VenueBookingStatus.CONFIRMED && sessionEnded ? VenueBookingStatus.EXPIRED : status;
    }

    private static VenueBookingPaymentView toPayment(BookingRow row) {
        if (row.tradeOrderId() == null) {
            return new VenueBookingPaymentView(VenuePaymentStatus.FREE, null, 0, 0, null);
        }
        VenuePaymentStatus status = switch (row.status()) {
            case VenueBookingRecord.PENDING_PAYMENT -> VenuePaymentStatus.PENDING_PAYMENT;
            case VenueBookingRecord.CLOSED -> VenuePaymentStatus.CLOSED;
            default -> VenuePaymentStatus.PAID;
        };
        return new VenueBookingPaymentView(status, row.tradeOrderId(), row.amountMinor(), row.unitAmountMinor(),
                row.payBefore());
    }

    private static VenueCheckinView toCheckin(CheckinRow row, OffsetDateTime checkedInAt, boolean repeated) {
        return new VenueCheckinView(row.code(), row.venueName(), row.projectName(), row.startsAt(), row.people(),
                checkedInAt, repeated);
    }

    // ---- 工具 ----

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

/** 预约单落库的状态（venue_booking.status）。 */
final class VenueBookingRecord {
    static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    static final String CONFIRMED = "CONFIRMED";
    static final String CHECKED_IN = "CHECKED_IN";
    static final String CANCELLED = "CANCELLED";
    static final String CLOSED = "CLOSED";

    private VenueBookingRecord() {
    }
}

/** 预约对用户的状态（契约 VenueBookingStatus）：EXPIRED 按时间算。 */
enum VenueBookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CHECKED_IN,
    EXPIRED,
    CANCELLED,
    CLOSED
}

/** “我的到馆预约”的三个页签。 */
enum VenueBookingBucket {
    UPCOMING,
    FINISHED,
    CANCELLED;

    static VenueBookingBucket parse(String code) {
        if (code == null || code.isBlank()) {
            return UPCOMING;
        }
        try {
            return valueOf(code.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("booking bucket is invalid");
        }
    }
}

enum VenuePaymentStatus {
    FREE,
    PENDING_PAYMENT,
    PAID,
    CLOSED
}

enum VenueRefundStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    CLOSED,
    ABNORMAL
}

enum VenueParticipantRelation {
    SELF,
    CHILD
}

enum VenueEligibilityReason {
    ELIGIBLE,
    UNDER_MIN_AGE,
    ALREADY_BOOKED
}

/**
 * @param expectedAmountMinor 页面看到的应付金额（分）= 单价 × 人数；标价为 0 的项目是 0
 */
record BookCommand(UUID sessionId, List<UUID> participantIds, Long expectedAmountMinor, boolean noticeAgreed) {
}

record BookingResult(VenueBookingView booking, boolean created) {
}

record VenueParticipantView(
        UUID participantId,
        VenueParticipantRelation relation,
        String name,
        Integer ageAtSession,
        boolean eligible,
        VenueEligibilityReason reason
) {
}

record VenueParticipantListView(UUID sessionId, int seatsLeft, int maxPeople, List<VenueParticipantView> items) {
}

record BookingVenueView(UUID venueId, String name, String address, String phone) {
}

record BookingProjectView(UUID projectId, String name, int durationMinutes) {
}

record BookingSessionView(UUID sessionId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
}

record BookedParticipantView(UUID participantId, String name, VenueParticipantRelation relation, Integer age) {
}

record VenueBookingPaymentView(
        VenuePaymentStatus status,
        UUID tradeOrderId,
        long amountMinor,
        long unitAmountMinor,
        OffsetDateTime payBefore
) {
}

record VenueBookingRefundView(VenueRefundStatus status, long amountMinor) {
}

record VenueBookingView(
        UUID bookingId,
        VenueBookingStatus status,
        BookingVenueView venue,
        BookingProjectView project,
        BookingSessionView session,
        int people,
        List<BookedParticipantView> participants,
        String code,
        VenueBookingPaymentView payment,
        VenueBookingRefundView refund,
        OffsetDateTime createdAt,
        OffsetDateTime checkedInAt,
        OffsetDateTime cancelledAt,
        boolean cancellable,
        boolean reschedulable,
        int version
) {
}

record VenueBookingPageView(int page, int size, long total, boolean hasMore, List<VenueBookingView> items) {
}

record VenueCheckinView(
        String code,
        String venueName,
        String projectName,
        OffsetDateTime sessionStartsAt,
        int people,
        OffsetDateTime checkedInAt,
        boolean repeated
) {
}
