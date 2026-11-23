package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.ParticipantRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper.SessionRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper.MemberRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper.QueueRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper.SessionSeatsRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper.WaitlistLockRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper.WaitlistRow;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 候补（S5-3）：转正的顺序与保留时间、加入的规则、状态怎么读、放弃和确认。mapper 用 Mockito 桩出 SQL 的结果；
 * SQL 本身由 ActivityWaitlistMapperContractTest 检查，整条链路（真实数据库、交易模块、定时任务）由
 * infra/scripts/trade-smoke/activity-waitlist.cjs 覆盖。
 */
class ActivityWaitlistTest {

    /** 北京时间 2026-11-23 10:00。 */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-23T02:00:00Z");
    private static final OffsetDateTime STARTS = OffsetDateTime.parse("2026-11-29T06:00:00Z");
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000005");
    private static final UUID SESSION = UUID.fromString("a3000000-0000-4000-8000-000000000503");
    private static final UUID ORGANIZER = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID KID = UUID.fromString("a4000000-0000-4000-8000-000000000001");
    private static final UUID W1 = UUID.fromString("a8000000-0000-4000-8000-000000000001");
    private static final UUID W2 = UUID.fromString("a8000000-0000-4000-8000-000000000002");
    private static final UUID W3 = UUID.fromString("a8000000-0000-4000-8000-000000000003");

    @Nested
    class Promotion {

        private final ActivityWaitlistMapper mapper = mock(ActivityWaitlistMapper.class);
        private final ActivityEnrollmentNotifier notifier = mock(ActivityEnrollmentNotifier.class);
        private final ActivityWaitlistQueue queue = new ActivityWaitlistQueue(mapper, notifier);

        private void seats(int capacity, int enrolled, boolean open, OffsetDateTime startsAt) {
            when(mapper.findSessionSeats(SESSION)).thenReturn(
                    new SessionSeatsRow(SESSION, capacity, enrolled, startsAt, open, "无人机开放体验日", "FREE", null));
        }

        @Test
        void inQueueOrderEveryWaitlistThatFitsIsOfferedAndTheBigFrontOneKeepsWaiting() {
            // 放回 2 个名额：最前面要 3 个放不下，后面 1 个、1 个依次转正。
            seats(60, 58, true, STARTS);
            when(mapper.findQueue(SESSION)).thenReturn(List.of(
                    new QueueRow(W1, ACCOUNT, 3, 1),
                    new QueueRow(W2, ACCOUNT, 1, 2),
                    new QueueRow(W3, ACCOUNT, 1, 3)));
            when(mapper.offer(any(), any(), any())).thenReturn(1);

            assertThat(queue.released(SESSION, NOW)).isEqualTo(2);

            verify(mapper, never()).offer(eq(W1), any(), any());
            InOrder order = inOrder(mapper);
            order.verify(mapper).offer(W2, NOW, NOW.plusMinutes(15));
            order.verify(mapper).addEnrolled(SESSION, 1, NOW);
            order.verify(mapper).offer(W3, NOW, NOW.plusMinutes(15));
            order.verify(mapper).addEnrolled(SESSION, 1, NOW);
            verify(notifier).waitlistOffered(new ActivityEnrollmentNotifier.WaitlistOffered(
                    ACCOUNT, W2, "无人机开放体验日", STARTS, 1, false, NOW.plusMinutes(15), NOW.toInstant()));
        }

        @Test
        void theHoldNeverRunsPastTheStartOfTheSession() {
            OffsetDateTime soon = NOW.plusMinutes(5);
            seats(10, 9, true, soon);
            when(mapper.findQueue(SESSION)).thenReturn(List.of(new QueueRow(W1, ACCOUNT, 1, 1)));
            when(mapper.offer(any(), any(), any())).thenReturn(1);

            queue.released(SESSION, NOW);

            verify(mapper).offer(W1, NOW, soon);
        }

        @Test
        void aSessionThatIsOffSaleOrHasStartedOffersNothing() {
            seats(10, 5, false, STARTS);
            assertThat(queue.released(SESSION, NOW)).isZero();
            seats(10, 5, true, NOW.minusMinutes(1));
            assertThat(queue.released(SESSION, NOW)).isZero();
            verify(mapper, never()).findQueue(any());
        }

        @Test
        void anOfferTakenByAnotherTransactionIsSkippedAndAFailingMessageDoesNotUndoTheOffer() {
            seats(10, 8, true, STARTS);
            when(mapper.findQueue(SESSION)).thenReturn(List.of(
                    new QueueRow(W1, ACCOUNT, 1, 1),
                    new QueueRow(W2, ACCOUNT, 1, 2)));
            when(mapper.offer(eq(W1), any(), any())).thenReturn(0);
            when(mapper.offer(eq(W2), any(), any())).thenReturn(1);
            doThrow(new IllegalStateException("inbox down")).when(notifier).waitlistOffered(any());

            assertThat(queue.released(SESSION, NOW)).isEqualTo(1);
            verify(mapper).addEnrolled(SESSION, 1, NOW);
        }
    }

