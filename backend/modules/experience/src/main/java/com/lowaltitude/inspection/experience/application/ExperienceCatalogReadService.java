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
import java.util.function.Predicate;

import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueProjectRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueRow;

/**
 * 体验频道只读服务：线上体验项目、场馆列表与详情、场次、馆内设备（契约 contracts/openapi-experience.yaml）。
 * 场次状态在这里按开始时间和余位计算，客户端不自行判断。
 */
public final class ExperienceCatalogReadService {

    private static final int MAX_PAGE_SIZE = 50;
    /** R6-2：商品详情“先体验，再决定”最多列出的线上体验项目和场馆数。 */
    static final int MAX_PRODUCT_EXPERIENCES = 10;
    static final int MAX_PRODUCT_VENUES = 10;
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    /** 查询里“不限”的占位值。 */
    private static final String ALL = "ALL";
    /** 场馆都在境内，“某一天”一律按北京时间划分。 */
    static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Shanghai");

    private final ExperienceCatalogReadStore store;
    private final Clock clock;
    private final Predicate<UUID> operatorCanCollect;
    private final VenueDeviceOfferPort deviceOffers;

    /** 没有接交易模块：项目的 onlinePayment 一律为 false。 */
    ExperienceCatalogReadService(ExperienceCatalogReadStore store, Clock clock) {
        this(store, clock, operatorId -> false);
    }

    ExperienceCatalogReadService(ExperienceCatalogReadStore store, Clock clock, Predicate<UUID> operatorCanCollect) {
        this(store, clock, operatorCanCollect, VenueDeviceOfferPort.none());
    }

