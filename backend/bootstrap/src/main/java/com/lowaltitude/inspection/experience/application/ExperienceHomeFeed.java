package com.lowaltitude.inspection.experience.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 给首页推荐用的场馆入口：本城的前几个场馆，以及“今天去体验”。
 * “今天去体验”取第一个今天还有可约场次的场馆，列出它前几个项目各自最早的一场可约场次；
 * 没有这样的场馆或场次时不返回，首页就不显示这一块。“今天”按北京时间。
 */
@Component
public class ExperienceHomeFeed {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final ExperienceCatalogReadService service;
    private final Clock clock;

    @Autowired
    public ExperienceHomeFeed(ExperienceCatalogReadService service) {
        this(service, Clock.systemUTC());
    }

    ExperienceHomeFeed(ExperienceCatalogReadService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    public Feed load(String cityCode, int venueCount, int todayProjectCount) {
        List<VenueCardView> venues = service.listVenues(cityCode, 1, venueCount).items();
        return new Feed(venues, today(cityCode, venues, todayProjectCount).orElse(null));
    }

    private Optional<Today> today(String cityCode, List<VenueCardView> venues, int projectCount) {
        Optional<VenueCardView> venue = venues.stream()
                .filter(candidate -> candidate.openSessionsToday() > 0)
                .findFirst();
        if (venue.isEmpty()) {
            return Optional.empty();
        }
        UUID venueId = venue.get().venueId();
        String date = LocalDate.now(clock.withZone(BEIJING)).toString();
        List<TodaySlot> slots = new ArrayList<>();
        for (VenueProjectView project : service.findVenue(venueId, cityCode).projects().stream()
                .limit(projectCount)
                .toList()) {
            // 场次状态由读服务按当前时间算好：OPEN 即还没开始、还有余位。
            service.listSessions(venueId, cityCode, project.projectId(), date).sessions().stream()
                    .filter(session -> session.status() == VenueSessionStatus.OPEN && session.seatsLeft() > 0)
                    .min(Comparator.comparing(VenueSessionView::startsAt))
                    .ifPresent(session -> slots.add(new TodaySlot(
                            project.projectId(), project.name(), session.startsAt(), session.seatsLeft())));
        }
        return slots.isEmpty() ? Optional.empty() : Optional.of(new Today(venue.get(), slots));
    }

    public record Feed(List<?> venues, Today today) {
    }

    public record Today(Object venue, List<TodaySlot> slots) {
    }

    public record TodaySlot(UUID projectId, String projectName, OffsetDateTime startsAt, int seatsLeft) {
    }
}