    @Nested
    class Joining {

        private final ActivityEnrollmentMapper enrollments = mock(ActivityEnrollmentMapper.class);
        private final ActivityWaitlistMapper mapper = mock(ActivityWaitlistMapper.class);
        private final ActivityPaymentPort payments = mock(ActivityPaymentPort.class);
        private final ActivityWaitlistService service = new ActivityWaitlistService(
                enrollments, mapper, mock(ActivityEnrollmentService.class), ActivitySeatsReleased.none(), payments,
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        private SessionRow session(int capacity, int enrolled, boolean waitlistAllowed) {
            return new SessionRow(SESSION, ACTIVITY, "PUBLISHED", "FREE", 6, 12, waitlistAllowed, true, "ACTIVE", STARTS,
                    STARTS.plusHours(2), STARTS.minusDays(1), capacity, enrolled, null, ORGANIZER, "370500",
                    "无人机开放体验日", null, "上午", false);
        }

        private JoinWaitlistCommand command() {
            return new JoinWaitlistCommand(ACTIVITY, SESSION, List.of(KID), true);
        }

        @Test
        void onlyAFullSessionThatAllowsAWaitlistCanBeWaitedFor() {
            when(enrollments.lockSession(SESSION)).thenReturn(session(10, 9, true));
            assertThatThrownBy(() -> service.join(ACCOUNT, UUID.randomUUID(), command()))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.WAITLIST_NOT_AVAILABLE));
            when(enrollments.lockSession(SESSION)).thenReturn(session(10, 10, false));
            assertThatThrownBy(() -> service.join(ACCOUNT, UUID.randomUUID(), command()))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.WAITLIST_NOT_AVAILABLE));
            verify(mapper, never()).insertWaitlist(any(), any(), any(), any(), any(), anyInt(), any(), any());
        }

        @Test
        void aChildAlreadyWaitingOrTooYoungOrATeamMemberIsRefused() {
            when(enrollments.lockSession(SESSION)).thenReturn(session(10, 10, true));
            when(enrollments.findParticipant(ACCOUNT, KID))
                    .thenReturn(new ParticipantRow(KID, "CHILD", "小禾", LocalDate.of(2016, 5, 1), false));
            when(mapper.findWaitingParticipantIds(SESSION, ACCOUNT)).thenReturn(List.of(KID));
            assertThatThrownBy(() -> service.join(ACCOUNT, UUID.randomUUID(), command()))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.WAITLIST_ALREADY_JOINED));

            when(mapper.findWaitingParticipantIds(SESSION, ACCOUNT)).thenReturn(List.of());
            when(enrollments.findParticipant(ACCOUNT, KID))
                    .thenReturn(new ParticipantRow(KID, "CHILD", "小禾", LocalDate.of(2022, 5, 1), false));
            assertThatThrownBy(() -> service.join(ACCOUNT, UUID.randomUUID(), command()))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.PARTICIPANT_NOT_ELIGIBLE));

            when(enrollments.findParticipant(ACCOUNT, KID))
                    .thenReturn(new ParticipantRow(KID, "MEMBER", "队员", LocalDate.of(2016, 5, 1), false));
            assertThatThrownBy(() -> service.join(ACCOUNT, UUID.randomUUID(), command()))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.NOT_FOUND));
        }

        @Test
        void aRequestWithoutTheNoticeOrWithRepeatsIsInvalid() {
            assertThatThrownBy(() -> service.join(ACCOUNT, UUID.randomUUID(),
                    new JoinWaitlistCommand(ACTIVITY, SESSION, List.of(KID), false)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.join(ACCOUNT, UUID.randomUUID(),
                    new JoinWaitlistCommand(ACTIVITY, SESSION, List.of(KID, KID), true)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void anExpiredOfferCannotBeConfirmed() {
            when(mapper.findOwnWaitlist(ACCOUNT, W1))
                    .thenReturn(new WaitlistLockRow(W1, ACCOUNT, SESSION, "OFFERED", 1, NOW.minusSeconds(1), null));
            when(mapper.lockWaitlist(W1))
                    .thenReturn(new WaitlistLockRow(W1, ACCOUNT, SESSION, "OFFERED", 1, NOW.minusSeconds(1), null));
            when(enrollments.lockSession(SESSION)).thenReturn(session(10, 10, true));
            assertThatThrownBy(() -> service.confirm(ACCOUNT, W1, null))
                    .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                            e -> assertThat(e.error()).isEqualTo(Error.WAITLIST_STATE_INVALID));
            verify(mapper, never()).markEnrolled(any(), any(), any());
        }

        @Test
        void abandoningAnOfferGivesItsSeatsBackAndOffersThemOn() {
            ActivitySeatsReleased released = mock(ActivitySeatsReleased.class);
            ActivityWaitlistService withQueue = new ActivityWaitlistService(
                    enrollments, mapper, mock(ActivityEnrollmentService.class), released, payments,
                    TransactionOperations.withoutTransaction(), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
            when(mapper.findWaitlist(ACCOUNT, W1)).thenReturn(row("OFFERED", null, NOW.plusMinutes(10)));
            when(mapper.findMembers(List.of(W1))).thenReturn(List.of());
            WaitlistLockRow lock = new WaitlistLockRow(W1, ACCOUNT, SESSION, "OFFERED", 2, NOW.plusMinutes(10), null);
            when(mapper.findOwnWaitlist(ACCOUNT, W1)).thenReturn(lock);
            when(mapper.lockWaitlist(W1)).thenReturn(lock);
            when(mapper.abandon(W1, NOW)).thenReturn(1);

            withQueue.abandon(ACCOUNT, W1);

            InOrder order = inOrder(enrollments, mapper, released);
            order.verify(enrollments).lockSession(SESSION);
            order.verify(mapper).abandon(W1, NOW);
            order.verify(mapper).addEnrolled(SESSION, -2, NOW);
            order.verify(released).released(SESSION, NOW);
        }
    }

    @Nested
    class Reading {

        @Test
        void storedStatesReadAsWhatThePageShows() {
            assertThat(view("WAITING", null, null).status()).isEqualTo(WaitlistStatus.WAITING);
            assertThat(view("WAITING", null, null).position()).isEqualTo(3);
            assertThat(view("WAITING", null, null).ahead()).isEqualTo(2);
            assertThat(view("OFFERED", null, NOW.plusMinutes(10)).status()).isEqualTo(WaitlistStatus.OFFERED);
            assertThat(view("OFFERED", null, NOW.plusMinutes(10)).holdUntil()).isEqualTo(NOW.plusMinutes(10));
            // 还没被定时任务作废，但保留时间已过：读成已超时。
            assertThat(view("OFFERED", null, NOW.minusSeconds(1)).status()).isEqualTo(WaitlistStatus.EXPIRED);
            assertThat(view("ENROLLED", "PENDING_PAYMENT", NOW.plusMinutes(10)).status()).isEqualTo(WaitlistStatus.OFFERED);
            assertThat(view("ENROLLED", "CLOSED", NOW.plusMinutes(10)).status()).isEqualTo(WaitlistStatus.EXPIRED);
            assertThat(view("ENROLLED", "CONFIRMED", NOW.plusMinutes(10)).status()).isEqualTo(WaitlistStatus.ENROLLED);
            assertThat(view("ABANDONED", null, null).abandonable()).isFalse();
        }

        @Test
        void aWaitlistStillQueuedWhenTheSessionStartsReadsAsEnded() {
            WaitlistRow started = new WaitlistRow(W1, ACCOUNT, "WAITING", 5, 2, 12000L, null, null, null, null,
                    NOW.minusDays(1), ACTIVITY, "CONTEST", "挑战赛", null, "370500", "PER_PERSON", 12000L, "主办",
                    "体验馆", SESSION, "下午", NOW.minusMinutes(1), null, null, null, null, null, 2);
            WaitlistView view = ActivityWaitlistService.toView(started, List.of(), NOW);
            assertThat(view.status()).isEqualTo(WaitlistStatus.ENDED);
            assertThat(view.position()).isNull();
            assertThat(view.amountMinor()).isEqualTo(24000L);
        }

        private WaitlistView view(String status, String enrollmentStatus, OffsetDateTime expires) {
            return ActivityWaitlistService.toView(row(status, enrollmentStatus, expires),
                    List.of(new MemberRow(W1, KID, "小禾", "CHILD", 10)), NOW);
        }
    }

    private static WaitlistRow row(String status, String enrollmentStatus, OffsetDateTime expires) {
        UUID enrollment = enrollmentStatus == null ? null : UUID.randomUUID();
        return new WaitlistRow(W1, ACCOUNT, status, 7, 1, null, expires == null ? null : NOW.minusMinutes(5), expires,
                enrollment, null, NOW.minusDays(1), ACTIVITY, "OPEN_DAY", "无人机开放体验日", null, "370500 ", "FREE",
                null, "东营低空与机器人体验馆", "体验馆", SESSION, "上午", STARTS, null, enrollmentStatus,
                null, null, null, 2);
    }
}