    /**
     * @param operatorCanCollect 场馆运营机构能不能在线收款（登记了有效的微信收款账户，E10）；只在有标价大于 0 的项目时才问
     */
    ExperienceCatalogReadService(
            ExperienceCatalogReadStore store,
            Clock clock,
            Predicate<UUID> operatorCanCollect,
            VenueDeviceOfferPort deviceOffers
    ) {
        this.store = store;
        this.clock = clock;
        this.operatorCanCollect = operatorCanCollect;
        this.deviceOffers = deviceOffers;
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

    /** 只从公开目录核对课程选用的仿真项目，模板为空的项目不能进入仿真。 */
    public boolean isPlayableSimulation(UUID itemId) {
        return store.findExperienceItem(itemId)
                .filter(row -> ExperienceType.SIMULATION.name().equals(row.type()) && row.simulationTemplate() != null)
                .isPresent();
    }

    /** S5-4：一个已发布的线上体验项目，带仿真模板和试用时长；没有或没发布时 404。 */
    public ExperienceItemDetailView findExperienceItem(UUID experienceId) {
        if (experienceId == null) {
            throw new IllegalArgumentException("experience id is required");
        }
        var row = store.findExperienceItem(experienceId).orElseThrow(VenueNotFoundException::new);
        return new ExperienceItemDetailView(
                row.experienceId(),
                ExperienceType.valueOf(row.type()),
                row.title(),
                blankToNull(row.summary()),
                secureMedia(row.coverUrl(), row.title()),
                row.loginRequired(),
                row.productId(),
                row.simulationTemplate() == null ? null : SimulationTemplate.valueOf(row.simulationTemplate()),
                row.trialMinutes());
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
                        row.openSessionsToday(),
                        blankToNull(row.projectSummary())))
                .toList();
        return new VenuePageView(page, size, total, hasMore(page, size, total), items);
    }

    public VenueDetailView findVenue(UUID venueId, String cityCode) {
        VenueRow venue = requireVenue(venueId, cityCode);
        List<VenueProjectRow> projects = store.findProjects(venueId);
        // 运营机构能不能收款只问一次，而且只在有要付款的项目时问。
        boolean canCollect = projects.stream().anyMatch(project -> project.priceAmountMinor() > 0)
                && operatorCanCollect.test(venue.operatorId());
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
                projects.stream()
                        .map(row -> new VenueProjectView(
                                row.projectId(),
                                row.name(),
                                row.durationMinutes(),
                                row.minAge(),
                                row.capacity(),
                                blankToNull(row.limitNote()),
                                row.priceAmountMinor(),
                                row.currency() == null ? "CNY" : row.currency().trim(),
                                // E10：标价大于 0 并且运营机构能在线收款时为 true；标价为 0 的不用付款，为 false。
                                row.priceAmountMinor() > 0 && canCollect))
                        .toList(),
                store.findInfoItems(venueId).stream()
                        .map(row -> new VenueInfoItemView(row.label(), row.content()))
                        .toList(),
                venue.deviceCount(),
                new VenueCheckInRuleView(venue.checkinOpensMinutes(), venue.lateEntryMinutes()));
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
                        VenueDeviceTrialMode.valueOf(row.trialMode()),
                        deviceProduct(row.productId(), cityCode)))
                .toList();
        return new VenueDevicePageView(page, size, total, hasMore(page, size, total), items);
    }

    /** 设备关联的商品在这个城市怎么卖：经商城端口问，一页里每件商品只问一次以内。 */
    private VenueDeviceProductView deviceProduct(UUID productId, String cityCode) {
        if (productId == null) {
            return null;
        }
        return deviceOffers.offer(productId, cityCode)
                .filter(offer -> offer.sellerCount() > 0)
                .map(offer -> new VenueDeviceProductView(
                        productId,
                        offer.saleMode(),
                        offer.fromAmountMinor(),
                        offer.priceUnit(),
                        offer.multiplePrices(),
                        offer.sellerCount()))
                .orElse(null);
    }

    /**
     * R6-2 先体验：这件商城商品能在哪体验。线上体验项目不分城市，只给已发布的；场馆只给这个城市已发布的、
     * 馆内有启用的设备关联了这件商品的。都没有时两个列表都是空的（不是 404）：商品是否存在由商城模块判断。
     */
    public ProductExperiencesView findProductExperiences(UUID productId, String cityCode) {
        if (productId == null) {
            throw new IllegalArgumentException("product id is required");
        }
        requireCityCode(cityCode);
        List<ProductExperienceItemView> experiences = store
                .findProductExperiences(productId, MAX_PRODUCT_EXPERIENCES)
                .stream()
                .map(row -> new ProductExperienceItemView(
                        row.experienceId(),
                        ExperienceType.valueOf(row.type()),
                        row.title(),
                        blankToNull(row.summary())))
                .toList();
        List<ProductExperienceVenueView> venues = store
                .findProductVenues(productId, cityCode, MAX_PRODUCT_VENUES)
                .stream()
                .map(row -> new ProductExperienceVenueView(row.venueId(), row.venueName(), row.deviceName()))
                .toList();
        return new ProductExperiencesView(productId, experiences, venues);
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

/** S5-4：仿真页、试用页用的项目详情。simulationTemplate 只有仿真有，trialMinutes 只有试用和试听有。 */
record ExperienceItemDetailView(
        UUID experienceId,
        ExperienceType type,
        String title,
        String summary,
        ExperienceMediaView coverImage,
        boolean loginRequired,
        UUID productId,
        SimulationTemplate simulationTemplate,
        Integer trialMinutes
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
        long openSessionsToday,
        // 1.5.0（S4-4）：卡片第一行的项目摘要，如“模拟飞行 · 机器人编程 · 厂家展区”；没有时为 null。
        String projectSummary
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
        String currency,
        /** 1.1.0：能不能在线预约付款（标价大于 0 且运营机构能在线收款）；标价为 0 的为 false，提交即预约成功。 */
        boolean onlinePayment
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
        long deviceCount,
        /** 1.3.0（S3-1）：入口检票与迟到规则，运营端维护。 */
        VenueCheckInRuleView checkIn
) {
}

/**
 * 1.3.0（S3-1）：检票从开场前 opensMinutesBeforeStart 分钟开始；开场后 lateEntryMinutes 分钟内还能入场，
 * 为 null 表示到场次结束都能入场。核销接口按同样的规则判断。
 */
record VenueCheckInRuleView(int opensMinutesBeforeStart, Integer lateEntryMinutes) {
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
        VenueDeviceTrialMode trialMode,
        // 1.5.0（S4-4）：关联商品在场馆所在城市的价格和在售家数；没关联或这个城市没有在售时为 null。
        VenueDeviceProductView product
) {
}

record VenueDeviceProductView(
        UUID productId,
        VenueDeviceOfferPort.SaleMode saleMode,
        Long fromAmountMinor,
        VenueDeviceOfferPort.PriceUnit priceUnit,
        boolean multiplePrices,
        long sellerCount
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

/** R6-2：商品详情“先体验”里的一个线上体验项目。summary 是运营写的时长说明，如“约 3 分钟 · 免登录可试”。 */
record ProductExperienceItemView(
        UUID experienceId,
        ExperienceType type,
        String title,
        String summary
) {
}

/** R6-2：本城有这件商品可看的场馆，deviceName 是馆内那件设备的名称。 */
record ProductExperienceVenueView(UUID venueId, String name, String deviceName) {
}

record ProductExperiencesView(
        UUID productId,
        List<ProductExperienceItemView> experiences,
        List<ProductExperienceVenueView> venues
) {
}
