package com.lowaltitude.inspection.experience.application.ops;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.experience.application.VenueDeviceCategory;
import com.lowaltitude.inspection.experience.application.VenueDeviceTrialMode;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.DeviceDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.InfoItemDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueDetailView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueDeviceView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueInfoItemView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenuePageView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueProjectView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueSessionView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueSessionsGeneratedView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueSummaryView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.ProjectDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.SessionPlan;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.VenueDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.VenueItemStatus;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.VenueOpsStatus;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.VenueSessionOpsStatus;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueOpsMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueOpsMapper.DeviceRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueOpsMapper.ProjectRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueOpsMapper.SessionRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueOpsMapper.VenueRow;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“场馆与设备”（S3-1，契约 contracts/openapi-ops-venue.yaml 1.0.0）：场馆（运营机构、城市、地址、营业时间、
 * 检票与迟到规则、信息条目）、项目、按周模板批量生成场次、停售与取消场次、馆内设备及关联商品、上线与下线。
 * 只有平台运营能调（控制器检查角色）；每个写操作都在 venue_ops_change 里记一条（操作人显示名、时间、说明）。
 * <ul>
 *   <li>上线前必填：封面、营业时间、至少一个启用的项目、至少一场没开始的在售场次（缺的键放在 details.missing）。
 *       已上线的场馆改资料时封面和营业时间不能清掉，最后一个启用的项目不能停用。</li>
 *   <li>场次：有有效预约（待付款、待到馆、已核销）的只能停售，不能取消；开始后都不能再改。</li>
 *   <li>营业时间按北京时间；保存时由开门、关门时间和休息日说明生成 App 上显示的那一行（opening_note）。</li>
 * </ul>
 */
