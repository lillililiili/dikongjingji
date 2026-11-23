package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityChangeMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityChangeMapper.EnrollmentExtrasRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.EnrollmentRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.ParticipantRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.SessionRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.TicketRow;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 报名服务里 S5-3 的几件事：带申请原因的取消记退改记录并给候补放名额、改期、团队报名、报名单上的附加信息。
 * mapper 用 Mockito 桩出 SQL 的结果；整条链路（真实数据库、交易模块）由 infra/scripts/trade-smoke/activity-waitlist.cjs 覆盖。
 */
class ActivityEnrollmentChangesTest {

    /** 北京时间 2026-11-23 10:00。 */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-23T02:00:00Z");
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    private static final UUID FROM = UUID.fromString("a3000000-0000-4000-8000-000000000203");
    private static final UUID TO = UUID.fromString("a3000000-0000-4000-8000-000000000201");
    private static final UUID ORGANIZER = UUID.fromString("a1000000-0000-4000-8000-000000000002");
    private static final UUID ENROLLMENT = UUID.fromString("a5000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID KID = UUID.fromString("a4000000-0000-4000-8000-000000000001");
    private static final UUID TICKET = UUID.fromString("a6000000-0000-4000-8000-000000000001");
    private static final UUID CHANGE = UUID.fromString("a9000000-0000-4000-8000-000000000001");
    private static final long UNIT = 16800;

    private final ActivityEnrollmentMapper mapper = mock(ActivityEnrollmentMapper.class);
    private final ActivityChangeMapper changes = mock(ActivityChangeMapper.class);
    private final ActivityPaymentPort payments = mock(ActivityPaymentPort.class);
    private final ActivitySeatsReleased released = mock(ActivitySeatsReleased.class);
    private final ActivityEnrollmentNotifier notifier = mock(ActivityEnrollmentNotifier.class);
    private final ActivityEnrollmentService service = new ActivityEnrollmentService(
            mapper,
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
            () -> "ACT-NEW1-CODE-0001",
            payments,
            TransactionOperations.withoutTransaction(),
            notifier,
            changes,
            released);

    private static SessionRow session(UUID id, UUID activityId, int capacity, int enrolled, OffsetDateTime startsAt) {
        return new SessionRow(id, activityId, "PUBLISHED", "PER_PERSON", 6, 12, true, "ACTIVE", startsAt,
                startsAt.plusHours(3), startsAt.minusDays(1), capacity, enrolled, UNIT, ORGANIZER, "370500",
                "机器人研学体验", null, "上午", false);
    }

    private static EnrollmentRow enrollment(String status, UUID sessionId, OffsetDateTime startsAt) {
        return new EnrollmentRow(ENROLLMENT, status, NOW.minusDays(2), ACTIVITY, "STUDY_TOUR", "机器人研学体验", null,
                "370500", "示例研学机构", "体验馆", "示例路 66 号", null, null, sessionId, "上午", startsAt,
                startsAt.plusHours(3), null, UNIT, UNIT * 2, ORDER, NOW.minusDays(2));
    }

    private static TicketRow ticket(UUID id, String status, String cause) {
        return new TicketRow(id, ENROLLMENT, KID, "小禾", "CHILD", 10, "ACT-OLD1-CODE-0001", status, null,
                "CANCELLED".equals(status) ? NOW : null, cause, null);
    }

    private static ParticipantRow kid(LocalDate birthMonth) {
        return new ParticipantRow(KID, "CHILD", "小禾", birthMonth, false);
    }

    @Nested
    class CancelWithReason {

        @Test
        void aPaidCancellationRecordsItsRefundAndReasonAndOffersTheSeatToTheWaitlist() {
            OffsetDateTime startsAt = NOW.plusDays(5);
            when(mapper.findEnrollment(ACCOUNT, ENROLLMENT)).thenReturn(enrollment("CONFIRMED", FROM, startsAt));
            when(mapper.lockSession(FROM)).thenReturn(session(FROM, ACTIVITY, 30, 30, startsAt));
            when(mapper.findTickets(List.of(ENROLLMENT))).thenReturn(List.of(ticket(TICKET, "ACTIVE", null)));
            when(mapper.cancelTicket(ENROLLMENT, TICKET, NOW)).thenReturn(1);
            UUID refund = UUID.randomUUID();
            when(payments.requestRefund(eq(ORDER), eq(UNIT), anyString(), any(), eq(ACCOUNT))).thenReturn(refund);
            when(changes.insertChange(any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any()))
                    .thenReturn(CHANGE);

            service.cancel(ACCOUNT, ENROLLMENT, List.of(TICKET), "UNWELL");

            InOrder order = inOrder(mapper, changes, released);
            order.verify(mapper).addEnrolled(FROM, -1, NOW);
            order.verify(changes).insertChange(ENROLLMENT, ACCOUNT, "CANCEL", "UNWELL", 1, FROM, null, refund, UNIT, NOW);
            order.verify(changes).markCancelledBy(ENROLLMENT, List.of(TICKET), CHANGE, NOW);
            order.verify(released).released(FROM, NOW);
        }

        @Test
        void anUnknownReasonIsRefusedBeforeAnythingIsRead() {
            assertThatThrownBy(() -> service.cancel(ACCOUNT, ENROLLMENT, null, "BORED"))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(mapper, never()).findEnrollment(any(), any());
        }
    }

    @Nested
    class Reschedule {

        private final OffsetDateTime fromStarts = NOW.plusDays(5);
        private final OffsetDateTime toStarts = NOW.plusDays(12);

        private void ready(int targetCapacity, int targetEnrolled) {
            when(mapper.findEnrollment(ACCOUNT, ENROLLMENT)).thenReturn(enrollment("CONFIRMED", FROM, fromStarts));
            when(mapper.lockSession(FROM)).thenReturn(session(FROM, ACTIVITY, 30, 10, fromStarts));
            when(mapper.lockSession(TO)).thenReturn(session(TO, ACTIVITY, targetCapacity, targetEnrolled, toStarts));
            when(mapper.findTickets(List.of(ENROLLMENT))).thenReturn(List.of(
                    ticket(TICKET, "ACTIVE", null),
                    ticket(UUID.randomUUID(), "CANCELLED", "BUYER")));
            when(mapper.findParticipant(ACCOUNT, KID)).thenReturn(kid(LocalDate.of(2016, 5, 1)));
            when(changes.insertChange(any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any()))
                    .thenReturn(CHANGE);
            when(changes.cancelForReschedule(ENROLLMENT, TICKET, CHANGE, NOW)).thenReturn(1);
            when(changes.moveEnrollment(ENROLLMENT, ACCOUNT, FROM, TO, NOW)).thenReturn(1);
        }

        @Test
        void theWholeSignUpMovesWithNewCodesAndItsSeats() {
            ready(30, 20);

            service.reschedule(ACCOUNT, ENROLLMENT, TO, "PLAN_CHANGED");

            InOrder order = inOrder(payments, mapper, changes, released);
            // 先锁交易订单，再按 ID 顺序锁两个场次（TO 的 ID 小）。
            order.verify(payments).lockOrder(ORDER);
            order.verify(mapper).lockSession(TO);
            order.verify(mapper).lockSession(FROM);
            order.verify(changes).insertChange(ENROLLMENT, ACCOUNT, "RESCHEDULE", "PLAN_CHANGED", 1, FROM, TO, null,
                    null, NOW);
            order.verify(changes).cancelForReschedule(ENROLLMENT, TICKET, CHANGE, NOW);
            order.verify(changes).insertRescheduledTicket(ENROLLMENT, TO, KID, "小禾", "CHILD", 10,
                    "ACT-NEW1-CODE-0001", CHANGE, NOW);
            order.verify(changes).moveEnrollment(ENROLLMENT, ACCOUNT, FROM, TO, NOW);
            order.verify(mapper).addEnrolled(FROM, -1, NOW);
            order.verify(mapper).addEnrolled(TO, 1, NOW);
            order.verify(released).released(FROM, NOW);
            verify(notifier).rescheduled(new ActivityEnrollmentNotifier.Rescheduled(
                    ACCOUNT, ENROLLMENT, CHANGE, "机器人研学体验", toStarts, 1, NOW.toInstant()));
        }

        @Test
        void aFullTargetSessionIsRefused() {
            ready(30, 30);
            assertThatThrownBy(() -> service.reschedule(ACCOUNT, ENROLLMENT, TO, null))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.SESSION_FULL));
            verify(changes, never()).insertChange(any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any());
        }

