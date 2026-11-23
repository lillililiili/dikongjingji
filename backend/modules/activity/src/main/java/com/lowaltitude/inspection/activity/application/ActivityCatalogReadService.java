package com.lowaltitude.inspection.activity.application;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 活动目录只读服务：频道列表、有场次的日期、活动详情（契约 contracts/openapi-activity.yaml）。
 * 报名状态全部在这里按容量、报名截止时间和开始时间计算，客户端不自行判断。
 */
public final class ActivityCatalogReadService {

    private static final int MAX_PAGE_SIZE = 50;
    /** 日期条一次最多查的天数（含首尾），对应契约 session-dates 的说明。 */
    private static final int MAX_SESSION_DATE_RANGE_DAYS = 62;
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    /** 活动都在境内举办，“某一天”一律按北京时间划分。 */
    static final ZoneId ACTIVITY_ZONE = ZoneId.of("Asia/Shanghai");

    private final ActivityCatalogReadStore store;
    private final Clock clock;
    private final Predicate<UUID> organizerCanCollect;

    /** 没有接交易模块：price.onlinePayment 一律为 false。 */
    ActivityCatalogReadService(ActivityCatalogReadStore store, Clock clock) {
        this(store, clock, organizerId -> false);
    }

    /**
     * @param organizerCanCollect 主办机构能不能在线收款（登记了有效的微信收款账户，E09）；
     *                            只对按人收费、单价大于 0 的活动才问
     */
    ActivityCatalogReadService(ActivityCatalogReadStore store, Clock clock, Predicate<UUID> organizerCanCollect) {
        this.store = store;
        this.clock = clock;
        this.organizerCanCollect = organizerCanCollect;
    }

    public ActivityPageView search(
            String cityCode,
            String typeCode,
            String dateText,
            boolean enrollingOnly,
            boolean freeOnly,
            int page,
            int size
    ) {
        requireCityCode(cityCode);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("activity page is invalid");
        }
        String type = ActivityType.parseFilter(typeCode);
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean filterByDate = dateText != null && !dateText.isBlank();
        // 不按日期筛选时仍给一个确定的区间，SQL 里由 filterByDate 决定是否使用。
        LocalDate date = filterByDate
                ? parseDate(dateText)
                : now.atZoneSameInstant(ACTIVITY_ZONE).toLocalDate();
        ActivityListQuery query = new ActivityListQuery(
                cityCode,
                type,
                freeOnly,
                enrollingOnly,
                filterByDate,
                startOfDay(date),
                startOfDay(date.plusDays(1)),
                now);

