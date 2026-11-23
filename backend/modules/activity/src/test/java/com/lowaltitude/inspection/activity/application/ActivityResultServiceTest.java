package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityResultMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityResultMapper.RosterRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityResultMapper.SessionRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityResultMapper.TicketLockRow;
import org.junit.jupiter.api.Test;

/** 机构工作台的“活动结果”（S5-3）：只给已签到的录、版本号、打码、第一次录入才通知。 */
class ActivityResultServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-23T06:00:00Z");
    private static final UUID ORG = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID OPERATOR = UUID.fromString("a1000000-0000-4000-8000-0000000000a4");
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SESSION = UUID.fromString("a3000000-0000-4000-8000-000000000502");
    private static final UUID ENROLLMENT = UUID.fromString("a5000000-0000-4000-8000-000000000001");
    private static final UUID TICKET = UUID.fromString("a6000000-0000-4000-8000-000000000001");

    private final ActivityResultMapper mapper = mock(ActivityResultMapper.class);
    private final ActivityEnrollmentMapper enrollments = mock(ActivityEnrollmentMapper.class);
    private final ActivityEnrollmentNotifier notifier = mock(ActivityEnrollmentNotifier.class);
    private final ActivityResultService service =
            new ActivityResultService(mapper, enrollments, notifier, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

    private void ticket(String status, Integer version) {
        when(mapper.lockTicket(ORG, TICKET)).thenReturn(
                new TicketLockRow(TICKET, ENROLLMENT, SESSION, ACCOUNT, status, NOW.minusHours(1), "开放体验日", version));
        when(mapper.findRoster(ORG, SESSION)).thenReturn(List.of(new RosterRow(
                TICKET, "王小禾", 11, "ACT-7K2M-9QXD-4HTP", status, NOW.minusMinutes(50), null,
                "COMPLETED", "5/5", null, NOW, version == null ? 0 : version + 1)));
    }

    @Test
    void theFirstResultIsInsertedAndTheCustomerIsToldOnce() {
        ticket("CHECKED_IN", null);
        when(mapper.insertResult(eq(TICKET), eq(ORG), eq("COMPLETED"), eq("5/5"), eq(null), eq(OPERATOR), eq(NOW)))
                .thenReturn(0);

        ResultRosterEntryView entry = service.record(OPERATOR, ORG, TICKET,
                new RecordResultCommand("COMPLETED", " 5/5 ", "  ", null));

        assertThat(entry.holderName()).isEqualTo("王**");
        assertThat(entry.codeTail()).isEqualTo("4HTP");
        assertThat(entry.result().completion()).isEqualTo(ResultCompletion.COMPLETED);
        verify(enrollments).insertEvent(ENROLLMENT, TICKET, "RESULT_RECORDED", OPERATOR, NOW);
        verify(notifier).resultRecorded(
                new ActivityEnrollmentNotifier.ResultRecorded(ACCOUNT, ENROLLMENT, "开放体验日", NOW.toInstant()));
    }

    @Test
    void aChangeNeedsTheVersionItReadAndDoesNotNotifyAgain() {
        ticket("CHECKED_IN", 0);
        assertThatThrownBy(() -> service.record(OPERATOR, ORG, TICKET, new RecordResultCommand("INCOMPLETE", null, null, null)))
                .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                        e -> assertThat(e.error()).isEqualTo(Error.RESULT_VERSION_CONFLICT));
        when(mapper.updateResult(TICKET, ORG, "INCOMPLETE", null, null, OPERATOR, 0, NOW)).thenReturn(1);

        service.record(OPERATOR, ORG, TICKET, new RecordResultCommand("INCOMPLETE", null, null, 0));

        verify(notifier, never()).resultRecorded(any());
    }

    @Test
    void onlyACheckedInTicketGetsAResultAndAnotherOrganisationsTicketDoesNotExist() {
        ticket("ACTIVE", null);
        assertThatThrownBy(() -> service.record(OPERATOR, ORG, TICKET, new RecordResultCommand("COMPLETED", null, null, null)))
                .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                        e -> assertThat(e.error()).isEqualTo(Error.RESULT_NOT_ALLOWED));
        when(mapper.lockTicket(ORG, TICKET)).thenReturn(null);
        assertThatThrownBy(() -> service.record(OPERATOR, ORG, TICKET, new RecordResultCommand("COMPLETED", null, null, null)))
                .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                        e -> assertThat(e.error()).isEqualTo(Error.NOT_FOUND));
        verify(mapper, never()).insertResult(any(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).updateResult(any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void textsAreCheckedBeforeAnythingIsRead() {
        assertThatThrownBy(() -> service.record(OPERATOR, ORG, TICKET, new RecordResultCommand("DONE", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(OPERATOR, ORG, TICKET,
                new RecordResultCommand("COMPLETED", "成".repeat(41), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(OPERATOR, ORG, TICKET,
                new RecordResultCommand("COMPLETED", null, "证\n书", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(OPERATOR, null, TICKET, new RecordResultCommand("COMPLETED", null, null, null)))
                .isInstanceOfSatisfying(ActivityEnrollmentException.class,
                        e -> assertThat(e.error()).isEqualTo(Error.FORBIDDEN));
        verify(mapper, never()).lockTicket(any(), any());
    }

    @Test
    void theSessionListLooksBack180DaysAndTheRosterSaysWhetherItStarted() {
        when(mapper.countSessions(ORG, NOW, NOW.minusDays(180))).thenReturn(1L);
        when(mapper.findSessions(ORG, NOW, NOW.minusDays(180), 20, 0)).thenReturn(List.of(
                new SessionRow(SESSION, UUID.randomUUID(), "开放体验日", " ", NOW.minusHours(1), null, 3, 2, 1)));
        ResultSessionPageView page = service.sessions(ORG, 1, 20);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.label()).isNull();
            assertThat(item.checkedInPeople()).isEqualTo(2);
        });

        when(mapper.findSession(ORG, SESSION)).thenReturn(
                new SessionRow(SESSION, UUID.randomUUID(), "开放体验日", "下午", NOW.plusHours(1), null, 0, 0, 0));
        when(mapper.findRoster(ORG, SESSION)).thenReturn(List.of());
        assertThat(service.roster(ORG, SESSION).started()).isFalse();
    }
}