public class VenueOpsService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_KEYWORD = 20;
    public static final int MAX_NAME = 120;
    public static final int MAX_ADDRESS = 240;
    public static final int MAX_PHONE = 32;
    public static final int MAX_CLOSED_NOTE = 40;
    /** S4-4：首页场馆卡的项目摘要，如“模拟飞行 · 机器人编程 · 厂家展区”。 */
    public static final int MAX_PROJECT_SUMMARY = 40;
    public static final int MAX_INFO_ITEMS = 10;
    public static final int MAX_INFO_LABEL = 40;
    public static final int MAX_INFO_CONTENT = 200;
    public static final int MAX_CHECKIN_MINUTES = 240;
    public static final int DEFAULT_CHECKIN_MINUTES = 30;
    public static final int MIN_DURATION = 5;
    public static final int MAX_DURATION = 480;
    public static final long MAX_PRICE_MINOR = 10_000_000L;
    public static final int MAX_AGE = 99;
    public static final int MAX_CAPACITY = 999;
    public static final int MAX_LIMIT_NOTE = 200;
    public static final int MAX_SUMMARY = 200;
    public static final int MAX_SORT_ORDER = 9999;
    public static final int MAX_NOTE = 200;
    public static final int MAX_SESSIONS_PER_BATCH = 200;
    public static final int MAX_RANGE_DAYS = 62;
    public static final int MAX_START_TIMES = 12;
    public static final int SESSION_LIST_LIMIT = 500;
    public static final int CHANGE_HISTORY_LIMIT = 100;

    /** 场馆都在境内：周模板、日期、营业时间一律按北京时间。 */
    static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern HHMM = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+\\- ]{5,32}$");
    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM/dd");
    private static final String[] WEEKDAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    // 上线前必填项的字段键（契约 OpsVenuePublishField），按页面上的顺序。
    static final String MISSING_COVER = "coverUrl";
    static final String MISSING_HOURS = "openingHours";
    static final String MISSING_PROJECTS = "projects";
    static final String MISSING_SESSIONS = "sessions";

    private final VenueOpsMapper mapper;
    private final VenueOpsPorts.Cities cities;
    private final VenueOpsPorts.Operators operators;
    private final VenueOpsPorts.Products products;
    private final Clock clock;

    public VenueOpsService(VenueOpsMapper mapper, VenueOpsPorts.Cities cities, VenueOpsPorts.Operators operators,
                           VenueOpsPorts.Products products) {
        this(mapper, cities, operators, products, Clock.systemUTC());
    }

    VenueOpsService(VenueOpsMapper mapper, VenueOpsPorts.Cities cities, VenueOpsPorts.Operators operators,
                    VenueOpsPorts.Products products, Clock clock) {
        this.mapper = mapper;
        this.cities = cities;
        this.operators = operators;
        this.products = products;
        this.clock = clock;
    }

    /** 新建的结果：第一次 created 为 true；同一个幂等键再提交返回第一次建的，created 为 false。 */
    public record Created(OpsVenueDetailView venue, boolean created) {
    }

    /** 操作人：账号 ID 和显示名（写进改动记录）。 */
    public record Operator(UUID accountId, String displayName) {
        public Operator {
            Objects.requireNonNull(accountId, "accountId");
            displayName = OpsText.operatorName(displayName);
        }
    }

    // ---- 场馆 ----

    @Transactional(readOnly = true)
    public OpsVenuePageView venues(String keyword, String cityCode, String status, int page, int size) {
        OpsText.page(page, size, MAX_PAGE_SIZE);
        String filterKeyword = OpsText.keyword(keyword, MAX_KEYWORD);
        String filterCity = cityCode == null || cityCode.isBlank() ? null : cityCode(cityCode);
        VenueOpsStatus filterStatus = OpsText.optionalEnum(VenueOpsStatus.class, status, "status");
        String statusCode = filterStatus == null ? null : filterStatus.name();
        long total = mapper.countVenues(filterKeyword, filterCity, statusCode);
        List<OpsVenueSummaryView> items = mapper.findVenues(filterKeyword, filterCity, statusCode, now(), size,
                        Math.multiplyExact(page - 1, size)).stream()
                .map(row -> new OpsVenueSummaryView(row.venueId(), row.name(), row.cityCode(), row.operatorId(),
                        row.operatorName(), VenueOpsStatus.valueOf(row.status()), row.sortOrder(),
                        row.projectCount(), row.upcomingSessionCount(), row.updatedAt(), row.version()))
                .toList();
        return new OpsVenuePageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public OpsVenueDetailView venue(UUID venueId) {
        return detail(requireVenue(mapper.findVenue(require(venueId))));
    }

    @Transactional
    public Created createVenue(UUID idempotencyKey, VenueDraft draft, Operator operator) {
        requireKey(idempotencyKey);
        UUID existing = mapper.findVenueByCreateKey(idempotencyKey);
        if (existing != null) {
            return new Created(venue(existing), false);
        }
        CleanVenue clean = cleanVenue(draft, null);
        UUID venueId = UUID.randomUUID();
        OffsetDateTime at = now();
        mapper.insertVenue(venueId, clean.operatorId, clean.name, clean.coverUrl, clean.cityCode, clean.address,
                clean.phone, clean.opensAt, clean.closesAt, clean.closedNote, clean.openingNote,
                clean.checkinOpensMinutes, clean.lateEntryMinutes, clean.sortOrder, idempotencyKey,
                clean.projectSummary, at);
        replaceInfoItems(venueId, clean.infoItems, at);
        record(venueId, "VENUE", venueId, "CREATE", "新建场馆草稿“" + clean.name + "”", operator, at);
        return new Created(venue(venueId), true);
    }

    @Transactional
    public OpsVenueDetailView updateVenue(UUID venueId, VenueDraft draft, Integer version, Operator operator) {
        VenueRow current = lockVersioned(venueId, version);
        CleanVenue clean = cleanVenue(draft, current);
        if (VenueOpsStatus.PUBLISHED.name().equals(current.status())) {
            List<String> missing = new ArrayList<>();
            if (clean.coverUrl == null) {
                missing.add(MISSING_COVER);
            }
            if (clean.opensAt == null) {
                missing.add(MISSING_HOURS);
            }
            if (!missing.isEmpty()) {
                throw OpsException.incomplete(VenueOpsError.PUBLISH_INCOMPLETE, missing);
            }
        }
        OffsetDateTime at = now();
        if (mapper.updateVenue(venueId, clean.operatorId, clean.name, clean.coverUrl, clean.cityCode, clean.address,
                clean.phone, clean.opensAt, clean.closesAt, clean.closedNote, clean.openingNote,
                clean.checkinOpensMinutes, clean.lateEntryMinutes, clean.sortOrder, clean.projectSummary,
                version, at) != 1) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        replaceInfoItems(venueId, clean.infoItems, at);
        StringBuilder summary = new StringBuilder("修改场馆资料");
        if (!clean.operatorId.equals(current.operatorId())) {
            summary.append("，运营机构改为“").append(clean.operatorName).append("”");
        }
        if (!clean.cityCode.equals(current.cityCode())) {
            summary.append("，城市改为").append(clean.cityName);
        }
        record(venueId, "VENUE", venueId, "UPDATE", summary.toString(), operator, at);
        return venue(venueId);
    }

    @Transactional
    public OpsVenueDetailView publish(UUID venueId, Integer version, String note, Operator operator) {
        VenueRow current = lockVersioned(venueId, version);
        String reason = OpsText.optional(note, MAX_NOTE, "note");
        VenueOpsStatus status = VenueOpsStatus.valueOf(current.status());
        if (status == VenueOpsStatus.PUBLISHED) {
            throw new OpsException(VenueOpsError.STATUS_CONFLICT);
        }
        OffsetDateTime at = now();
        List<String> missing = publishMissing(current, at);
        if (!missing.isEmpty()) {
            throw OpsException.incomplete(VenueOpsError.PUBLISH_INCOMPLETE, missing);
        }
        if (mapper.updateVenueStatus(venueId, status.name(), VenueOpsStatus.PUBLISHED.name(), at, version, at) != 1) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        record(venueId, "VENUE", venueId, "PUBLISH", withNote("上线", reason), operator, at);
        return venue(venueId);
    }

    @Transactional
    public OpsVenueDetailView offline(UUID venueId, Integer version, String note, Operator operator) {
        VenueRow current = lockVersioned(venueId, version);
        String reason = OpsText.optional(note, MAX_NOTE, "note");
        if (!VenueOpsStatus.PUBLISHED.name().equals(current.status())) {
            throw new OpsException(VenueOpsError.STATUS_CONFLICT);
        }
        OffsetDateTime at = now();
        if (mapper.updateVenueStatus(venueId, current.status(), VenueOpsStatus.OFFLINE.name(), null, version,
                at) != 1) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        record(venueId, "VENUE", venueId, "OFFLINE", withNote("下线（App 不再显示，已有预约照常）", reason), operator, at);
        return venue(venueId);
    }

    @Transactional(readOnly = true)
    public List<OpsChangeView> changes(UUID venueId) {
        requireVenue(mapper.findVenue(require(venueId)));
        return mapper.findChanges(venueId, CHANGE_HISTORY_LIMIT).stream()
                .map(row -> new OpsChangeView(row.changeId(), row.subjectType(), row.subjectId(), row.action(),
                        row.summary(), row.operatorName(), row.changedAt()))
                .toList();
    }

    // ---- 项目 ----

    @Transactional
    public Created createProject(UUID venueId, UUID idempotencyKey, ProjectDraft draft, Operator operator) {
        requireKey(idempotencyKey);
        requireVenue(mapper.lockVenue(require(venueId)));
        UUID existing = mapper.findProjectByCreateKey(venueId, idempotencyKey);
        if (existing != null) {
            return new Created(venue(venueId), false);
        }
        CleanProject clean = cleanProject(draft);
        UUID projectId = UUID.randomUUID();
        OffsetDateTime at = now();
        mapper.insertProject(projectId, venueId, clean.name, clean.durationMinutes, clean.minAge, clean.capacity,
                clean.limitNote, clean.priceAmountMinor, clean.sortOrder, idempotencyKey, at);
        mapper.touchVenue(venueId, at);
        record(venueId, "PROJECT", projectId, "CREATE", "新建项目“" + clean.name + "”（" + describe(clean) + "）",
                operator, at);
        return new Created(venue(venueId), true);
    }

    @Transactional
    public OpsVenueDetailView updateProject(UUID venueId, UUID projectId, ProjectDraft draft, Integer version,
                                            Operator operator) {
        requireVenue(mapper.lockVenue(require(venueId)));
        ProjectRow current = lockProject(venueId, projectId, version);
        CleanProject clean = cleanProject(draft);
        OffsetDateTime at = now();
        if (mapper.updateProject(venueId, projectId, clean.name, clean.durationMinutes, clean.minAge, clean.capacity,
                clean.limitNote, clean.priceAmountMinor, clean.sortOrder, version, at) != 1) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        mapper.touchVenue(venueId, at);
        String summary = "修改项目“" + current.name() + "”（" + describe(clean) + "）"
                + (current.priceAmountMinor() == clean.priceAmountMinor ? "" : "，价格改动只影响之后的新预约");
        record(venueId, "PROJECT", projectId, "UPDATE", summary, operator, at);
        return venue(venueId);
    }

    @Transactional
    public OpsVenueDetailView changeProjectStatus(UUID venueId, UUID projectId, String status, Integer version,
                                                  Operator operator) {
        VenueRow venue = requireVenue(mapper.lockVenue(require(venueId)));
        VenueItemStatus target = OpsText.enumValue(VenueItemStatus.class, status, "status");
        ProjectRow current = lockProject(venueId, projectId, version);
        if (VenueItemStatus.valueOf(current.status()) == target) {
            throw new OpsException(VenueOpsError.STATUS_CONFLICT);
        }
        if (target == VenueItemStatus.DISABLED && VenueOpsStatus.PUBLISHED.name().equals(venue.status())
                && mapper.countActiveProjects(venueId) <= 1) {
            throw OpsException.incomplete(VenueOpsError.PUBLISH_INCOMPLETE, List.of(MISSING_PROJECTS));
        }
        OffsetDateTime at = now();
        if (mapper.updateProjectStatus(venueId, projectId, target.name(), version, at) != 1) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        mapper.touchVenue(venueId, at);
        boolean enable = target == VenueItemStatus.ACTIVE;
        record(venueId, "PROJECT", projectId, enable ? "ENABLE" : "DISABLE",
                (enable ? "启用项目“" : "停用项目“") + current.name() + "”" + (enable ? "" : "（不再接新预约，已有预约照常）"),
                operator, at);
        return venue(venueId);
    }

    // ---- 场次 ----

    /** 一个项目在 from–to（北京时间日期，含两端；不传时从今天起 14 天）里的场次，含停售和已取消的。 */
    @Transactional(readOnly = true)
    public List<OpsVenueSessionView> sessions(UUID venueId, UUID projectId, String from, String to) {
        requireVenue(mapper.findVenue(require(venueId)));
        require(projectId);
        OffsetDateTime now = now();
        if (mapper.findProjects(venueId, now).stream().noneMatch(project -> project.projectId().equals(projectId))) {
            throw new OpsException(VenueOpsError.NOT_FOUND);
        }
        LocalDate today = now.atZoneSameInstant(VENUE_ZONE).toLocalDate();
        LocalDate fromDate = from == null || from.isBlank() ? today : date(from, "from");
        LocalDate toDate = to == null || to.isBlank() ? fromDate.plusDays(13) : date(to, "to");
        requireRange(fromDate, toDate);
        return mapper.findSessions(venueId, projectId, startOf(fromDate), startOf(toDate.plusDays(1)),
                        SESSION_LIST_LIMIT).stream()
                .map(VenueOpsService::session)
                .toList();
    }

    @Transactional
    public OpsVenueSessionsGeneratedView generateSessions(UUID venueId, UUID projectId, SessionPlan plan,
                                                          Operator operator) {
        requireVenue(mapper.lockVenue(require(venueId)));
        require(projectId);
        OffsetDateTime now = now();
        ProjectRow project = mapper.lockProject(venueId, projectId, now);
        if (project == null) {
            throw new OpsException(VenueOpsError.NOT_FOUND);
        }
        if (plan == null) {
            throw new IllegalArgumentException("plan is required");
        }
        LocalDate fromDate = date(plan.fromDate(), "fromDate");
        LocalDate toDate = date(plan.toDate(), "toDate");
        requireRange(fromDate, toDate);
        Set<DayOfWeek> weekdays = weekdays(plan.weekdays());
        List<LocalTime> times = startTimes(plan.startTimes());
        int capacity = plan.capacity() == null ? project.capacity() : bounded(plan.capacity(), 1, MAX_CAPACITY,
                "capacity");

        List<OffsetDateTime> slots = expand(fromDate, toDate, weekdays, times);
        Set<Instant> existing = mapper.findSessionStarts(projectId, startOf(fromDate), startOf(toDate.plusDays(1)))
                .stream().map(OffsetDateTime::toInstant).collect(Collectors.toCollection(HashSet::new));
        List<OffsetDateTime> toCreate = new ArrayList<>();
        int skippedExisting = 0;
        int skippedPast = 0;
        for (OffsetDateTime startsAt : slots) {
            if (!startsAt.isAfter(now)) {
                skippedPast++;
            } else if (existing.contains(startsAt.toInstant())) {
                skippedExisting++;
            } else {
                toCreate.add(startsAt);
            }
        }
        if (toCreate.size() > MAX_SESSIONS_PER_BATCH) {
            throw new OpsException(VenueOpsError.TOO_MANY_SESSIONS);
        }
        for (OffsetDateTime startsAt : toCreate) {
            mapper.insertSession(projectId, startsAt, startsAt.plusMinutes(project.durationMinutes()), capacity, now);
        }
        if (!toCreate.isEmpty()) {
            mapper.touchVenue(venueId, now);
            String summary = "按周模板给“" + project.name() + "”生成 " + toCreate.size() + " 场："
                    + DAY.format(fromDate) + "–" + DAY.format(toDate) + "，"
                    + weekdays.stream().map(day -> WEEKDAY_LABELS[day.getValue() - 1]).collect(Collectors.joining("、"))
                    + "，" + times.stream().map(LocalTime::toString).collect(Collectors.joining("、"))
                    + "，每场 " + capacity + " 人"
                    + (skippedExisting + skippedPast == 0 ? ""
                    : "（跳过已有 " + skippedExisting + " 场、已过 " + skippedPast + " 场）");
            record(venueId, "PROJECT", projectId, "GENERATE", summary, operator, now);
        }
        return new OpsVenueSessionsGeneratedView(toCreate.size(), skippedExisting, skippedPast);
    }

    @Transactional
    public OpsVenueSessionView stopSale(UUID venueId, UUID sessionId, Integer version, Operator operator) {
        return changeSession(venueId, sessionId, version, operator, SessionAction.STOP_SALE);
    }

    @Transactional
    public OpsVenueSessionView resumeSale(UUID venueId, UUID sessionId, Integer version, Operator operator) {
        return changeSession(venueId, sessionId, version, operator, SessionAction.RESUME_SALE);
    }

    @Transactional
    public OpsVenueSessionView cancelSession(UUID venueId, UUID sessionId, Integer version, Operator operator) {
        return changeSession(venueId, sessionId, version, operator, SessionAction.CANCEL);
    }

    private enum SessionAction {
        STOP_SALE("停售"),
        RESUME_SALE("恢复售卖"),
        CANCEL("取消");

        private final String label;

        SessionAction(String label) {
            this.label = label;
        }
    }

    private OpsVenueSessionView changeSession(UUID venueId, UUID sessionId, Integer version, Operator operator,
                                              SessionAction action) {
        requireVersion(version);
        require(venueId);
        require(sessionId);
        SessionRow session = mapper.lockSession(venueId, sessionId);
        if (session == null) {
            throw new OpsException(VenueOpsError.NOT_FOUND);
        }
        if (session.version() != version) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        OffsetDateTime now = now();
        VenueSessionOpsStatus status = VenueSessionOpsStatus.valueOf(session.status());
        VenueSessionOpsStatus target = switch (action) {
            case STOP_SALE -> {
                requireStatus(status == VenueSessionOpsStatus.ACTIVE);
                yield VenueSessionOpsStatus.STOPPED;
            }
            case RESUME_SALE -> {
                requireStatus(status == VenueSessionOpsStatus.STOPPED);
                yield VenueSessionOpsStatus.ACTIVE;
            }
            case CANCEL -> {
                requireStatus(status != VenueSessionOpsStatus.CANCELLED);
                yield VenueSessionOpsStatus.CANCELLED;
            }
        };
        if (!now.isBefore(session.startsAt())) {
            throw new OpsException(VenueOpsError.SESSION_STARTED);
        }
        if (action == SessionAction.CANCEL && (session.activeBookingCount() > 0 || session.bookedCount() > 0)) {
            throw new OpsException(VenueOpsError.SESSION_HAS_BOOKINGS);
        }
        if (mapper.updateSessionStatus(sessionId, status.name(), target.name(), version, now) != 1) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        mapper.touchVenue(venueId, now);
        String when = WHEN.format(session.startsAt().atZoneSameInstant(VENUE_ZONE));
        String summary = action.label + " " + when + " 的场次"
                + (action == SessionAction.STOP_SALE && session.activeBookingCount() > 0
                ? "（已有 " + session.activeBookingCount() + " 单预约照常有效）" : "");
        record(venueId, "SESSION", sessionId, action.name(), summary, operator, now);
        return session(mapper.lockSession(venueId, sessionId));
    }

    // ---- 馆内设备 ----

    @Transactional
    public Created createDevice(UUID venueId, UUID idempotencyKey, DeviceDraft draft, Operator operator) {
        requireKey(idempotencyKey);
        VenueRow venue = requireVenue(mapper.lockVenue(require(venueId)));
        UUID existing = mapper.findDeviceByCreateKey(venueId, idempotencyKey);
        if (existing != null) {
            return new Created(venue(venueId), false);
        }
        CleanDevice clean = cleanDevice(draft, venue.cityCode(), null);
        UUID deviceId = UUID.randomUUID();
        OffsetDateTime at = now();
        mapper.insertDevice(deviceId, venueId, clean.category.name(), clean.name, clean.summary, clean.imageUrl,
                clean.trialMode.name(), clean.productId, clean.sortOrder, idempotencyKey, at);
        mapper.touchVenue(venueId, at);
        record(venueId, "DEVICE", deviceId, "CREATE", "新增设备“" + clean.name + "”"
                + (clean.productId == null ? "" : "，关联商品 " + clean.productId), operator, at);
        return new Created(venue(venueId), true);
    }

    @Transactional
    public OpsVenueDetailView updateDevice(UUID venueId, UUID deviceId, DeviceDraft draft, Integer version,
                                           Operator operator) {
        VenueRow venue = requireVenue(mapper.lockVenue(require(venueId)));
        DeviceRow current = lockDevice(venueId, deviceId, version);
        CleanDevice clean = cleanDevice(draft, venue.cityCode(), current.imageUrl());
        OffsetDateTime at = now();
        if (mapper.updateDevice(venueId, deviceId, clean.category.name(), clean.name, clean.summary, clean.imageUrl,
                clean.trialMode.name(), clean.productId, clean.sortOrder, version, at) != 1) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        mapper.touchVenue(venueId, at);
        String summary = "修改设备“" + current.name() + "”"
                + (Objects.equals(current.productId(), clean.productId) ? ""
                : clean.productId == null ? "，取消关联商品" : "，关联商品改为 " + clean.productId);
        record(venueId, "DEVICE", deviceId, "UPDATE", summary, operator, at);
        return venue(venueId);
    }

    @Transactional
    public OpsVenueDetailView changeDeviceStatus(UUID venueId, UUID deviceId, String status, Integer version,
                                                 Operator operator) {
        requireVenue(mapper.lockVenue(require(venueId)));
        VenueItemStatus target = OpsText.enumValue(VenueItemStatus.class, status, "status");
        DeviceRow current = lockDevice(venueId, deviceId, version);
        if (VenueItemStatus.valueOf(current.status()) == target) {
            throw new OpsException(VenueOpsError.STATUS_CONFLICT);
        }
        OffsetDateTime at = now();
        if (mapper.updateDeviceStatus(venueId, deviceId, target.name(), version, at) != 1) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        mapper.touchVenue(venueId, at);
        boolean enable = target == VenueItemStatus.ACTIVE;
        record(venueId, "DEVICE", deviceId, enable ? "ENABLE" : "DISABLE",
                (enable ? "启用设备“" : "停用设备“") + current.name() + "”", operator, at);
        return venue(venueId);
    }

    // ---- 周模板 ----

    /** 把周模板展开成开始时间（北京时间），按日期、时间先后。 */
    static List<OffsetDateTime> expand(LocalDate fromDate, LocalDate toDate, Set<DayOfWeek> weekdays,
                                       List<LocalTime> times) {
        List<OffsetDateTime> slots = new ArrayList<>();
        for (LocalDate day = fromDate; !day.isAfter(toDate); day = day.plusDays(1)) {
            if (!weekdays.contains(day.getDayOfWeek())) {
                continue;
            }
            for (LocalTime time : times) {
                slots.add(day.atTime(time).atZone(VENUE_ZONE).toOffsetDateTime()
                        .withOffsetSameInstant(ZoneOffset.UTC));
            }
        }
        return slots;
    }

    static Set<DayOfWeek> weekdays(List<String> codes) {
        if (codes == null || codes.isEmpty() || codes.size() > 7) {
            throw new IllegalArgumentException("weekdays are required");
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String code : codes) {
            if (!days.add(OpsText.enumValue(DayOfWeek.class, code, "weekdays"))) {
                throw new IllegalArgumentException("weekdays repeat");
            }
        }
        return days;
    }

    static List<LocalTime> startTimes(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_START_TIMES) {
            throw new IllegalArgumentException("startTimes are required");
        }
        Set<LocalTime> times = new LinkedHashSet<>();
        for (String value : values) {
            if (!times.add(time(value, "startTimes"))) {
                throw new IllegalArgumentException("startTimes repeat");
            }
        }
        return times.stream().sorted().toList();
    }

    // ---- 整理输入 ----

    private record CleanVenue(UUID operatorId, String operatorName, String name, String cityCode, String cityName,
                              String coverUrl, String address, String phone, String opensAt, String closesAt,
                              String closedNote, String openingNote, int checkinOpensMinutes,
                              Integer lateEntryMinutes, int sortOrder, List<InfoItemDraft> infoItems,
                              String projectSummary) {
    }

    private CleanVenue cleanVenue(VenueDraft draft, VenueRow current) {
        if (draft == null) {
            throw new IllegalArgumentException("venue is required");
        }
        if (draft.operatorId() == null) {
            throw new IllegalArgumentException("operatorId is required");
        }
        String operatorName = operators.providerName(draft.operatorId())
                .orElseThrow(() -> new OpsException(VenueOpsError.OPERATOR_INVALID));
        String name = OpsText.required(draft.name(), MAX_NAME, "name");
        String cityCode = cityCode(draft.cityCode());
        String cityName = cities.cityName(cityCode).orElseThrow(() -> new OpsException(VenueOpsError.CITY_UNKNOWN));
        String coverUrl = OpsText.mediaUrl(draft.coverUrl(), current == null ? null : current.coverUrl(), "coverUrl");
        String address = OpsText.required(draft.address(), MAX_ADDRESS, "address");
        String phone = OpsText.optional(draft.phone(), MAX_PHONE, "phone");
        if (phone != null && !PHONE.matcher(phone).matches()) {
            throw new IllegalArgumentException("phone is invalid");
        }
        String opensAt = draft.opensAt() == null || draft.opensAt().isBlank() ? null : hhmm(draft.opensAt(), "opensAt");
        String closesAt = draft.closesAt() == null || draft.closesAt().isBlank() ? null
                : hhmm(draft.closesAt(), "closesAt");
        if ((opensAt == null) != (closesAt == null) || (opensAt != null && opensAt.compareTo(closesAt) >= 0)) {
            throw new IllegalArgumentException("opening hours are invalid");
        }
        String closedNote = OpsText.optional(draft.closedNote(), MAX_CLOSED_NOTE, "closedNote");
        // App 上那一行营业时间：有开门、关门时间时由它们生成；老数据没填结构化字段时保持原来的文字。
        String openingNote = opensAt != null ? openingNote(opensAt, closesAt, closedNote)
                : current == null ? null : current.openingNote();
        int checkinOpens = draft.checkinOpensMinutes() == null ? DEFAULT_CHECKIN_MINUTES
                : bounded(draft.checkinOpensMinutes(), 0, MAX_CHECKIN_MINUTES, "checkinOpensMinutes");
        Integer lateEntry = draft.lateEntryMinutes() == null ? null
                : bounded(draft.lateEntryMinutes(), 0, MAX_CHECKIN_MINUTES, "lateEntryMinutes");
        int sortOrder = draft.sortOrder() == null ? 0 : bounded(draft.sortOrder(), 0, MAX_SORT_ORDER, "sortOrder");
        List<InfoItemDraft> items = draft.infoItems() == null ? List.of() : draft.infoItems();
        if (items.size() > MAX_INFO_ITEMS) {
            throw new IllegalArgumentException("too many info items");
        }
        List<InfoItemDraft> infoItems = items.stream()
                .map(item -> {
                    if (item == null) {
                        throw new IllegalArgumentException("info item is required");
                    }
                    return new InfoItemDraft(OpsText.required(item.label(), MAX_INFO_LABEL, "label"),
                            OpsText.required(item.content(), MAX_INFO_CONTENT, "content"));
                })
                .toList();
        // S4-4：首页“附近场馆”卡片的项目摘要；不填时 App 用启用的前三个项目名。
        String projectSummary = OpsText.optional(draft.projectSummary(), MAX_PROJECT_SUMMARY, "projectSummary");
        return new CleanVenue(draft.operatorId(), operatorName, name, cityCode, cityName, coverUrl, address, phone,
                opensAt, closesAt, closedNote, openingNote, checkinOpens, lateEntry, sortOrder, infoItems,
                projectSummary);
    }

    /** “09:30–18:00 · 周一闭馆”。 */
    static String openingNote(String opensAt, String closesAt, String closedNote) {
        return opensAt + "–" + closesAt + (closedNote == null ? "" : " · " + closedNote);
    }

    private record CleanProject(String name, int durationMinutes, long priceAmountMinor, Integer minAge,
                                int capacity, String limitNote, int sortOrder) {
    }

    private static CleanProject cleanProject(ProjectDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("project is required");
        }
        if (draft.durationMinutes() == null || draft.priceAmountMinor() == null || draft.capacity() == null) {
            throw new IllegalArgumentException("project fields are required");
        }
        long price = draft.priceAmountMinor();
        if (price < 0 || price > MAX_PRICE_MINOR) {
            throw new IllegalArgumentException("price is invalid");
        }
        return new CleanProject(
                OpsText.required(draft.name(), MAX_NAME, "name"),
                bounded(draft.durationMinutes(), MIN_DURATION, MAX_DURATION, "durationMinutes"),
                price,
                draft.minAge() == null ? null : bounded(draft.minAge(), 0, MAX_AGE, "minAge"),
                bounded(draft.capacity(), 1, MAX_CAPACITY, "capacity"),
                OpsText.optional(draft.limitNote(), MAX_LIMIT_NOTE, "limitNote"),
                draft.sortOrder() == null ? 0 : bounded(draft.sortOrder(), 0, MAX_SORT_ORDER, "sortOrder"));
    }

    private static String describe(CleanProject project) {
        return project.durationMinutes + " 分钟 · ¥" + String.format("%d.%02d", project.priceAmountMinor / 100,
                project.priceAmountMinor % 100) + " · 每场 " + project.capacity + " 人";
    }

    private record CleanDevice(VenueDeviceCategory category, String name, String summary, String imageUrl,
                               VenueDeviceTrialMode trialMode, UUID productId, int sortOrder) {
    }

    private CleanDevice cleanDevice(DeviceDraft draft, String cityCode, String existingImage) {
        if (draft == null) {
            throw new IllegalArgumentException("device is required");
        }
        UUID productId = draft.productId();
        if (productId != null && !products.soldInCity(productId, cityCode)) {
            throw new OpsException(VenueOpsError.PRODUCT_UNKNOWN);
        }
        return new CleanDevice(
                OpsText.enumValue(VenueDeviceCategory.class, draft.category(), "category"),
                OpsText.required(draft.name(), MAX_NAME, "name"),
                OpsText.optional(draft.summary(), MAX_SUMMARY, "summary"),
                OpsText.mediaUrl(draft.imageUrl(), existingImage, "imageUrl"),
                OpsText.enumValue(VenueDeviceTrialMode.class, draft.trialMode(), "trialMode"),
                productId,
                draft.sortOrder() == null ? 0 : bounded(draft.sortOrder(), 0, MAX_SORT_ORDER, "sortOrder"));
    }

    // ---- 读出来 ----

    private OpsVenueDetailView detail(VenueRow row) {
        OffsetDateTime now = now();
        List<OpsVenueProjectView> projects = mapper.findProjects(row.venueId(), now).stream()
                .map(project -> new OpsVenueProjectView(project.projectId(), project.name(),
                        project.durationMinutes(), project.priceAmountMinor(), project.minAge(), project.capacity(),
                        project.limitNote(), project.sortOrder(), VenueItemStatus.valueOf(project.status()),
                        project.upcomingSessionCount(), project.nextSessionAt(), project.version()))
                .toList();
        List<OpsVenueDeviceView> devices = mapper.findDevices(row.venueId()).stream()
                .map(device -> new OpsVenueDeviceView(device.deviceId(),
                        VenueDeviceCategory.valueOf(device.category()), device.name(), device.summary(),
                        device.imageUrl(), VenueDeviceTrialMode.valueOf(device.trialMode()), device.productId(),
                        device.sortOrder(), VenueItemStatus.valueOf(device.status()), device.version()))
                .toList();
        return new OpsVenueDetailView(row.venueId(), row.name(), row.operatorId(), row.operatorName(),
                row.cityCode(), cities.cityName(row.cityCode()).orElse(null), row.coverUrl(), row.address(),
                row.phone(), row.opensAt(), row.closesAt(), row.closedNote(), row.openingNote(),
                row.checkinOpensMinutes(), row.lateEntryMinutes(), row.sortOrder(),
                VenueOpsStatus.valueOf(row.status()), row.publishedAt(),
                mapper.findInfoItems(row.venueId()).stream()
                        .map(item -> new OpsVenueInfoItemView(item.label(), item.content()))
                        .toList(),
                projects, devices, publishMissing(row, now), row.updatedAt(), row.version(), row.projectSummary());
    }

    /** 现在上线还差哪些：封面、营业时间、启用的项目、没开始的在售场次。 */
    private List<String> publishMissing(VenueRow row, OffsetDateTime now) {
        List<String> missing = new ArrayList<>();
        if (row.coverUrl() == null || row.coverUrl().isBlank()) {
            missing.add(MISSING_COVER);
        }
        if (row.opensAt() == null) {
            missing.add(MISSING_HOURS);
        }
        if (mapper.countActiveProjects(row.venueId()) == 0) {
            missing.add(MISSING_PROJECTS);
        }
        if (mapper.countUpcomingSessions(row.venueId(), now) == 0) {
            missing.add(MISSING_SESSIONS);
        }
        return missing;
    }

    private static OpsVenueSessionView session(SessionRow row) {
        return new OpsVenueSessionView(row.sessionId(), row.projectId(), row.startsAt(), row.endsAt(), row.capacity(),
                row.bookedCount(), row.activeBookingCount(), VenueSessionOpsStatus.valueOf(row.status()),
                row.version());
    }

    // ---- 小工具 ----

    private void replaceInfoItems(UUID venueId, List<InfoItemDraft> items, OffsetDateTime at) {
        mapper.deleteInfoItems(venueId);
        for (int index = 0; index < items.size(); index++) {
            mapper.insertInfoItem(venueId, items.get(index).label(), items.get(index).content(), (index + 1) * 10, at);
        }
    }

    private void record(UUID venueId, String subjectType, UUID subjectId, String action, String summary,
                        Operator operator, OffsetDateTime at) {
        if (operator == null) {
            throw new IllegalArgumentException("operator is required");
        }
        if (mapper.insertChange(venueId, subjectType, subjectId, action, OpsText.clip(summary, 300),
                operator.accountId(), operator.displayName(), at) != 1) {
            throw new IllegalStateException("venue change was not recorded");
        }
    }

    private VenueRow lockVersioned(UUID venueId, Integer version) {
        requireVersion(version);
        VenueRow current = requireVenue(mapper.lockVenue(require(venueId)));
        if (current.version() != version) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        return current;
    }

    private ProjectRow lockProject(UUID venueId, UUID projectId, Integer version) {
        requireVersion(version);
        ProjectRow current = mapper.lockProject(venueId, require(projectId), now());
        if (current == null) {
            throw new OpsException(VenueOpsError.NOT_FOUND);
        }
        if (current.version() != version) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        return current;
    }

    private DeviceRow lockDevice(UUID venueId, UUID deviceId, Integer version) {
        requireVersion(version);
        DeviceRow current = mapper.lockDevice(venueId, require(deviceId));
        if (current == null) {
            throw new OpsException(VenueOpsError.NOT_FOUND);
        }
        if (current.version() != version) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        return current;
    }

    private static VenueRow requireVenue(VenueRow row) {
        if (row == null) {
            throw new OpsException(VenueOpsError.NOT_FOUND);
        }
        return row;
    }

    private static UUID require(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return id;
    }

    private static void requireKey(UUID key) {
        if (key == null) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
    }

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw new IllegalArgumentException("version is required");
        }
    }

    private static void requireStatus(boolean allowed) {
        if (!allowed) {
            throw new OpsException(VenueOpsError.STATUS_CONFLICT);
        }
    }

    private static void requireRange(LocalDate fromDate, LocalDate toDate) {
        if (toDate.isBefore(fromDate) || ChronoUnit.DAYS.between(fromDate, toDate) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("date range is invalid");
        }
    }

    private static String cityCode(String code) {
        if (code == null || !CITY_CODE.matcher(code.strip()).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return code.strip();
    }

    private static String hhmm(String value, String field) {
        return time(value, field).toString();
    }

    private static LocalTime time(String value, String field) {
        String text = value == null ? "" : value.strip();
        if (!HHMM.matcher(text).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return LocalTime.parse(text);
    }

    private static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value == null ? "" : value.strip());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(field + " is invalid", invalid);
        }
    }

    private static int bounded(Integer value, int min, int max, String field) {
        if (value == null || value < min || value > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String withNote(String action, String note) {
        return note == null ? action : action + "：" + note;
    }

    private static OffsetDateTime startOf(LocalDate date) {
        return date.atStartOfDay(VENUE_ZONE).toOffsetDateTime();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
