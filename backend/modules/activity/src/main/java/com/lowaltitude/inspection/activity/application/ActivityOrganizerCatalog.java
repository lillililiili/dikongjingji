package com.lowaltitude.inspection.activity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOrganizerMapper;

/**
 * 给别的模块用的只读入口（S5-2）：某个机构在某城市主办或承办、还有场次没开始的已上线活动。
 * 商城的商家主页经 bootstrap 的端口来读，不直接查活动的表。
 */
public final class ActivityOrganizerCatalog {

    private static final int MAX_LIMIT = 20;

    private final ActivityOrganizerMapper mapper;
    private final Clock clock;

    public ActivityOrganizerCatalog(ActivityOrganizerMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public record UpcomingActivity(UUID activityId, String title, String coverUrl, String venueName, Instant startsAt,
                                   String priceMode, Long priceAmountMinor) {
    }

    public record Upcoming(long total, List<UpcomingActivity> items) {
    }

    public Upcoming upcoming(UUID organizerId, String cityCode, int limit) {
        if (organizerId == null || cityCode == null || cityCode.isBlank()) {
            throw new IllegalArgumentException("organizer and city are required");
        }
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        Instant now = clock.instant();
        String city = cityCode.trim();
        return new Upcoming(
                mapper.countUpcoming(organizerId, city, now),
                mapper.findUpcoming(organizerId, city, now, size).stream()
                        .map(row -> new UpcomingActivity(row.activityId(), row.title(), row.coverUrl(), row.venueName(),
                                row.startsAt(), row.priceMode(), row.priceAmountMinor()))
                        .toList());
    }
}