        long total = store.countListed(query);
        int offset = Math.multiplyExact(page - 1, size);
        // 一页里同一家主办机构只问一次。
        Map<UUID, Boolean> collecting = new HashMap<>();
        List<ActivityCardView> items = store.findListedPage(query, size, offset).stream()
                .map(row -> toCard(row, collecting))
                .toList();
        return new ActivityPageView(page, size, total, (long) page * size < total, items);
    }

    public ActivitySessionDatesView findSessionDates(
            String cityCode,
            String typeCode,
            String fromText,
            String toText
    ) {
        requireCityCode(cityCode);
        String type = ActivityType.parseFilter(typeCode);
        LocalDate from = parseDate(fromText);
        LocalDate to = parseDate(toText);
        if (to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) > MAX_SESSION_DATE_RANGE_DAYS) {
            throw new IllegalArgumentException("activity date range is invalid");
        }
        return new ActivitySessionDatesView(store.findSessionDates(
                cityCode,
                type,
                ACTIVITY_ZONE.getId(),
                startOfDay(from),
                startOfDay(to.plusDays(1)),
                OffsetDateTime.now(clock)));
    }

    public ActivityDetailView findDetail(UUID activityId, String cityCode) {
        if (activityId == null) {
            throw new IllegalArgumentException("activity id is required");
        }
        requireCityCode(cityCode);
        StoredActivityDetail row = store.findPublishedDetail(activityId, cityCode)
                .orElseThrow(ActivityNotFoundException::new);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ActivitySessionView> sessions = store.findActiveSessions(activityId).stream()
                .map(session -> new ActivitySessionView(
                        session.sessionId(),
                        blankToNull(session.label()),
                        session.startsAt(),
                        session.endsAt(),
                        session.checkInOpensAt(),
                        session.enrollmentClosesAt(),
                        session.capacity(),
                        seatsLeft(session.capacity(), session.enrolledCount()),
                        ActivityEnrollmentStatus.ofSession(
                                now,
                                session.startsAt(),
                                session.endsAt(),
                                session.enrollmentClosesAt(),
                                session.capacity(),
                                session.enrolledCount(),
                                row.waitlistAllowed(),
                                session.saleStopped()),
                        session.saleStopped()))
                .toList();
        // 图集只给受控 HTTPS 地址，读不懂的跳过（1.3.0）。
        List<ActivityMediaView> gallery = store.findGallery(activityId).stream()
                .map(url -> secureMedia(url, row.title()))
                .filter(java.util.Objects::nonNull)
                .toList();
        // 没有任何有效场次的活动无法报名，按已结束处理。
        ActivityEnrollmentStatus status = sessions.stream()
                .map(ActivitySessionView::status)
                .min(Comparator.naturalOrder())
                .orElse(ActivityEnrollmentStatus.ENDED);
        return new ActivityDetailView(
                row.activityId(),
                ActivityType.valueOf(row.type()),
                row.title(),
                blankToNull(row.subtitle()),
                secureMedia(row.coverUrl(), row.title()),
                gallery,
                toPrice(row.priceMode(), row.priceAmountMinor(), row.currency(), row.organizerId(), new HashMap<>()),
                new ActivityAgeRangeView(row.minAge(), row.maxAge()),
                status,
                new ActivityOrganizerView(row.organizerId(), row.organizerName()),
                row.coOrganizerId() == null
                        ? null
                        : new ActivityOrganizerView(row.coOrganizerId(), row.coOrganizerName()),
                new ActivityLocationView(
                        row.venueName(),
                        row.venueAddress(),
                        row.latitude(),
                        row.longitude()),
                blankToNull(row.scheduleNote()),
                blankToNull(row.eligibilityNote()),
                blankToNull(row.seatNote()),
                blankToNull(row.refundNote()),
                blankToNull(row.enrollmentNotice()),
                row.waitlistAllowed(),
                row.teamEnrollmentAllowed(),
                sessions);
    }

    private ActivityCardView toCard(StoredActivityCard row, Map<UUID, Boolean> collecting) {
        // 进列表的活动一定还有未开始的场次：有可报的就是报名中；否则有未截止但已满的就是已满；其余只剩报名截止。
        ActivityEnrollmentStatus status;
        if (row.hasOpenSession()) {
            status = ActivityEnrollmentStatus.OPEN;
        } else if (row.hasFullSession()) {
            status = row.waitlistAllowed()
                    ? ActivityEnrollmentStatus.FULL_WAITLIST
                    : ActivityEnrollmentStatus.FULL;
        } else {
            status = ActivityEnrollmentStatus.CLOSED;
        }
        return new ActivityCardView(
                row.activityId(),
                ActivityType.valueOf(row.type()),
                row.title(),
                secureMedia(row.coverUrl(), row.title()),
                row.venueName(),
                blankToNull(row.scheduleNote()),
                toPrice(row.priceMode(), row.priceAmountMinor(), row.currency(), row.organizerId(), collecting),
                new ActivityAgeRangeView(row.minAge(), row.maxAge()),
                status,
                new ActivityNextSessionView(
                        row.nextSessionId(),
                        row.nextStartsAt(),
                        row.nextCapacity(),
                        seatsLeft(row.nextCapacity(), row.nextEnrolledCount())));
    }

    /**
     * onlinePayment（E09）：能不能在线报名付款——按人收费、单价大于 0、主办机构能在线收款时为 true；
     * 免费、询价的活动一律 false（免费报名不用付款，看 mode）。
     */
    private ActivityPriceView toPrice(
            String mode,
            Long amountMinor,
            String currency,
            UUID organizerId,
            Map<UUID, Boolean> collecting
    ) {
        ActivityPriceMode priceMode = ActivityPriceMode.valueOf(mode);
        boolean priced = priceMode == ActivityPriceMode.PER_PERSON && amountMinor != null && amountMinor > 0;
        boolean onlinePayment = priced && organizerId != null
                && collecting.computeIfAbsent(organizerId, organizerCanCollect::test);
        return new ActivityPriceView(
                priceMode,
                priceMode == ActivityPriceMode.PER_PERSON ? amountMinor : null,
                currency == null ? "CNY" : currency.trim(),
                onlinePayment);
    }

    private static int seatsLeft(int capacity, int enrolledCount) {
        return Math.max(0, capacity - enrolledCount);
    }

    private static void requireCityCode(String cityCode) {
        if (cityCode == null || !cityCode.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
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
        return date.atStartOfDay(ACTIVITY_ZONE).toOffsetDateTime();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 只放行带主机名的 HTTPS 地址（AGENTS.md §11）；其余当作没有图片。 */
    static ActivityMediaView secureMedia(String url, String alt) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            return new ActivityMediaView(uri.toString(), alt);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}

record ActivityPageView(
        int page,
        int size,
        long total,
        boolean hasMore,
        List<ActivityCardView> items
) {
}

record ActivityCardView(
        UUID activityId,
        ActivityType type,
        String title,
        ActivityMediaView coverImage,
        String venueName,
        String scheduleNote,
        ActivityPriceView price,
        ActivityAgeRangeView ageRange,
        ActivityEnrollmentStatus enrollmentStatus,
        ActivityNextSessionView nextSession
) {
}

record ActivityNextSessionView(
        UUID sessionId,
        OffsetDateTime startsAt,
        int capacity,
        int seatsLeft
) {
}

record ActivitySessionDatesView(List<LocalDate> dates) {
}

record ActivityDetailView(
        UUID activityId,
        ActivityType type,
        String title,
        String subtitle,
        ActivityMediaView coverImage,
        /** 1.3.0：图集，按顺序，最多 9 张；没有时为空数组。 */
        List<ActivityMediaView> gallery,
        ActivityPriceView price,
        ActivityAgeRangeView ageRange,
        ActivityEnrollmentStatus enrollmentStatus,
        ActivityOrganizerView organizer,
        ActivityOrganizerView coOrganizer,
        ActivityLocationView location,
        String scheduleNote,
        String eligibilityNote,
        String seatNote,
        String refundNote,
        /** 1.3.0：报名须知（多行文字），没有时为 null。 */
        String enrollmentNotice,
        boolean waitlistAllowed,
        boolean teamEnrollmentAllowed,
        List<ActivitySessionView> sessions
) {
}

record ActivitySessionView(
        UUID sessionId,
        String label,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime checkInOpensAt,
        OffsetDateTime enrollmentClosesAt,
        int capacity,
        int seatsLeft,
        ActivityEnrollmentStatus status,
        /** 1.3.0：运营停售了这一场（status 读成 CLOSED）。 */
        boolean saleStopped
) {
}

record ActivityMediaView(String url, String alt) {
}

/** onlinePayment：现在能不能在线报名付款（1.2.0，见 toPrice）。 */
record ActivityPriceView(ActivityPriceMode mode, Long amountMinor, String currency, boolean onlinePayment) {
}

record ActivityAgeRangeView(Integer minAge, Integer maxAge) {
}

record ActivityOrganizerView(UUID organizationId, String name) {
}

record ActivityLocationView(String name, String address, Double latitude, Double longitude) {
}
