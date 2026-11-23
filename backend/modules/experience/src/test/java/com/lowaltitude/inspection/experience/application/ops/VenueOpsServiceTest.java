package com.lowaltitude.inspection.experience.application.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.DeviceDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.InfoItemDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.ProjectDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.SessionPlan;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.VenueDraft;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueOpsMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueOpsMapper.ProjectRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueOpsMapper.SessionRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueOpsMapper.VenueRow;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 运营端“场馆与设备”的规则：上线前必填、版本号、幂等新建、周模板展开与跳过、场次停售与取消、关联商品、留痕。 */
class VenueOpsServiceTest {

    /** 2026-11-23（周一）09:00 北京时间。 */
    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final UUID VENUE = UUID.fromString("b2000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("b4000000-0000-4000-8000-000000000101");
    private static final UUID SESSION = UUID.fromString("b5000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR_ORG = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000101");
    private static final UUID KEY = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final VenueOpsService.Operator OPERATOR =
            new VenueOpsService.Operator(UUID.fromString("90000000-0000-4000-8000-0000000000b1"), "平台运营（示例）");

    private final VenueOpsMapper mapper = mock(VenueOpsMapper.class);
    private final VenueOpsService service = new VenueOpsService(mapper,
            code -> "370500".equals(code) ? Optional.of("东营市") : Optional.empty(),
            org -> OPERATOR_ORG.equals(org) ? Optional.of("东营低空与机器人体验馆") : Optional.empty(),
            (product, city) -> PRODUCT.equals(product) && "370500".equals(city),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void changesAreRecorded() {
        when(mapper.insertChange(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void newVenuesAreDraftsWithGeneratedOpeningHoursAndARecord() {
        when(mapper.findVenue(any())).thenAnswer(call -> venue(call.getArgument(0), "DRAFT", null, "09:30", 0));

        var created = service.createVenue(KEY, draft(null, "09:30", "18:00"), OPERATOR);

        assertThat(created.created()).isTrue();
        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        verify(mapper).insertVenue(id.capture(), eq(OPERATOR_ORG), eq("东营低空与机器人体验馆"), isNull(), eq("370500"),
                eq("东营区示例路 66 号"), eq("0546-8000 0000"), eq("09:30"), eq("18:00"), eq("周一闭馆"),
                eq("09:30–18:00 · 周一闭馆"), eq(30), eq(10), eq(0), eq(KEY), eq("模拟飞行 · 机器人编程"), any());
        verify(mapper).insertInfoItem(id.getValue(), "入口", "东门入场，西门离场", 10, OffsetDateTime.ofInstant(NOW,
                ZoneOffset.UTC));
        verify(mapper).insertChange(eq(id.getValue()), eq("VENUE"), eq(id.getValue()), eq("CREATE"),
                eq("新建场馆草稿“东营低空与机器人体验馆”"), eq(OPERATOR.accountId()), eq("平台运营（示例）"), any());
        assertThat(created.venue().publishMissing()).containsExactly("coverUrl", "projects", "sessions");
        assertThat(created.venue().cityName()).isEqualTo("东营市");
    }

    @Test
    void theSameKeyAgainReturnsTheFirstVenue() {
        when(mapper.findVenueByCreateKey(KEY)).thenReturn(VENUE);
        when(mapper.findVenue(VENUE)).thenReturn(venue(VENUE, "DRAFT", null, null, 0));

        var replay = service.createVenue(KEY, draft(null, "09:30", "18:00"), OPERATOR);

        assertThat(replay.created()).isFalse();
        assertThat(replay.venue().venueId()).isEqualTo(VENUE);
        verify(mapper, never()).insertVenue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void operatorCityAndInputsAreChecked() {
        assertRejected(VenueOpsError.OPERATOR_INVALID, () -> service.createVenue(KEY, new VenueDraft(UUID.randomUUID(),
                "馆", "370500", null, "地址", null, null, null, null, null, null, null, null), OPERATOR));
        assertRejected(VenueOpsError.CITY_UNKNOWN, () -> service.createVenue(KEY, new VenueDraft(OPERATOR_ORG, "馆",
                "110100", null, "地址", null, null, null, null, null, null, null, null), OPERATOR));
        for (VenueDraft bad : List.of(
                new VenueDraft(OPERATOR_ORG, "馆", "370500", "http://cdn.example.com/a.jpg", "地址", null, null, null,
                        null, null, null, null, null),
                new VenueDraft(OPERATOR_ORG, "馆", "370500", null, "地址", null, "18:00", "09:30", null, null, null,
                        null, null),
                new VenueDraft(OPERATOR_ORG, "馆", "370500", null, "地址", null, "09:30", null, null, null, null, null,
                        null),
                new VenueDraft(OPERATOR_ORG, "馆", "370500", null, "地址", null, null, null, null, 241, null, null,
                        null),
                new VenueDraft(OPERATOR_ORG, "馆", "370500", null, "地址", "电话", null, null, null, null, null, null,
                        null),
                new VenueDraft(OPERATOR_ORG, " ", "370500", null, "地址", null, null, null, null, null, null, null,
                        null),
                new VenueDraft(OPERATOR_ORG, "馆", "370500", null, "地址", null, null, null, null, null, null, null,
                        null, "模".repeat(VenueOpsService.MAX_PROJECT_SUMMARY + 1)))) {
            assertThatThrownBy(() -> service.createVenue(KEY, bad, OPERATOR)).as(bad.toString())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.createVenue(null, draft(null, null, null), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishNeedsCoverHoursAProjectAndAFutureSession() {
        when(mapper.lockVenue(VENUE)).thenReturn(venue(VENUE, "DRAFT", null, null, 4));
        when(mapper.countActiveProjects(VENUE)).thenReturn(0L);
        when(mapper.countUpcomingSessions(eq(VENUE), any())).thenReturn(0L);

        OpsException incomplete = assertRejected(VenueOpsError.PUBLISH_INCOMPLETE,
                () -> service.publish(VENUE, 4, null, OPERATOR));
        assertThat(incomplete.details()).isEqualTo(Map.of("missing",
                List.of("coverUrl", "openingHours", "projects", "sessions")));
        assertRejected(VenueOpsError.VERSION_CONFLICT, () -> service.publish(VENUE, 3, null, OPERATOR));

        when(mapper.lockVenue(VENUE)).thenReturn(venue(VENUE, "DRAFT", "https://cdn.example.com/v.jpg", "09:30", 4));
        when(mapper.findVenue(VENUE)).thenReturn(venue(VENUE, "PUBLISHED", "https://cdn.example.com/v.jpg", "09:30", 5));
        when(mapper.countActiveProjects(VENUE)).thenReturn(1L);
        when(mapper.countUpcomingSessions(eq(VENUE), any())).thenReturn(3L);
        when(mapper.updateVenueStatus(eq(VENUE), eq("DRAFT"), eq("PUBLISHED"), any(), eq(4), any())).thenReturn(1);

        assertThat(service.publish(VENUE, 4, "首批开放", OPERATOR).status())
                .isEqualTo(VenueOpsViews.VenueOpsStatus.PUBLISHED);
        verify(mapper).insertChange(eq(VENUE), eq("VENUE"), eq(VENUE), eq("PUBLISH"), eq("上线：首批开放"),
                any(), any(), any());

        when(mapper.lockVenue(VENUE)).thenReturn(venue(VENUE, "PUBLISHED", "https://cdn.example.com/v.jpg", "09:30", 5));
        assertRejected(VenueOpsError.STATUS_CONFLICT, () -> service.publish(VENUE, 5, null, OPERATOR));
    }

    @Test
    void aPublishedVenueKeepsItsCoverHoursAndLastProject() {
        when(mapper.lockVenue(VENUE)).thenReturn(venue(VENUE, "PUBLISHED", "https://cdn.example.com/v.jpg", "09:30", 2));
        OpsException cleared = assertRejected(VenueOpsError.PUBLISH_INCOMPLETE,
                () -> service.updateVenue(VENUE, draft(null, null, null), 2, OPERATOR));
        assertThat(cleared.details()).isEqualTo(Map.of("missing", List.of("coverUrl", "openingHours")));

        when(mapper.lockProject(eq(VENUE), eq(PROJECT), any())).thenReturn(project("ACTIVE", 1));
        when(mapper.countActiveProjects(VENUE)).thenReturn(1L);
        OpsException last = assertRejected(VenueOpsError.PUBLISH_INCOMPLETE,
                () -> service.changeProjectStatus(VENUE, PROJECT, "DISABLED", 1, OPERATOR));
        assertThat(last.details()).isEqualTo(Map.of("missing", List.of("projects")));
        assertRejected(VenueOpsError.STATUS_CONFLICT,
                () -> service.changeProjectStatus(VENUE, PROJECT, "ACTIVE", 1, OPERATOR));
        verify(mapper, never()).updateProjectStatus(any(), any(), any(), anyInt(), any());
    }

    @Test
    void weeklyTemplatesExpandInBeijingTime() {
        List<OffsetDateTime> slots = VenueOpsService.expand(LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-30"),
                Set.of(DayOfWeek.MONDAY, DayOfWeek.SATURDAY), List.of(LocalTime.parse("10:00"), LocalTime.parse("14:30")));

        assertThat(slots).extracting(OffsetDateTime::toString).containsExactly(
                "2026-11-23T02:00Z", "2026-11-23T06:30Z",
                "2026-11-28T02:00Z", "2026-11-28T06:30Z",
                "2026-11-30T02:00Z", "2026-11-30T06:30Z");
        assertThat(VenueOpsService.startTimes(List.of("14:30", "10:00"))).extracting(LocalTime::toString)
                .containsExactly("10:00", "14:30");
        for (List<String> bad : List.of(List.<String>of(), List.of("9:00"), List.of("10:00", "10:00"),
                List.of("24:00"))) {
            assertThatThrownBy(() -> VenueOpsService.startTimes(bad)).as(bad.toString())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> VenueOpsService.weekdays(List.of("MONDAY", "monday")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VenueOpsService.weekdays(List.of("周一"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatingSkipsExistingAndPastStartsAndRecordsOnce() {
        when(mapper.lockVenue(VENUE)).thenReturn(venue(VENUE, "PUBLISHED", "https://cdn.example.com/v.jpg", "09:30", 1));
        when(mapper.lockProject(eq(VENUE), eq(PROJECT), any())).thenReturn(project("ACTIVE", 1));
        // 11/23 10:00 已有一场；11/23 08:00 已经过去。
        when(mapper.findSessionStarts(eq(PROJECT), any(), any()))
                .thenReturn(List.of(OffsetDateTime.parse("2026-11-23T10:00:00+08:00")));

        var result = service.generateSessions(VENUE, PROJECT, new SessionPlan("2026-11-23", "2026-11-30",
                List.of("MONDAY"), List.of("08:00", "10:00", "14:00"), null), OPERATOR);

        assertThat(result).isEqualTo(new VenueOpsViews.OpsVenueSessionsGeneratedView(4, 1, 1));
        verify(mapper).insertSession(PROJECT, OffsetDateTime.parse("2026-11-23T06:00:00Z"),
                OffsetDateTime.parse("2026-11-23T06:30:00Z"), 8, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(mapper, times(4)).insertSession(eq(PROJECT), any(), any(), eq(8), any());
        verify(mapper).insertChange(eq(VENUE), eq("PROJECT"), eq(PROJECT), eq("GENERATE"),
                eq("按周模板给“机器人避障闯关”生成 4 场：11/23–11/30，周一，08:00、10:00、14:00，每场 8 人（跳过已有 1 场、已过 1 场）"),
                any(), any(), any());
    }

    @Test
    void tooManySessionsAreRefusedBeforeAnythingIsWritten() {
        when(mapper.lockVenue(VENUE)).thenReturn(venue(VENUE, "DRAFT", null, null, 1));
        when(mapper.lockProject(eq(VENUE), eq(PROJECT), any())).thenReturn(project("ACTIVE", 1));
        when(mapper.findSessionStarts(eq(PROJECT), any(), any())).thenReturn(List.of());

        assertRejected(VenueOpsError.TOO_MANY_SESSIONS, () -> service.generateSessions(VENUE, PROJECT,
                new SessionPlan("2026-11-24", "2027-01-23",
                        List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"),
                        List.of("09:00", "10:00", "11:00", "14:00"), 6), OPERATOR));
        assertThatThrownBy(() -> service.generateSessions(VENUE, PROJECT, new SessionPlan("2026-11-24", "2027-01-25",
                List.of("MONDAY"), List.of("09:00"), null), OPERATOR)).isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertSession(any(), any(), any(), anyInt(), any());
    }

    @Test
    void sessionsWithBookingsCanOnlyStopSelling() {
        OffsetDateTime starts = OffsetDateTime.parse("2026-11-24T02:00:00Z");
        when(mapper.lockSession(VENUE, SESSION)).thenReturn(session("ACTIVE", starts, 3, 2, 5));
        assertRejected(VenueOpsError.SESSION_HAS_BOOKINGS, () -> service.cancelSession(VENUE, SESSION, 5, OPERATOR));
        assertRejected(VenueOpsError.STATUS_CONFLICT, () -> service.resumeSale(VENUE, SESSION, 5, OPERATOR));
        assertRejected(VenueOpsError.VERSION_CONFLICT, () -> service.stopSale(VENUE, SESSION, 4, OPERATOR));

        when(mapper.updateSessionStatus(SESSION, "ACTIVE", "STOPPED", 5, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
                .thenReturn(1);
        service.stopSale(VENUE, SESSION, 5, OPERATOR);
        verify(mapper).insertChange(eq(VENUE), eq("SESSION"), eq(SESSION), eq("STOP_SALE"),
                eq("停售 2026-11-24 10:00 的场次（已有 2 单预约照常有效）"), any(), any(), any());

        when(mapper.lockSession(VENUE, SESSION)).thenReturn(session("STOPPED", starts, 0, 0, 6));
        when(mapper.updateSessionStatus(eq(SESSION), eq("STOPPED"), eq("CANCELLED"), eq(6), any())).thenReturn(1);
        service.cancelSession(VENUE, SESSION, 6, OPERATOR);
        verify(mapper).updateSessionStatus(eq(SESSION), eq("STOPPED"), eq("CANCELLED"), eq(6), any());

        when(mapper.lockSession(VENUE, SESSION)).thenReturn(session("ACTIVE",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), 0, 0, 1));
        assertRejected(VenueOpsError.SESSION_STARTED, () -> service.stopSale(VENUE, SESSION, 1, OPERATOR));
        when(mapper.lockSession(VENUE, UUID.fromString("b5000000-0000-4000-8000-000000000009"))).thenReturn(null);
        assertRejected(VenueOpsError.NOT_FOUND, () -> service.stopSale(VENUE,
                UUID.fromString("b5000000-0000-4000-8000-000000000009"), 1, OPERATOR));
    }

    @Test
    void devicesLinkOnlyProductsSoldInTheVenueCity() {
        when(mapper.lockVenue(VENUE)).thenReturn(venue(VENUE, "DRAFT", null, null, 1));
        when(mapper.findVenue(VENUE)).thenReturn(venue(VENUE, "DRAFT", null, null, 1));
        assertRejected(VenueOpsError.PRODUCT_UNKNOWN, () -> service.createDevice(VENUE, KEY, new DeviceDraft("ROBOT",
                "编程机器人", null, null, "ACCOMPANIED_TRIAL", UUID.randomUUID(), null), OPERATOR));
        assertThatThrownBy(() -> service.createDevice(VENUE, KEY, new DeviceDraft("CAR", "车", null, null,
                "DISPLAY_ONLY", null, null), OPERATOR)).isInstanceOf(IllegalArgumentException.class);

        assertThat(service.createDevice(VENUE, KEY, new DeviceDraft("robot", "编程机器人", "陪同试用",
                "https://cdn.example.com/d.png", "ACCOMPANIED_TRIAL", PRODUCT, 10), OPERATOR).created()).isTrue();
        verify(mapper).insertDevice(any(), eq(VENUE), eq("ROBOT"), eq("编程机器人"), eq("陪同试用"),
                eq("https://cdn.example.com/d.png"), eq("ACCOMPANIED_TRIAL"), eq(PRODUCT), eq(10), eq(KEY), any());
        verify(mapper).insertChange(eq(VENUE), eq("DEVICE"), any(), eq("CREATE"),
                eq("新增设备“编程机器人”，关联商品 " + PRODUCT), any(), any(), any());
    }

    @Test
    void projectsAreValidatedAndPriceChangesAreNoted() {
        when(mapper.lockVenue(VENUE)).thenReturn(venue(VENUE, "DRAFT", null, null, 1));
        when(mapper.findVenue(VENUE)).thenReturn(venue(VENUE, "DRAFT", null, null, 1));
        for (ProjectDraft bad : List.of(new ProjectDraft("项目", 4, 0L, null, 8, null, null),
                new ProjectDraft("项目", 30, -1L, null, 8, null, null),
                new ProjectDraft("项目", 30, 3900L, null, 0, null, null),
                new ProjectDraft("项目", 30, 3900L, 100, 8, null, null),
                new ProjectDraft("项目", null, 3900L, null, 8, null, null))) {
            assertThatThrownBy(() -> service.createProject(VENUE, KEY, bad, OPERATOR)).as(bad.toString())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        when(mapper.lockProject(eq(VENUE), eq(PROJECT), any())).thenReturn(project("ACTIVE", 2));
        when(mapper.updateProject(eq(VENUE), eq(PROJECT), anyString(), anyInt(), any(), anyInt(), any(),
                eq(4900L), anyInt(), eq(2), any())).thenReturn(1);
        service.updateProject(VENUE, PROJECT, new ProjectDraft("机器人避障闯关", 30, 4900L, 6, 8, null, 10), 2,
                OPERATOR);
        verify(mapper).insertChange(eq(VENUE), eq("PROJECT"), eq(PROJECT), eq("UPDATE"),
                eq("修改项目“机器人避障闯关”（30 分钟 · ¥49.00 · 每场 8 人），价格改动只影响之后的新预约"),
                any(), any(), any());
    }

    // ---- 造数据 ----

    private static VenueDraft draft(String coverUrl, String opensAt, String closesAt) {
        return new VenueDraft(OPERATOR_ORG, " 东营低空与机器人体验馆 ", "370500", coverUrl, "东营区示例路 66 号",
                "0546-8000 0000", opensAt, closesAt, "周一闭馆", null, 10, null,
                List.of(new InfoItemDraft("入口", "东门入场，西门离场")), " 模拟飞行 · 机器人编程 ");
    }

    private static VenueRow venue(UUID id, String status, String coverUrl, String opensAt, int version) {
        return new VenueRow(id, "东营低空与机器人体验馆", OPERATOR_ORG, "东营低空与机器人体验馆", "370500", coverUrl,
                "东营区示例路 66 号", "0546-8000 0000", opensAt, opensAt == null ? null : "18:00", "周一闭馆",
                opensAt == null ? null : opensAt + "–18:00 · 周一闭馆", 30, 10, 0, status, null,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), version, null);
    }

    private static ProjectRow project(String status, int version) {
        return new ProjectRow(PROJECT, "机器人避障闯关", 30, 3900, 6, 8, null, 10, status, 3, null, version);
    }

    private static SessionRow session(String status, OffsetDateTime startsAt, int booked, long bookings, int version) {
        return new SessionRow(SESSION, PROJECT, startsAt, startsAt.plusMinutes(30), 8, booked, bookings, status, version);
    }

    private static OpsException assertRejected(VenueOpsError error, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call);
        assertThat(thrown).isInstanceOf(OpsException.class);
        assertThat(((OpsException) thrown).error()).isEqualTo(error);
        return (OpsException) thrown;
    }
}
