package com.lowaltitude.inspection.experience.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import org.junit.jupiter.api.Test;

class ExperienceHomeFeedTest {

    private static final UUID QUIET_VENUE = UUID.fromString("b2000000-0000-4000-8000-000000000001");
    private static final UUID OPEN_VENUE = UUID.fromString("b2000000-0000-4000-8000-000000000002");
    // 17:30 UTC 已经是北京时间第二天凌晨 01:30。
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T17:30:00Z"), ZoneOffset.UTC);
    private static final String BEIJING_TODAY = "2026-11-18";

    private final ExperienceCatalogReadService service = mock(ExperienceCatalogReadService.class);
    private final ExperienceHomeFeed feed = new ExperienceHomeFeed(service, CLOCK);

    @Test
    void todayComesFromTheFirstVenueWithSessionsLeftAndTheEarliestOpenSessionOfEachProject() {
        VenueCardView quiet = venue(QUIET_VENUE, "没有场次的馆", 0);
        VenueCardView open = venue(OPEN_VENUE, "有场次的馆", 5);
        when(service.listVenues("370500", 1, 2)).thenReturn(new VenuePageView(1, 2, 2, false, List.of(quiet, open)));
        List<VenueProjectView> projects = List.of(project(1, "避障闯关"), project(2, "模拟飞行"),
                project(3, "编程体验课"), project(4, "第四个项目"));
        when(service.findVenue(OPEN_VENUE, "370500")).thenReturn(detail(OPEN_VENUE, projects));
        when(service.listSessions(OPEN_VENUE, "370500", projectId(1), BEIJING_TODAY)).thenReturn(sessions(
                session("10:00", 8, 0, VenueSessionStatus.ENDED),
                session("16:00", 8, 6, VenueSessionStatus.OPEN),
                session("14:00", 8, 2, VenueSessionStatus.OPEN)));
        when(service.listSessions(OPEN_VENUE, "370500", projectId(2), BEIJING_TODAY)).thenReturn(sessions(
                session("14:00", 8, 0, VenueSessionStatus.FULL)));
        when(service.listSessions(OPEN_VENUE, "370500", projectId(3), BEIJING_TODAY)).thenReturn(sessions(
                session("15:00", 8, 4, VenueSessionStatus.OPEN)));

        ExperienceHomeFeed.Feed result = feed.load("370500", 2, 3);

        assertThat(result.venues()).isEqualTo(List.of(quiet, open));
        assertThat(result.today().venue()).isEqualTo(open);
        assertThat(result.today().slots()).containsExactly(
                new ExperienceHomeFeed.TodaySlot(projectId(1), "避障闯关", at("14:00"), 2),
                new ExperienceHomeFeed.TodaySlot(projectId(3), "编程体验课", at("15:00"), 4));
        // 只问前三个项目。
        verify(service, never()).listSessions(any(), anyString(), eq(projectId(4)), anyString());
    }

    @Test
    void noTodayBlockWhenNoVenueHasSessionsLeftOrEverySessionIsGone() {
        VenueCardView quiet = venue(QUIET_VENUE, "没有场次的馆", 0);
        when(service.listVenues("370500", 1, 2)).thenReturn(new VenuePageView(1, 2, 1, false, List.of(quiet)));

        assertThat(feed.load("370500", 2, 3).today()).isNull();
        verify(service, never()).findVenue(any(), anyString());

        VenueCardView open = venue(OPEN_VENUE, "有场次的馆", 1);
        when(service.listVenues("370500", 1, 2)).thenReturn(new VenuePageView(1, 2, 1, false, List.of(open)));
        when(service.findVenue(OPEN_VENUE, "370500")).thenReturn(detail(OPEN_VENUE, List.of(project(1, "避障闯关"))));
        when(service.listSessions(OPEN_VENUE, "370500", projectId(1), BEIJING_TODAY)).thenReturn(sessions(
                session("10:00", 8, 0, VenueSessionStatus.ENDED)));

        ExperienceHomeFeed.Feed result = feed.load("370500", 2, 3);
        assertThat(result.today()).isNull();
        assertThat(result.venues()).isEqualTo(List.of(open));
    }

    private static UUID projectId(int number) {
        return UUID.fromString("b4000000-0000-4000-8000-00000000010" + number);
    }

    private static OffsetDateTime at(String time) {
        return OffsetDateTime.parse(BEIJING_TODAY + "T" + time + ":00+08:00");
    }

    private static VenueCardView venue(UUID id, String name, long openSessionsToday) {
        return new VenueCardView(id, name, null, "09:30–18:00", "示例路 66 号", openSessionsToday);
    }

    private static VenueProjectView project(int number, String name) {
        return new VenueProjectView(projectId(number), name, 30, 6, 8, null, 3900L, "CNY", true);
    }

    private static VenueDetailView detail(UUID id, List<VenueProjectView> projects) {
        return new VenueDetailView(id, "有场次的馆", null, "09:30–18:00", "示例路 66 号", null, null, null,
                null, projects, List.of(), 0);
    }

    private static VenueSessionView session(String time, int capacity, int seatsLeft, VenueSessionStatus status) {
        return new VenueSessionView(UUID.randomUUID(), at(time), at(time).plusMinutes(30), capacity, seatsLeft, status);
    }

    private static VenueSessionListView sessions(VenueSessionView... sessions) {
        return new VenueSessionListView(LocalDate.parse(BEIJING_TODAY), List.of(sessions));
    }
}
