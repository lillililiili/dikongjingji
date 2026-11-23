package com.lowaltitude.inspection.experience.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.lowaltitude.inspection.experience.application.VenueCheckinDesk.Action;
import com.lowaltitude.inspection.experience.application.VenueCheckinDesk.Current;
import com.lowaltitude.inspection.experience.application.VenueCheckinDesk.Entry;
import com.lowaltitude.inspection.experience.application.VenueCheckinDesk.Kind;
import com.lowaltitude.inspection.experience.application.VenueCheckinDesk.Method;
import com.lowaltitude.inspection.experience.application.VenueCheckinDesk.Outcome;
import com.lowaltitude.inspection.experience.application.VenueCheckinDesk.Phase;
import com.lowaltitude.inspection.experience.application.VenueCheckinException.Reason;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper.BookingRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper.DeskSessionRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper.RecordRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueCheckinMapper.SessionLockRow;
import org.junit.jupiter.api.Test;

/** 核销工作台里到馆预约一半的规则。mapper 用 Mockito 桩出 SQL 的结果，SQL 文本由 VenueCheckinMapperContractTest 检查。 */
class VenueCheckinDeskTest {

    /** 北京时间 2026-11-23 14:05。 */
    private static final Instant NOW = Instant.parse("2026-11-23T06:05:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final OffsetDateTime STARTS = OffsetDateTime.parse("2026-11-23T06:00:00Z");
    private static final OffsetDateTime ENDS = OffsetDateTime.parse("2026-11-23T07:00:00Z");

    private static final UUID OPERATOR = UUID.fromString("a1000000-0000-4000-8000-0000000000a4");
    private static final UUID ORG = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_ORG = UUID.fromString("a1000000-0000-4000-8000-000000000009");
    private static final UUID PROJECT = UUID.fromString("b4000000-0000-4000-8000-000000000101");
    private static final UUID SESSION = UUID.fromString("b6000000-0000-4000-8000-000000000001");
    private static final UUID LATER_SESSION = UUID.fromString("b6000000-0000-4000-8000-000000000002");
    private static final UUID BOOKING = UUID.fromString("b7000000-0000-4000-8000-000000000001");
    private static final UUID RECORD = UUID.fromString("b8000000-0000-4000-8000-000000000001");
    private static final String CODE = "VEN-7K2M-9QXD-0314";

    private final VenueCheckinMapper mapper = mock(VenueCheckinMapper.class);
    private final VenueCheckinDesk desk = new VenueCheckinDesk(mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void outcomeFollowsTheOrderOfTheContract() {
        Current here = new Current(Kind.VENUE, SESSION);

        assertThat(VenueCheckinDesk.outcomeOf(booking("CONFIRMED", OTHER_ORG, STARTS, ENDS), ORG, here, AT))
                .isEqualTo(Outcome.OTHER_ORGANIZATION);
        assertThat(VenueCheckinDesk.outcomeOf(booking("CANCELLED", ORG, STARTS, ENDS), ORG, here, AT))
                .isEqualTo(Outcome.CANCELLED);
        assertThat(VenueCheckinDesk.outcomeOf(booking("CHECKED_IN", ORG, STARTS, ENDS), ORG, here, AT))
                .isEqualTo(Outcome.REPEATED);
        assertThat(VenueCheckinDesk.outcomeOf(booking("CONFIRMED", ORG, STARTS.minusDays(1), ENDS.minusDays(1)),
                ORG, here, AT)).isEqualTo(Outcome.EXPIRED);
        assertThat(VenueCheckinDesk.outcomeOf(booking("CONFIRMED", ORG, STARTS, ENDS), ORG,
                new Current(Kind.ACTIVITY, SESSION), AT)).isEqualTo(Outcome.SESSION_MISMATCH);
        assertThat(VenueCheckinDesk.outcomeOf(booking("CONFIRMED", ORG, STARTS, ENDS), ORG, here, AT))
                .isEqualTo(Outcome.CHECKED_IN);
    }

    @Test
    void onlyMismatchAndExpiredWithinTheSameProjectCanBeReleased() {
        assertThat(VenueCheckinDesk.allowedActions(record("SESSION_MISMATCH", PROJECT, null)))
                .containsExactly(Action.RELEASE, Action.REJECT);
        assertThat(VenueCheckinDesk.allowedActions(record("EXPIRED", UUID.randomUUID(), null)))
                .containsExactly(Action.REJECT);
        assertThat(VenueCheckinDesk.allowedActions(record("CANCELLED", PROJECT, null))).containsExactly(Action.BLOCK);
        assertThat(VenueCheckinDesk.allowedActions(record("CHECKED_IN", PROJECT, null))).isEmpty();
    }

    @Test
    void sessionsOpenHalfAnHourBeforeTheStart() {
        OffsetDateTime from = OffsetDateTime.parse("2026-11-23T00:00:00+08:00");
        when(mapper.findSessions(ORG, from, from.plusDays(1))).thenReturn(List.of(
                new DeskSessionRow(SESSION, PROJECT, "机器人避障闯关", "体验馆", STARTS, ENDS, 8, 4),
                new DeskSessionRow(LATER_SESSION, PROJECT, "机器人避障闯关", "体验馆", STARTS.plusMinutes(40),
                        ENDS.plusMinutes(40), 2, 0)));

        List<VenueCheckinDesk.DeskSession> sessions = desk.sessions(ORG, LocalDate.of(2026, 11, 23));

        assertThat(sessions).extracting(VenueCheckinDesk.DeskSession::phase).containsExactly(Phase.OPEN, Phase.UPCOMING);
        assertThat(sessions.get(1).opensAt()).isEqualTo(STARTS.plusMinutes(10));
    }

    @Test
    void aBookingOfTheCurrentSessionIsCheckedInAsAWholeAndRecorded() {
        when(mapper.findOwnSession(ORG, SESSION)).thenReturn(
                new DeskSessionRow(SESSION, PROJECT, "机器人避障闯关", "体验馆", STARTS, ENDS, 0, 0));
        when(mapper.lockBookingByCode(CODE)).thenReturn(booking("CONFIRMED", ORG, STARTS, ENDS));
        when(mapper.insertRecord(any(), any(), any(), any(), anyString(), anyString(), any(), any(), anyString(),
                anyInt(), any(), any(), any())).thenReturn(RECORD);
        when(mapper.findRecord(OPERATOR, ORG, RECORD)).thenReturn(record("CHECKED_IN", PROJECT, null));

        Entry entry = desk.verify(OPERATOR, ORG, CODE, Method.SCAN, new Current(Kind.VENUE, SESSION));

        assertThat(entry.outcome()).isEqualTo(Outcome.CHECKED_IN);
        verify(mapper).checkIn(BOOKING, OPERATOR, AT);
        verify(mapper).insertEvent(BOOKING, "CHECKED_IN", OPERATOR, null, null, AT);
        verify(mapper).insertRecord(ORG, OPERATOR, BOOKING, SESSION, "0314", "SCAN", "VENUE", SESSION,
                "CHECKED_IN", 2, 7800L, null, AT);
    }

    @Test
    void earlyCodesUnknownCodesAndForeignSessionsAreRefusedWithoutARecord() {
        when(mapper.lockBookingByCode(CODE)).thenReturn(booking("CONFIRMED", ORG, STARTS.plusHours(2), ENDS.plusHours(2)));
        assertRejected(Reason.NOT_OPEN, () -> desk.verify(OPERATOR, ORG, CODE, Method.SCAN, null));

        when(mapper.lockBookingByCode(CODE)).thenReturn(null);
        assertRejected(Reason.NOT_FOUND, () -> desk.verify(OPERATOR, ORG, CODE, Method.SCAN, null));
        assertThatThrownBy(() -> desk.verify(OPERATOR, ORG, CODE, Method.SCAN, new Current(Kind.VENUE, SESSION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> desk.verify(OPERATOR, ORG, "ACT-7K2M-9QXD-0314", Method.SCAN, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertRecord(any(), any(), any(), any(), anyString(), anyString(), any(), any(),
                anyString(), anyInt(), any(), any(), any());
    }

    @Test
    void aForeignCodeIsRecordedAsOnePersonWithoutSessionOrAmount() {
        when(mapper.lockBookingByCode(CODE)).thenReturn(booking("CONFIRMED", OTHER_ORG, STARTS, ENDS));
        when(mapper.insertRecord(any(), any(), any(), any(), anyString(), anyString(), any(), any(), anyString(),
                anyInt(), any(), any(), any())).thenReturn(RECORD);
        when(mapper.findRecord(OPERATOR, ORG, RECORD)).thenReturn(record("OTHER_ORGANIZATION", null, null));

        desk.verify(OPERATOR, ORG, CODE, Method.MANUAL, null);

        verify(mapper).insertRecord(ORG, OPERATOR, BOOKING, null, "0314", "MANUAL", null, null,
                "OTHER_ORGANIZATION", 1, null, null, AT);
    }

    @Test
    void releasingMovesTheWholeBookingAndKeepsItsCode() {
        when(mapper.lockRecord(OPERATOR, ORG, RECORD)).thenReturn(record("SESSION_MISMATCH", PROJECT, null));
        when(mapper.lockSession(SESSION)).thenReturn(new SessionLockRow(SESSION, PROJECT, "ACTIVE", STARTS, ENDS, 8, 6));
        when(mapper.lockSession(LATER_SESSION)).thenReturn(
                new SessionLockRow(LATER_SESSION, PROJECT, "ACTIVE", STARTS.plusHours(2), ENDS.plusHours(2), 8, 2));
        when(mapper.lockBooking(BOOKING)).thenReturn(new BookingRow(BOOKING, LATER_SESSION, PROJECT, CODE, "CONFIRMED",
                2, 7800L, null, ORG, STARTS.plusHours(2), ENDS.plusHours(2)));
        when(mapper.resolveRecord(OPERATOR, RECORD, "RELEASE", true, null, SESSION, AT)).thenReturn(1);
        when(mapper.findRecord(OPERATOR, ORG, RECORD)).thenReturn(record("SESSION_MISMATCH", PROJECT, "RELEASE"));

        desk.resolve(OPERATOR, ORG, RECORD, Action.RELEASE, true, " ");

        verify(mapper).moveAndCheckIn(BOOKING, SESSION, OPERATOR, AT);
        verify(mapper).moveParticipants(BOOKING, SESSION, AT);
        verify(mapper).addBooked(LATER_SESSION, -2, AT);
        verify(mapper).addBooked(SESSION, 2, AT);
        verify(mapper).insertEvent(BOOKING, "RESCHEDULED", OPERATOR, LATER_SESSION, SESSION, AT);
    }

    @Test
    void releasingNeedsSeatsForTheWholeBooking() {
        when(mapper.lockRecord(OPERATOR, ORG, RECORD)).thenReturn(record("SESSION_MISMATCH", PROJECT, null));
        when(mapper.lockSession(SESSION)).thenReturn(new SessionLockRow(SESSION, PROJECT, "ACTIVE", STARTS, ENDS, 8, 7));
        when(mapper.lockSession(LATER_SESSION)).thenReturn(
                new SessionLockRow(LATER_SESSION, PROJECT, "ACTIVE", STARTS.plusHours(2), ENDS.plusHours(2), 8, 2));
        when(mapper.lockBooking(BOOKING)).thenReturn(new BookingRow(BOOKING, LATER_SESSION, PROJECT, CODE, "CONFIRMED",
                2, 7800L, null, ORG, STARTS.plusHours(2), ENDS.plusHours(2)));

        assertRejected(Reason.SEATS_NOT_ENOUGH, () -> desk.resolve(OPERATOR, ORG, RECORD, Action.RELEASE, true, null));
        verify(mapper, never()).moveAndCheckIn(any(), any(), any(), any());
        verify(mapper, never()).resolveRecord(any(), any(), anyString(), anyBoolean(), any(), any(), any());
    }

    private static BookingRow booking(String status, UUID operator, OffsetDateTime starts, OffsetDateTime ends) {
        return new BookingRow(BOOKING, SESSION, PROJECT, CODE, status, 2, 7800L,
                "CHECKED_IN".equals(status) ? AT.minusMinutes(3) : null, operator, starts, ends);
    }

    private static RecordRow record(String outcome, UUID currentProject, String resolution) {
        boolean foreign = "OTHER_ORGANIZATION".equals(outcome);
        boolean released = "RELEASE".equals(resolution);
        return new RecordRow(
                RECORD,
                BOOKING,
                outcome,
                "SCAN",
                AT,
                "0314",
                foreign ? null : "赵大海",
                foreign ? 1 : 2,
                foreign ? null : 7800L,
                foreign ? null : LATER_SESSION,
                foreign ? null : PROJECT,
                foreign ? null : "机器人避障闯关",
                foreign ? null : STARTS.plusHours(2),
                "VENUE",
                SESSION,
                currentProject,
                "机器人避障闯关",
                STARTS,
                "REPEATED".equals(outcome) ? AT.minusMinutes(3) : null,
                resolution,
                released,
                null,
                resolution == null ? null : AT,
                released ? SESSION : null,
                released ? "机器人避障闯关" : null,
                released ? STARTS : null);
    }

    private static void assertRejected(Reason reason, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(VenueCheckinException.class, error -> assertThat(error.reason()).isEqualTo(reason));
    }
}
