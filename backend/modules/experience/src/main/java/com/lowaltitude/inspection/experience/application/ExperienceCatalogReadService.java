package com.lowaltitude.inspection.experience.application;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueRow;

/**
 * 体验频道只读服务：线上体验项目、场馆列表与详情、场次、馆内设备（契约 contracts/openapi-experience.yaml）。
 * 场次状态在这里按开始时间和余位计算，客户端不自行判断。
 */
public final class ExperienceCatalogReadService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    /** 查询里“不限”的占位值。 */
    private static final String ALL = "ALL";
    /** 场馆都在境内，“某一天”一律按北京时间划分。 */
    static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Shanghai");

    private final ExperienceCatalogReadStore store;
    private final Clock clock;

    ExperienceCatalogReadService(ExperienceCatalogReadStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public ExperienceItemPageView listExperienceItems(String typeCode, int page, int size) {
        requirePage(page, size);
        String type = parseFilter(typeCode, ExperienceType::valueOf);
        long total = store.countExperienceItems(type);
        List<ExperienceItemView> items = store.findExperienceItems(type, size, offset(page, size)).stream()
                .map(row -> new ExperienceItemView(
                        row.experienceId(),
                        ExperienceType.valueOf(row.type()),
                        row.title(),
                        blankToNull(row.summary()),
                        secureMedia(row.coverUrl(), row.title()),
                        row.loginRequired()))
                .toList();
        return new ExperienceItemPageView(page, size, total, hasMore(page, size, total), items);
    }

    public VenuePageView listVenues(String cityCode, int page, int size) {
        requireCityCode(cityCode);
        requirePage(page, size);
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.atZoneSameInstant(VENUE_ZONE).toLocalDate();
        long total = store.countVenues(cityCode);
        List<VenueCardView> items = store
                .findVenues(cityCode, now, startOfDay(today.plusDays(1)), size, offset(page, size))
                .stream()
                .map(row -> new VenueCardView(
                        row.venueId(),
                        row.name(),
                        secureMedia(row.coverUrl(), row.name()),
                        blankToNull(row.openingNote()),
                        row.address(),
                        row.openSessionsToday()))
                .toList();
        return new VenuePageView(page, size, total, hasMore(page, size, total), items);
    }

    public VenueDetailView findVenue(UUID venueId, String cityCode) {
        VenueRow venue = requireVenue(venueId, cityCode);
        return new VenueDetailView(
                venue.venueId(),
                venue.name(),
                secureMedia(venue.coverUrl(), venue.name()),
                blankToNull(venue.openingNote()),
                venue.address(),
                venue.latitude(),
                venue.longitude(),
                blankToNull(venue.phone()),
                new VenueOperatorView(venue.operatorId(), venue.operatorName()),
                store.findProjects(venueId).stream()
                        .map(row -> new VenueProjectView(
                                row.projectId(),
                                row.name(),
                                row.durationMinutes(),
                                row.minAge(),
                                row.capacity(),
                                blankToNull(row.limitNote()),
                                row.priceAmountMinor(),
                                row.currency() == null ? "CNY" : row.currency().trim()))
                        .toList(),
                store.findInfoItems(venueId).stream()
                        .map(row -> new VenueInfoItemView(row.label(), row.content()))
                        .toList(),
                venue.deviceCount());
    }

    public VenueSessionListView listSessions(
            UUID venueId,
            String cityCode,
            UUID projectId,
            String dateText
    ) {
        if (projectId == null) {
            throw new IllegalArgumentException("project id is required");
        }
        LocalDate date = parseDate(dateText);
        requireVenue(venueId, cityCode);
        // 项目必须属于这个场馆，否则拿别的场馆的项目编号就能读到它的场次。
        if (!store.projectBelongsToVenue(venueId, projectId)) {
            throw new VenueNotFoundException();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<VenueSessionView> sessions = store
                .findSessions(projectId, startOfDay(date), startOfDay(date.plusDays(1)))
                .stream()
                .map(row -> new VenueSessionView(
                        row.sessionId(),
                        row.startsAt(),
                        row.endsAt(),
                        row.capacity(),
                        Math.max(0, row.capacity() - row.bookedCount()),
                        VenueSessionStatus.of(now, row.startsAt(), row.capacity(), row.bookedCount())))
                .toList();
        return new VenueSessionListView(date, sessions);
    }

    public VenueDevicePageView listDevices(
            UUID venueId,
            String cityCode,
            String categoryCode,
            int page,
            int size
    ) {
        requirePage(page, size);
        String category = parseFilter(categoryCode, VenueDeviceCategory::valueOf);
        requireVenue(venueId, cityCode);
        long total = store.countDevices(venueId, category);
        List<VenueDeviceView> items = store.findDevices(venueId, category, size, offset(page, size)).stream()
                .map(row -> new VenueDeviceView(
                        row.deviceId(),
                        VenueDeviceCategory.valueOf(row.category()),
                        row.name(),
                        blankToNull(row.summary()),
                        secureMedia(row.imageUrl(), row.name()),
                        VenueDeviceTrialMode.valueOf(row.trialMode())))
                .toList();
        return new VenueDevicePageView(page, size, total, hasMore(page, size, total), items);
    }

    private VenueRow requireVenue(UUID venueId, String cityCode) {
        if (venueId == null) {
            throw new IllegalArgumentException("venue id is required");
        }
        requireCityCode(cityCode);
        return store.findVenue(venueId, cityCode).orElseThrow(VenueNotFoundException::new);
    }

    private static <E extends Enum<E>> String parseFilter(String code, Function<String, E> parser) {
        if (code == null || code.isBlank()) {
            return ALL;
        }
        try {
            return parser.apply(code.trim()).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("filter value is invalid");
        }
    }

    private static void requireCityCode(String cityCode) {
        if (cityCode == null || !cityCode.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
    }

    private static int offset(int page, int size) {
        return Math.multiplyExact(page - 1, size);
    }

    private static boolean hasMore(int page, int size, long total) {
        return (long) page * size < total;
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("date is required");
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("date is invalid");
        }
    }

    private static OffsetDateTime startOfDay(LocalDate date) {
        return date.atStartOfDay(VENUE_ZONE).toOffsetDateTime();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 只放行带主机名的 HTTPS 地址（AGENTS.md §11）；其余当作没有图片。 */
    private static ExperienceMediaView secureMedia(String url, String alt) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            return new ExperienceMediaView(uri.toString(), alt);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}

record ExperienceMediaView(String url, String alt) {
}

record ExperienceItemView(
        UUID experienceId,
        ExperienceType type,
        String title,
        String summary,
        ExperienceMediaView coverImage,
        boolean loginRequired
) {
}

record ExperienceItemPageView(
        int page,
        int size,
        long total,
        boolean hasMore,
        List<ExperienceItemView> items
) {
}

record VenueCardView(
        UUID venueId,
        String name,
        ExperienceMediaView coverImage,
        String openingNote,
        String address,
        long openSessionsToday
) {
}

record VenuePageView(int page, int size, long total, boolean hasMore, List<VenueCardView> items) {
}

record VenueOperatorView(UUID organizationId, String name) {
}

record VenueProjectView(
        UUID projectId,
        String name,
        int durationMinutes,
        Integer minAge,
        int capacity,
        String limitNote,
        long priceAmountMinor,
        String currency
) {
}

record VenueInfoItemView(String label, String content) {
}

record VenueDetailView(
        UUID venueId,
        String name,
        ExperienceMediaView coverImage,
        String openingNote,
        String address,
        Double latitude,
        Double longitude,
        String phone,
        VenueOperatorView operator,
        List<VenueProjectView> projects,
        List<VenueInfoItemView> infoItems,
        long deviceCount
) {
}

record VenueSessionView(
        UUID sessionId,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        int capacity,
        int seatsLeft,
        VenueSessionStatus status
) {
}

record VenueSessionListView(LocalDate date, List<VenueSessionView> sessions) {
}

record VenueDeviceView(
        UUID deviceId,
        VenueDeviceCategory category,
        String name,
        String summary,
        ExperienceMediaView image,
        VenueDeviceTrialMode trialMode
) {
}

record VenueDevicePageView(
        int page,
        int size,
        long total,
        boolean hasMore,
        List<VenueDeviceView> items
) {
}
