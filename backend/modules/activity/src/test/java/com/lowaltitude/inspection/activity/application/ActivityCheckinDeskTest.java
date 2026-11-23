package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityCheckinDesk.Action;
import com.lowaltitude.inspection.activity.application.ActivityCheckinDesk.Current;
import com.lowaltitude.inspection.activity.application.ActivityCheckinDesk.Entry;
import com.lowaltitude.inspection.activity.application.ActivityCheckinDesk.Kind;
import com.lowaltitude.inspection.activity.application.ActivityCheckinDesk.Method;
import com.lowaltitude.inspection.activity.application.ActivityCheckinDesk.Outcome;
import com.lowaltitude.inspection.activity.application.ActivityCheckinDesk.Phase;
import com.lowaltitude.inspection.activity.application.ActivityCheckinException.Reason;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper.DeskSessionRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper.RecordRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper.SessionLockRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCheckinMapper.TicketRow;
import org.junit.jupiter.api.Test;

/**
 * 核销工作台里活动凭证一半的规则。mapper 用 Mockito 桩出 SQL 的结果，SQL 文本由 ActivityCheckinMapperContractTest 检查。
 */
class ActivityCheckinDeskTest {

    /** 北京时间 2026-11-23 14:05。 */
    private static final Instant NOW = Instant.parse("2026-11-23T06:05:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    /** 14:00 开始、15:00 结束，13:30 开放签到。 */
    private static final OffsetDateTime STARTS = OffsetDateTime.parse("2026-11-23T06:00:00Z");
    private static final OffsetDateTime ENDS = OffsetDateTime.parse("2026-11-23T07:00:00Z");

    private static final UUID OPERATOR = UUID.fromString("a1000000-0000-4000-8000-0000000000a4");
    private static final UUID ORG = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_ORG = UUID.fromString("a1000000-0000-4000-8000-000000000009");
    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000004");
    private static final UUID SESSION = UUID.fromString("a3000000-0000-4000-8000-000000000401");
    private static final UUID LATER_SESSION = UUID.fromString("a3000000-0000-4000-8000-000000000402");
    private static final UUID ENROLLMENT = UUID.fromString("a5000000-0000-4000-8000-000000000001");
    private static final UUID TICKET = UUID.fromString("a6000000-0000-4000-8000-000000000001");
    private static final UUID PARTICIPANT = UUID.fromString("a4000000-0000-4000-8000-000000000001");
    private static final UUID RECORD = UUID.fromString("a7000000-0000-4000-8000-000000000001");
    private static final String CODE = "ACT-7K2M-9QXD-4HTP";

    private final ActivityCheckinMapper mapper = mock(ActivityCheckinMapper.class);
    private final ActivityCheckinDesk desk = new ActivityCheckinDesk(mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    // ---- 判断顺序 ----

    @Test
    void outcomeFollowsTheOrderOfTheContract() {
        Current here = new Current(Kind.ACTIVITY, SESSION);

        assertThat(ActivityCheckinDesk.outcomeOf(ticket("ACTIVE", OTHER_ORG, STARTS, ENDS), ORG, here, AT))
                .isEqualTo(Outcome.OTHER_ORGANIZATION);
        assertThat(ActivityCheckinDesk.outcomeOf(ticket("CANCELLED", ORG, STARTS, ENDS), ORG, here, AT))
                .isEqualTo(Outcome.CANCELLED);
        assertThat(ActivityCheckinDesk.outcomeOf(ticket("PENDING_PAYMENT", ORG, STARTS, ENDS), ORG, here, AT))
                .isEqualTo(Outcome.UNPAID);
        assertThat(ActivityCheckinDesk.outcomeOf(ticket("CHECKED_IN", ORG, STARTS, ENDS), ORG, here, AT))
                .isEqualTo(Outcome.REPEATED);
        // 场次已经结束：先算过期，不管正在核销哪一场。
        assertThat(ActivityCheckinDesk.outcomeOf(
                ticket("ACTIVE", ORG, STARTS.minusDays(1), ENDS.minusDays(1)), ORG, new Current(Kind.VENUE, LATER_SESSION), AT))
                .isEqualTo(Outcome.EXPIRED);
        assertThat(ActivityCheckinDesk.outcomeOf(ticket("ACTIVE", ORG, STARTS, ENDS), ORG,
                new Current(Kind.ACTIVITY, LATER_SESSION), AT)).isEqualTo(Outcome.SESSION_MISMATCH);
        // 同一个 ID 但种类是场馆：也是场次不符。
        assertThat(ActivityCheckinDesk.outcomeOf(ticket("ACTIVE", ORG, STARTS, ENDS), ORG,
                new Current(Kind.VENUE, SESSION), AT)).isEqualTo(Outcome.SESSION_MISMATCH);
        assertThat(ActivityCheckinDesk.outcomeOf(ticket("ACTIVE", ORG, STARTS, ENDS), ORG, here, AT))
                .isEqualTo(Outcome.CHECKED_IN);
        assertThat(ActivityCheckinDesk.outcomeOf(ticket("ACTIVE", ORG, STARTS, ENDS), ORG, null, AT))
                .isEqualTo(Outcome.CHECKED_IN);
        // 承办机构也算本机构；没有结束时间的场次到开始时间为止。
        TicketRow coHosted = new TicketRow(TICKET, ENROLLMENT, SESSION, PARTICIPANT, CODE, "孙小明", "ACTIVE", null,
                ACTIVITY, OTHER_ORG, ORG, AT.minusMinutes(1), null, null, null);
        assertThat(ActivityCheckinDesk.outcomeOf(coHosted, ORG, null, AT)).isEqualTo(Outcome.EXPIRED);
    }

    @Test
    void onlyMismatchAndExpiredWithinTheSameActivityCanBeReleased() {
        assertThat(ActivityCheckinDesk.allowedActions(record("SESSION_MISMATCH", ACTIVITY, null)))
                .containsExactly(Action.RELEASE, Action.REJECT);
        assertThat(ActivityCheckinDesk.allowedActions(record("EXPIRED", ACTIVITY, null)))
                .containsExactly(Action.RELEASE, Action.REJECT);
        // 当前场次是别的活动的：只能不予核销。
        assertThat(ActivityCheckinDesk.allowedActions(record("SESSION_MISMATCH", UUID.randomUUID(), null)))
                .containsExactly(Action.REJECT);
        for (String blocked : List.of("REPEATED", "OTHER_ORGANIZATION", "CANCELLED", "UNPAID")) {
            assertThat(ActivityCheckinDesk.allowedActions(record(blocked, ACTIVITY, null))).as(blocked)
                    .containsExactly(Action.BLOCK);
        }
        assertThat(ActivityCheckinDesk.allowedActions(record("CHECKED_IN", ACTIVITY, null))).isEmpty();
        assertThat(ActivityCheckinDesk.allowedActions(record("SESSION_MISMATCH", ACTIVITY, "REJECT"))).isEmpty();
    }

    @Test
    void holderNamesKeepOnlyTheFirstCharacterAndNotesAreTrimmedAndLimited() {
        assertThat(ActivityCheckinDesk.maskName(" 孙小明 ")).isEqualTo("孙**");
        assertThat(ActivityCheckinDesk.maskName("王")).isEqualTo("王**");
        assertThat(ActivityCheckinDesk.maskName("  ")).isNull();
        assertThat(ActivityCheckinDesk.normalizeNote("  ")).isNull();
        assertThat(ActivityCheckinDesk.normalizeNote(" 现场改签 ")).isEqualTo("现场改签");
        assertThat(ActivityCheckinDesk.normalizeNote("字".repeat(200))).hasSize(200);
        assertThatThrownBy(() -> ActivityCheckinDesk.normalizeNote("字".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ActivityCheckinDesk.normalizeCode(" act-7k2m-9qxd-4htp ")).isEqualTo(CODE);
        assertThatThrownBy(() -> ActivityCheckinDesk.normalizeCode("VEN-7K2M-9QXD-4HTP"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 场次 ----

    @Test
    void sessionsOfTheBeijingDayCarryTheirPhase() {
        LocalDate day = LocalDate.of(2026, 11, 23);
        OffsetDateTime from = OffsetDateTime.parse("2026-11-23T00:00:00+08:00");
        when(mapper.findSessions(ORG, from, from.plusDays(1))).thenReturn(List.of(
                new DeskSessionRow(SESSION, ACTIVITY, "机器人避障闯关", "体验馆", null, STARTS, ENDS, null, 8, 3),
                new DeskSessionRow(LATER_SESSION, ACTIVITY, "机器人避障闯关", "体验馆", "下午场",
                        STARTS.plusHours(2), null, STARTS.plusMinutes(90), 2, 0),
                new DeskSessionRow(UUID.randomUUID(), ACTIVITY, "模拟飞行", "体验馆", null,
                        STARTS.minusHours(4), STARTS.minusHours(3), null, 6, 6)));

        List<ActivityCheckinDesk.DeskSession> sessions = desk.sessions(ORG, day);

        assertThat(sessions).extracting(ActivityCheckinDesk.DeskSession::phase)
                .containsExactly(Phase.OPEN, Phase.UPCOMING, Phase.ENDED);
        // 没设签到开放时间时开始前 1 小时开放；没有结束时间的到开始时间为止。
        assertThat(sessions.get(0).opensAt()).isEqualTo(STARTS.minusHours(1));
        assertThat(sessions.get(1).closesAt()).isEqualTo(STARTS.plusHours(2));
        assertThat(sessions.get(0).expectedPeople()).isEqualTo(8);
        assertThat(sessions.get(0).checkedInPeople()).isEqualTo(3);
    }

    @Test
    void anOperatorWithoutAnOrganizationIsRefused() {
        assertRejected(Reason.FORBIDDEN, () -> desk.records(OPERATOR, null, LocalDate.of(2026, 11, 23)));
        assertRejected(Reason.FORBIDDEN, () -> desk.verify(null, ORG, CODE, Method.SCAN, null));
        assertThat(desk.verifies(null)).isFalse();
    }

    // ---- 核销 ----

    @Test
    void aTicketOfTheCurrentSessionIsCheckedInAndRecorded() {
        TicketRow ticket = new TicketRow(TICKET, ENROLLMENT, SESSION, PARTICIPANT, CODE, "王女士", "ACTIVE", null,
                ACTIVITY, ORG, null, STARTS, ENDS, null, 3900L);
        when(mapper.findOwnSession(ORG, SESSION)).thenReturn(ownSession(SESSION));
        when(mapper.lockTicketByCode(CODE)).thenReturn(ticket);
        when(mapper.insertRecord(any(), any(), any(), any(), anyString(), anyString(), any(), any(), anyString(),
                any(), any(), any())).thenReturn(RECORD);
        when(mapper.findRecord(OPERATOR, ORG, RECORD)).thenReturn(record("CHECKED_IN", ACTIVITY, null));

        Entry entry = desk.verify(OPERATOR, ORG, " act-7k2m-9qxd-4htp ", Method.MANUAL, new Current(Kind.ACTIVITY, SESSION));

        assertThat(entry.outcome()).isEqualTo(Outcome.CHECKED_IN);
        verify(mapper).checkIn(TICKET, OPERATOR, AT);
        verify(mapper).insertCheckedInEvent(ENROLLMENT, TICKET, OPERATOR, AT);
        verify(mapper).insertRecord(ORG, OPERATOR, TICKET, SESSION, "4HTP", "MANUAL", "ACTIVITY", SESSION,
                "CHECKED_IN", 3900L, null, AT);
    }

    @Test
    void aTicketBeforeItsWindowIsRefusedWithoutARecord() {
        TicketRow early = new TicketRow(TICKET, ENROLLMENT, SESSION, PARTICIPANT, CODE, "王女士", "ACTIVE", null,
                ACTIVITY, ORG, null, STARTS.plusHours(3), ENDS.plusHours(3), null, null);
        when(mapper.lockTicketByCode(CODE)).thenReturn(early);

        assertRejected(Reason.NOT_OPEN, () -> desk.verify(OPERATOR, ORG, CODE, Method.SCAN, null));
        verify(mapper, never()).checkIn(any(), any(), any());
        verify(mapper, never()).insertRecord(any(), any(), any(), any(), anyString(), anyString(), any(), any(),
                anyString(), any(), any(), any());
    }

    @Test
    void anUnknownCodeIsNotFoundAndAForeignCurrentSessionIsInvalid() {
        assertRejected(Reason.NOT_FOUND, () -> desk.verify(OPERATOR, ORG, CODE, Method.SCAN, null));
        assertThatThrownBy(() -> desk.verify(OPERATOR, ORG, CODE, Method.SCAN, new Current(Kind.ACTIVITY, SESSION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> desk.verify(OPERATOR, ORG, "ACT-1", Method.SCAN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> desk.verify(OPERATOR, ORG, CODE, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aForeignTicketIsRecordedWithoutItsSessionOrAmount() {
        when(mapper.lockTicketByCode(CODE)).thenReturn(ticket("ACTIVE", OTHER_ORG, STARTS, ENDS));
        when(mapper.insertRecord(any(), any(), any(), any(), anyString(), anyString(), any(), any(), anyString(),
                any(), any(), any())).thenReturn(RECORD);
        when(mapper.findRecord(OPERATOR, ORG, RECORD)).thenReturn(record("OTHER_ORGANIZATION", null, null));

        Entry entry = desk.verify(OPERATOR, ORG, CODE, Method.SCAN, null);

        assertThat(entry.outcome()).isEqualTo(Outcome.OTHER_ORGANIZATION);
        assertThat(entry.holderName()).isNull();
        verify(mapper).insertRecord(ORG, OPERATOR, TICKET, null, "4HTP", "SCAN", null, null,
                "OTHER_ORGANIZATION", null, null, AT);
        verify(mapper, never()).checkIn(any(), any(), any());
    }

    @Test
    void aRepeatedScanOfTheSameRejectionReturnsTheEarlierRecord() {
        OffsetDateTime first = AT.minusMinutes(3);
        TicketRow checked = new TicketRow(TICKET, ENROLLMENT, SESSION, PARTICIPANT, CODE, "李雷", "CHECKED_IN", first,
                ACTIVITY, ORG, null, STARTS, ENDS, null, null);
        when(mapper.lockTicketByCode(CODE)).thenReturn(checked);
        when(mapper.findRecentRejection(OPERATOR, ORG, TICKET, "REPEATED", null, AT.minusMinutes(2)))
                .thenReturn(record("REPEATED", ACTIVITY, null));

        Entry entry = desk.verify(OPERATOR, ORG, CODE, Method.SCAN, null);

        assertThat(entry.outcome()).isEqualTo(Outcome.REPEATED);
        verify(mapper, never()).insertRecord(any(), any(), any(), any(), anyString(), anyString(), any(), any(),
                anyString(), any(), any(), any());
    }

    @Test
    void aRepeatedTicketKeepsItsFirstCheckInTime() {
        OffsetDateTime first = AT.minusMinutes(3);
        when(mapper.lockTicketByCode(CODE)).thenReturn(new TicketRow(TICKET, ENROLLMENT, SESSION, PARTICIPANT, CODE,
                "李雷", "CHECKED_IN", first, ACTIVITY, ORG, null, STARTS, ENDS, null, null));
        when(mapper.insertRecord(any(), any(), any(), any(), anyString(), anyString(), any(), any(), anyString(),
                any(), any(), any())).thenReturn(RECORD);
        when(mapper.findRecord(OPERATOR, ORG, RECORD)).thenReturn(record("REPEATED", ACTIVITY, null));

        desk.verify(OPERATOR, ORG, CODE, Method.SCAN, null);

        // 免费报名的金额记 0。
        verify(mapper).insertRecord(ORG, OPERATOR, TICKET, SESSION, "4HTP", "SCAN", null, null, "REPEATED", 0L, first, AT);
    }

    // ---- 人工处理 ----

    @Test
    void theSameResolutionTwiceReturnsTheFirstAndAnotherOneIsRefused() {
        when(mapper.lockRecord(OPERATOR, ORG, RECORD)).thenReturn(record("SESSION_MISMATCH", ACTIVITY, "REJECT"));

        Entry again = desk.resolve(OPERATOR, ORG, RECORD, Action.REJECT, false, null);

        assertThat(again.resolution().action()).isEqualTo(Action.REJECT);
        assertRejected(Reason.ALREADY_RESOLVED, () -> desk.resolve(OPERATOR, ORG, RECORD, Action.RELEASE, true, null));
        verify(mapper, never()).resolveRecord(any(), any(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void anActionOutsideTheAllowedOnesIsRefused() {
        when(mapper.lockRecord(OPERATOR, ORG, RECORD)).thenReturn(record("REPEATED", ACTIVITY, null));

        assertRejected(Reason.ACTION_NOT_ALLOWED, () -> desk.resolve(OPERATOR, ORG, RECORD, Action.REJECT, false, null));
        assertThatThrownBy(() -> desk.resolve(OPERATOR, ORG, RECORD, Action.RELEASE, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertRejected(Reason.NOT_FOUND, () -> desk.resolve(OPERATOR, ORG, UUID.randomUUID(), Action.BLOCK, false, null));
    }

    @Test
    void blockingKeepsTheNoteAndTouchesNoTicket() {
        when(mapper.lockRecord(OPERATOR, ORG, RECORD)).thenReturn(record("REPEATED", ACTIVITY, null));
        when(mapper.resolveRecord(OPERATOR, RECORD, "BLOCK", false, "已提醒", null, AT)).thenReturn(1);
        when(mapper.findRecord(OPERATOR, ORG, RECORD)).thenReturn(record("REPEATED", ACTIVITY, "BLOCK"));

        desk.resolve(OPERATOR, ORG, RECORD, Action.BLOCK, false, " 已提醒 ");

        verify(mapper).resolveRecord(OPERATOR, RECORD, "BLOCK", false, "已提醒", null, AT);
        verify(mapper, never()).moveAndCheckIn(any(), any(), any(), any());
    }

    @Test
    void releasingMovesTheTicketIntoTheCurrentSessionAndChecksItIn() {
        RecordRow mismatch = record("SESSION_MISMATCH", ACTIVITY, null);
        when(mapper.lockRecord(OPERATOR, ORG, RECORD)).thenReturn(mismatch);
        // 凭证属于稍后的场次，当前正在核销 SESSION（14:00 场，有余位）。
        when(mapper.lockSession(SESSION)).thenReturn(lock(SESSION, STARTS, ENDS, 8, 7));
        when(mapper.lockSession(LATER_SESSION)).thenReturn(lock(LATER_SESSION, STARTS.plusHours(2), ENDS.plusHours(2), 2, 2));
        when(mapper.lockTicket(TICKET)).thenReturn(new TicketRow(TICKET, ENROLLMENT, LATER_SESSION, PARTICIPANT, CODE,
                "孙小明", "ACTIVE", null, ACTIVITY, ORG, null, STARTS.plusHours(2), ENDS.plusHours(2), null, null));
        when(mapper.resolveRecord(OPERATOR, RECORD, "RELEASE", true, "本场有余位", SESSION, AT)).thenReturn(1);
        when(mapper.findRecord(OPERATOR, ORG, RECORD)).thenReturn(record("SESSION_MISMATCH", ACTIVITY, "RELEASE"));

        desk.resolve(OPERATOR, ORG, RECORD, Action.RELEASE, true, "本场有余位");

        verify(mapper).moveAndCheckIn(TICKET, SESSION, OPERATOR, AT);
        verify(mapper).addEnrolled(LATER_SESSION, -1, AT);
        verify(mapper).addEnrolled(SESSION, 1, AT);
        verify(mapper).insertCheckedInEvent(ENROLLMENT, TICKET, OPERATOR, AT);
    }

    @Test
    void releasingNeedsAFreeSeatAnOpenSessionAndAnUnchangedTicket() {
        when(mapper.lockRecord(OPERATOR, ORG, RECORD)).thenReturn(record("SESSION_MISMATCH", ACTIVITY, null));
        when(mapper.lockSession(LATER_SESSION)).thenReturn(lock(LATER_SESSION, STARTS.plusHours(2), ENDS.plusHours(2), 2, 2));
        TicketRow active = new TicketRow(TICKET, ENROLLMENT, LATER_SESSION, PARTICIPANT, CODE, "孙小明", "ACTIVE", null,
                ACTIVITY, ORG, null, STARTS.plusHours(2), ENDS.plusHours(2), null, null);
        when(mapper.lockTicket(TICKET)).thenReturn(active);

        when(mapper.lockSession(SESSION)).thenReturn(lock(SESSION, STARTS, ENDS, 8, 8));
        assertRejected(Reason.SEATS_NOT_ENOUGH, () -> desk.resolve(OPERATOR, ORG, RECORD, Action.RELEASE, true, null));

        when(mapper.lockSession(SESSION)).thenReturn(lock(SESSION, STARTS.minusHours(3), ENDS.minusHours(3), 8, 1));
        assertRejected(Reason.RELEASE_NOT_ALLOWED, () -> desk.resolve(OPERATOR, ORG, RECORD, Action.RELEASE, true, null));

        when(mapper.lockSession(SESSION)).thenReturn(lock(SESSION, STARTS, ENDS, 8, 1));
        when(mapper.countOtherTickets(SESSION, PARTICIPANT, TICKET)).thenReturn(1);
        assertRejected(Reason.RELEASE_NOT_ALLOWED, () -> desk.resolve(OPERATOR, ORG, RECORD, Action.RELEASE, true, null));

        when(mapper.countOtherTickets(SESSION, PARTICIPANT, TICKET)).thenReturn(0);
        when(mapper.lockTicket(TICKET)).thenReturn(new TicketRow(TICKET, ENROLLMENT, LATER_SESSION, PARTICIPANT, CODE,
                "孙小明", "CANCELLED", null, ACTIVITY, ORG, null, STARTS.plusHours(2), ENDS.plusHours(2), null, null));
        assertRejected(Reason.RELEASE_NOT_ALLOWED, () -> desk.resolve(OPERATOR, ORG, RECORD, Action.RELEASE, true, null));

        verify(mapper, never()).moveAndCheckIn(any(), any(), any(), any());
        verify(mapper, never()).addEnrolled(any(), anyInt(), any());
        verify(mapper, never()).resolveRecord(any(), any(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void entriesCarryTheMaskedHolderAndTheResolution() {
        when(mapper.findRecords(eq(OPERATOR), eq(ORG), any(), any()))
                .thenReturn(List.of(record("SESSION_MISMATCH", ACTIVITY, "RELEASE")));

        Entry entry = desk.records(OPERATOR, ORG, LocalDate.of(2026, 11, 23)).get(0);

        assertThat(entry.holderName()).isEqualTo("孙**");
        assertThat(entry.codeTail()).isEqualTo("0815");
        assertThat(entry.ticketSession().sessionId()).isEqualTo(LATER_SESSION);
        assertThat(entry.currentKind()).isEqualTo(Kind.ACTIVITY);
        assertThat(entry.currentSession().title()).isEqualTo("机器人避障闯关");
        assertThat(entry.allowedActions()).isEmpty();
        assertThat(entry.resolution().action()).isEqualTo(Action.RELEASE);
        assertThat(entry.resolution().admittedSession().sessionId()).isEqualTo(SESSION);
    }

    // ---- 帮手 ----

    private static TicketRow ticket(String status, UUID organizer, OffsetDateTime starts, OffsetDateTime ends) {
        return new TicketRow(TICKET, ENROLLMENT, SESSION, PARTICIPANT, CODE, "孙小明", status,
                "CHECKED_IN".equals(status) ? AT.minusMinutes(3) : null,
                ACTIVITY, organizer, null, starts, ends, null, null);
    }

    private static DeskSessionRow ownSession(UUID sessionId) {
        return new DeskSessionRow(sessionId, ACTIVITY, "机器人避障闯关", "体验馆", null, STARTS, ENDS, null, 0, 0);
    }

    private static SessionLockRow lock(UUID id, OffsetDateTime starts, OffsetDateTime ends, int capacity, int enrolled) {
        return new SessionLockRow(id, ACTIVITY, "ACTIVE", starts, ends, null, capacity, enrolled);
    }

    /** 凭证属于 LATER_SESSION，工作台正在核销 SESSION（当前活动是 currentActivity）。 */
    private static RecordRow record(String outcome, UUID currentActivity, String resolution) {
        boolean foreign = "OTHER_ORGANIZATION".equals(outcome);
        boolean released = "RELEASE".equals(resolution);
        return new RecordRow(
                RECORD,
                TICKET,
                outcome,
                "SCAN",
                AT,
                "0815",
                foreign ? null : "孙小明",
                1,
                foreign ? null : 0L,
                foreign ? null : LATER_SESSION,
                foreign ? null : ACTIVITY,
                foreign ? null : "机器人避障闯关",
                null,
                foreign ? null : STARTS.plusHours(2),
                "ACTIVITY",
                SESSION,
                currentActivity,
                "机器人避障闯关",
                null,
                STARTS,
                "REPEATED".equals(outcome) ? AT.minusMinutes(3) : null,
                resolution,
                released,
                resolution == null ? null : "现场处理",
                resolution == null ? null : AT,
                released ? SESSION : null,
                released ? "机器人避障闯关" : null,
                null,
                released ? STARTS : null);
    }

    private static void assertRejected(Reason reason, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ActivityCheckinException.class, error -> assertThat(error.reason()).isEqualTo(reason));
    }
}