        @Test
        void anotherActivitysSessionAPendingSignUpAndAStartedOneAreRefused() {
            ready(30, 20);
            when(mapper.lockSession(TO)).thenReturn(session(TO, OTHER_ACTIVITY, 30, 20, toStarts));
            assertRefused();

            ready(30, 20);
            when(mapper.findEnrollment(ACCOUNT, ENROLLMENT)).thenReturn(enrollment("PENDING_PAYMENT", FROM, fromStarts));
            assertRefused();

            ready(30, 20);
            when(mapper.lockSession(FROM)).thenReturn(session(FROM, ACTIVITY, 30, 10, NOW.minusMinutes(1)));
            assertRefused();

            ready(30, 20);
            when(mapper.findTickets(List.of(ENROLLMENT))).thenReturn(List.of(ticket(TICKET, "CHECKED_IN", null)));
            assertRefused();
        }

        @Test
        void aChildTooOldOnTheNewDateIsNotEligible() {
            ready(30, 20);
            // 新场次当天满 13 岁，超过 12 岁上限。
            when(mapper.findParticipant(ACCOUNT, KID)).thenReturn(kid(NOW.minusYears(13).toLocalDate().withDayOfMonth(1)));
            assertThatThrownBy(() -> service.reschedule(ACCOUNT, ENROLLMENT, TO, null))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.PARTICIPANT_NOT_ELIGIBLE));
        }

        @Test
        void movingToTheSessionItIsAlreadyInChangesNothing() {
            when(mapper.findEnrollment(ACCOUNT, ENROLLMENT)).thenReturn(enrollment("CONFIRMED", TO, toStarts));
            when(mapper.findTickets(List.of(ENROLLMENT))).thenReturn(List.of(ticket(TICKET, "ACTIVE", null)));
            service.reschedule(ACCOUNT, ENROLLMENT, TO, null);
            verify(mapper, never()).lockSession(any());
        }

        private void assertRefused() {
            assertThatThrownBy(() -> service.reschedule(ACCOUNT, ENROLLMENT, TO, null))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.RESCHEDULE_NOT_ALLOWED));
        }
    }

    @Nested
    class Team {

        private final OffsetDateTime startsAt = NOW.plusDays(5);
        private final UUID memberA = UUID.fromString("a4000000-0000-4000-8000-0000000000a1");
        private final UUID memberB = UUID.fromString("a4000000-0000-4000-8000-0000000000a2");

        private TeamEnrollCommand command(List<TeamMemberInput> members, String phone) {
            return new TeamEnrollCommand(ACTIVITY, FROM, "东营港务物流职工子女队", "王女士", phone, members, true, true,
                    UNIT * members.size());
        }

        private List<TeamMemberInput> two() {
            return List.of(new TeamMemberInput(null, "李明轩", "2014-06"), new TeamMemberInput(null, "张雨桐", "2016-02"));
        }

        @Test
        void typedInMembersBecomeTheLeadersMembersAndTheSignUpIsATeam() {
            when(mapper.lockSession(FROM)).thenReturn(session(FROM, ACTIVITY, 30, 10, startsAt));
            when(payments.merchantCanCollect(ORGANIZER)).thenReturn(true);
            when(changes.insertMember(eq(ACCOUNT), eq("李明轩"), any(), any())).thenReturn(memberA);
            when(changes.findParticipantByIdentity(eq(ACCOUNT), eq("张雨桐"), any())).thenReturn(memberB);
            when(mapper.findParticipant(ACCOUNT, memberA))
                    .thenReturn(new ParticipantRow(memberA, "MEMBER", "李明轩", LocalDate.of(2014, 6, 1), false));
            when(mapper.findParticipant(ACCOUNT, memberB))
                    .thenReturn(new ParticipantRow(memberB, "CHILD", "张雨桐", LocalDate.of(2016, 2, 1), false));
            when(payments.open(any())).thenReturn(new ActivityPaymentPort.OpenedPayment(ORDER, NOW.plusMinutes(15).toInstant()));
            when(mapper.findEnrollment(eq(ACCOUNT), any())).thenReturn(enrollment("PENDING_PAYMENT", FROM, startsAt));

            service.enrollTeam(ACCOUNT, UUID.randomUUID(), command(two(), "13800002026"));

            verify(mapper).insertTicket(any(), eq(FROM), eq(memberA), eq("李明轩"), eq("MEMBER"), eq(12),
                    anyString(), eq("PENDING_PAYMENT"), eq(NOW));
            verify(mapper).addEnrolled(FROM, 2, NOW);
            verify(changes).markTeam(any(), eq(ACCOUNT));
            verify(changes).insertTeam(any(), eq("东营港务物流职工子女队"), eq("王女士"), eq("13800002026"), eq(NOW));
        }

        @Test
        void sizePhoneAndConsentAreCheckedFirst() {
            assertThatThrownBy(() -> service.enrollTeam(ACCOUNT, UUID.randomUUID(),
                    command(List.of(new TeamMemberInput(null, "李明轩", "2014-06")), "13800002026")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.enrollTeam(ACCOUNT, UUID.randomUUID(), command(two(), "2026")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.enrollTeam(ACCOUNT, UUID.randomUUID(), new TeamEnrollCommand(
                    ACTIVITY, FROM, "队", "王女士", "13800002026", two(), true, false, UNIT * 2)))
                    .isInstanceOf(IllegalArgumentException.class);
            List<TeamMemberInput> many = java.util.stream.IntStream.range(0, 21)
                    .mapToObj(index -> new TeamMemberInput(null, "队员" + index, "2014-06"))
                    .toList();
            assertThatThrownBy(() -> service.enrollTeam(ACCOUNT, UUID.randomUUID(), command(many, "13800002026")))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(mapper, never()).lockSession(any());
        }

        @Test
        void theSameMemberTwiceIsADuplicateAndAFullSessionHasNoWaitlistForTeams() {
            when(mapper.lockSession(FROM)).thenReturn(session(FROM, ACTIVITY, 30, 10, startsAt));
            when(payments.merchantCanCollect(ORGANIZER)).thenReturn(true);
            when(mapper.findParticipant(ACCOUNT, KID)).thenReturn(kid(LocalDate.of(2016, 5, 1)));
            List<TeamMemberInput> twice = List.of(new TeamMemberInput(KID, null, null), new TeamMemberInput(KID, null, null));
            assertThatThrownBy(() -> service.enrollTeam(ACCOUNT, UUID.randomUUID(), command(twice, "13800002026")))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.PARTICIPANT_DUPLICATE));

            when(mapper.lockSession(FROM)).thenReturn(session(FROM, ACTIVITY, 30, 29, startsAt));
            when(changes.findParticipantByIdentity(eq(ACCOUNT), anyString(), any())).thenReturn(null);
            when(changes.insertMember(eq(ACCOUNT), eq("李明轩"), any(), any())).thenReturn(memberA);
            when(changes.insertMember(eq(ACCOUNT), eq("张雨桐"), any(), any())).thenReturn(memberB);
            when(mapper.findParticipant(ACCOUNT, memberA))
                    .thenReturn(new ParticipantRow(memberA, "MEMBER", "李明轩", LocalDate.of(2014, 6, 1), false));
            when(mapper.findParticipant(ACCOUNT, memberB))
                    .thenReturn(new ParticipantRow(memberB, "MEMBER", "张雨桐", LocalDate.of(2016, 2, 1), false));
            assertThatThrownBy(() -> service.enrollTeam(ACCOUNT, UUID.randomUUID(), command(two(), "13800002026")))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.SESSION_FULL));
            verify(payments, never()).open(any());
        }

        @Test
        void theRosterCheckFlagsAgesDuplicatesAndSignUpsWithoutWriting() {
            when(mapper.findSession(FROM)).thenReturn(session(FROM, ACTIVITY, 30, 10, startsAt));
            when(mapper.findEnrolledParticipantIds(FROM, ACCOUNT)).thenReturn(List.of(KID));
            when(mapper.findParticipant(ACCOUNT, KID)).thenReturn(kid(LocalDate.of(2016, 5, 1)));

            TeamCheckView check = service.checkTeam(ACCOUNT, FROM, List.of(
                    new TeamMemberInput(null, "李明轩", "2014-06"),
                    new TeamMemberInput(null, "王小满", "2021-01"),
                    new TeamMemberInput(KID, null, null),
                    new TeamMemberInput(null, "李明轩", "2014-06")));

            assertThat(check.eligibleCount()).isEqualTo(1);
            assertThat(check.members()).extracting(TeamMemberCheckView::reason).containsExactly(
                    TeamMemberReason.ELIGIBLE, TeamMemberReason.UNDER_MIN_AGE, TeamMemberReason.ALREADY_ENROLLED,
                    TeamMemberReason.DUPLICATED);
            assertThat(check.members().get(0).ageAtSession()).isEqualTo(12);
            verify(changes, never()).insertMember(any(), any(), any(), any());
        }
    }

    @Nested
    class Views {

        @Test
        void aTeamSignUpReadsWithItsTeamTheMaskedPhoneAndWithoutTheVoidedTickets() {
            OffsetDateTime startsAt = NOW.plusDays(5);
            when(mapper.findEnrollment(ACCOUNT, ENROLLMENT)).thenReturn(enrollment("CONFIRMED", TO, startsAt));
            when(mapper.findTickets(List.of(ENROLLMENT))).thenReturn(List.of(
                    ticket(UUID.randomUUID(), "CANCELLED", "RESCHEDULED"),
                    ticket(TICKET, "ACTIVE", null)));
            when(changes.findExtras(ACCOUNT, List.of(ENROLLMENT))).thenReturn(List.of(new EnrollmentExtrasRow(
                    ENROLLMENT, "TEAM", "东营港务物流职工子女队", "王女士", "13800002026", NOW.minusHours(1),
                    FROM, "上午", NOW.plusDays(2), null)));
            when(changes.findResults(anyList())).thenReturn(List.of());
            when(changes.findChanges(ACCOUNT, List.of(ENROLLMENT))).thenReturn(List.of());
            when(payments.refundStates(anyList())).thenReturn(Map.of());

            EnrollmentView view = service.readEnrollment(ACCOUNT, ENROLLMENT);

            assertThat(view.kind()).isEqualTo(EnrollmentKind.TEAM);
            assertThat(view.team().leaderPhoneMasked()).isEqualTo("138****2026");
            assertThat(view.tickets()).extracting(TicketView::ticketId).containsExactly(TICKET);
            assertThat(view.reschedule().previousSession().sessionId()).isEqualTo(FROM);
            assertThat(view.reschedulable()).isTrue();
        }

        @Test
        void phonesAreMaskedOnlyWhenTheyAreElevenDigits() {
            assertThat(ActivityEnrollmentService.maskPhone("13800002026")).isEqualTo("138****2026");
            assertThat(ActivityEnrollmentService.maskPhone("2026")).isNull();
            assertThat(ActivityEnrollmentService.maskPhone(null)).isNull();
        }
    }
}
