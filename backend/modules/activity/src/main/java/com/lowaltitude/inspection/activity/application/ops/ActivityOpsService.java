package com.lowaltitude.inspection.activity.application.ops;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityPriceMode;
import com.lowaltitude.inspection.activity.application.ActivityType;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.ActivityDraft;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsActivityPageView;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsActivitySessionView;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsActivityStatus;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsActivitySummaryView;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsActivityView;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsOrganizationRefView;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsSessionSaleStatus;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.Operator;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.SessionDraft;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOpsMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOpsMapper.ActivityRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOpsMapper.ActivityValues;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOpsMapper.SessionRow;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOpsMapper.SessionValues;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“活动与场次”（S3-1，契约 contracts/openapi-ops-activity.yaml 1.1.0）。只有平台运营能调（控制器检查角色）。
 * <ul>
 *   <li>活动新建为草稿；改要带版本号。上线前校验必填（{@link #publishMissing}），缺了 422 带 details.missing；
 *       已上线的活动改完也不能缺（场次除外）。下线后 App 看不到，已有报名照常。草稿且从没上线过的才能删（标 DELETED）。</li>
 *   <li>有待付款或已确认的报名后，收费方式、主办机构、城市不能改（单价能改，只影响以后的报名）。</li>
 *   <li>候补、团队报名两个开关（1.1.0，规则见 docs/redesign/S5-3-活动候补团队改期与结果.md）：只有能在线报名的
 *       （免费、按人收费）能打开，询价的只能关着；表单不带时新建为关、修改时保持原值。随时可以关：关掉候补后不能再排队，
 *       已经在排队、已转正的照常按顺序转正和确认（转正不看开关）；关掉团队报名后不能再团队报名，已有的团队报名单照常
 *       取消、改期、核销。改了开关时改动记录里写明。</li>
 *   <li>场次：新增的必须还没开始；开始了的不能再改；已有人报名的不能改开始、结束时间，名额不能少于已报名人数；
 *       有过报名单（任何状态）的不能删，只能停售。停售后 App 读成报名截止、不能再报，恢复售卖后照常。</li>
 *   <li>每个写操作在 activity_ops_change 里记一条（对象、动作、一句话说明、操作人显示名、时间），只追加。</li>
 * </ul>
 */
public class ActivityOpsService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_KEYWORD = 20;
    public static final int MAX_TITLE = 60;
    public static final int MAX_SUBTITLE = 200;
    public static final int MAX_VENUE_NAME = 120;
    public static final int MAX_VENUE_ADDRESS = 240;
    public static final int MAX_SCHEDULE_NOTE = 120;
    public static final int MAX_SHORT_NOTE = 300;
    public static final int MAX_REFUND_NOTE = 500;
    public static final int MAX_ENROLLMENT_NOTICE = 2000;
    public static final int MAX_GALLERY = 9;
    public static final int MAX_AGE = 120;
    /** 单价上限 10 万元（分）。 */
    public static final long MAX_PRICE_MINOR = 10_000_000L;
    public static final int MAX_LABEL = 60;
    public static final int MAX_CAPACITY = 10_000;
    public static final int MAX_SESSIONS = 50;
    public static final int CHANGE_HISTORY_LIMIT = 100;

    /** 上线必填项的字段键（契约 OpsActivityPublishField）。 */
    public static final String FIELD_COVER = "coverUrl";
    public static final String FIELD_NOTICE = "enrollmentNotice";
    public static final String FIELD_REFUND = "refundNote";
    public static final String FIELD_ORGANIZER = "organizer";
    public static final String FIELD_SESSIONS = "sessions";

    private static final Set<String> ORGANIZER_TYPES = Set.of("PROVIDER", "PLATFORM");
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SUMMARY_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final ActivityOpsMapper mapper;
    private final ActivityOpsPorts.Organizations organizations;
    private final ActivityOpsPorts.Cities cities;
    private final Clock clock;

    public ActivityOpsService(ActivityOpsMapper mapper, ActivityOpsPorts.Organizations organizations,
                              ActivityOpsPorts.Cities cities) {
        this(mapper, organizations, cities, Clock.systemUTC());
    }

    ActivityOpsService(ActivityOpsMapper mapper, ActivityOpsPorts.Organizations organizations,
                       ActivityOpsPorts.Cities cities, Clock clock) {
        this.mapper = mapper;
        this.organizations = organizations;
        this.cities = cities;
        this.clock = clock;
    }

    // ---- 读 ----

    @Transactional(readOnly = true)
    public OpsActivityPageView activities(String keyword, String cityCode, String type, String status, int page,
                                          int size) {
        OpsText.page(page, size, MAX_PAGE_SIZE);
        String filterKeyword = OpsText.keyword(keyword, MAX_KEYWORD);
        String filterCity = cityCode == null || cityCode.isBlank() ? null : cityCode(cityCode);
        ActivityType filterType = OpsText.optionalEnum(ActivityType.class, type, "type");
        OpsActivityStatus filterStatus = OpsText.optionalEnum(OpsActivityStatus.class, status, "status");
        String typeCode = filterType == null ? null : filterType.name();
        String statusCode = filterStatus == null ? null : filterStatus.name();
        long total = mapper.count(filterKeyword, filterCity, typeCode, statusCode);
        Map<UUID, OpsOrganizationRefView> orgs = new HashMap<>();
        Map<String, String> cityNames = new HashMap<>();
        List<OpsActivitySummaryView> items = mapper.page(filterKeyword, filterCity, typeCode, statusCode, now(), size,
                        Math.multiplyExact(page - 1, size)).stream()
                .map(row -> new OpsActivitySummaryView(row.id(), ActivityType.valueOf(row.type()), row.title(),
                        row.cityCode(), cityNames.computeIfAbsent(row.cityCode(), this::cityNameOrNull),
                        orgs.computeIfAbsent(row.organizerId(), this::organizationRef),
                        OpsActivityStatus.valueOf(row.status()), ActivityPriceMode.valueOf(row.priceMode()),
                        row.priceAmountMinor(), row.sessionCount(), row.nextStartsAt(), row.enrolledCount(),
                        row.updatedAt(), row.version()))
                .toList();
        return new OpsActivityPageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public OpsActivityView activity(UUID activityId) {
        return view(require(activityId));
    }

    @Transactional(readOnly = true)
    public List<OpsChangeView> changes(UUID activityId) {
        require(activityId);
        return mapper.changes(activityId, CHANGE_HISTORY_LIMIT).stream()
                .map(row -> new OpsChangeView(row.id(), row.subjectType(), row.subjectId(), row.action(),
                        row.summary(), row.operatorName(), row.changedAt()))
                .toList();
    }

    // ---- 活动 ----

    @Transactional
    public OpsActivityView create(ActivityDraft draft, Operator operator) {
        String name = operatorName(operator);
        Prepared prepared = prepare(draft, null);
        UUID id = UUID.randomUUID();
        OffsetDateTime at = now();
        mapper.insert(id, prepared.values(), at);
        writeGallery(id, prepared.gallery(), at);
        record(id, "ACTIVITY", id, "CREATE", "新建草稿“" + prepared.values().title() + "”"
                + switches(null, prepared.values()), operator, name, at);
        return view(require(id));
    }

    @Transactional
    public OpsActivityView update(UUID activityId, ActivityDraft draft, Integer version, Operator operator) {
        String name = operatorName(operator);
        int expected = version(version);
        ActivityRow current = require(activityId);
        checkVersion(current.version(), expected);
        Prepared prepared = prepare(draft, current);
        ActivityValues values = prepared.values();
        if (current.hasEnrollments() && (!values.priceMode().equals(current.priceMode())
                || !values.organizerId().equals(current.organizerId())
                || !values.cityCode().equals(current.cityCode()))) {
            throw new OpsException(ActivityOpsError.LOCKED_BY_ENROLLMENTS);
        }
        if (OpsActivityStatus.PUBLISHED.name().equals(current.status())) {
            // 已上线的活动改完也要齐全（场次另外管；机构在 prepare 里已经查过）。
            List<String> missing = missing(values, true);
            if (!missing.isEmpty()) {
                throw OpsException.incomplete(ActivityOpsError.PUBLISH_INCOMPLETE, missing);
            }
        }
        OffsetDateTime at = now();
        if (mapper.update(activityId, values, expected, at) != 1) {
            throw new OpsException(ActivityOpsError.VERSION_CONFLICT);
        }
        writeGallery(activityId, prepared.gallery(), at);
        record(activityId, "ACTIVITY", activityId, "UPDATE", "修改活动信息" + switches(current, values), operator, name,
                at);
        return view(require(activityId));
    }

    @Transactional
    public OpsActivityView publish(UUID activityId, Integer version, Operator operator) {
        String name = operatorName(operator);
        int expected = version(version);
        ActivityRow current = require(activityId);
        checkVersion(current.version(), expected);
        if (OpsActivityStatus.PUBLISHED.name().equals(current.status())) {
            throw new OpsException(ActivityOpsError.STATUS_CONFLICT);
        }
        List<String> missing = publishMissing(current, mapper.sessions(activityId));
        if (!missing.isEmpty()) {
            throw OpsException.incomplete(ActivityOpsError.PUBLISH_INCOMPLETE, missing);
        }
        // 资质没审核通过的机构不能上线活动（身份迁移 V013）。
        if (organizations.find(current.organizerId())
                .filter(ActivityOpsPorts.Organization::listingSuspended).isPresent()) {
            throw new OpsException(ActivityOpsError.ORGANIZER_NOT_QUALIFIED);
        }
        OffsetDateTime at = now();
        if (mapper.changeStatus(activityId, OpsActivityStatus.PUBLISHED.name(), expected, at) != 1) {
            throw new OpsException(ActivityOpsError.VERSION_CONFLICT);
        }
        record(activityId, "ACTIVITY", activityId, "PUBLISH", "上线，App 上能看到", operator, name, at);
        return view(require(activityId));
    }

    @Transactional
    public OpsActivityView offline(UUID activityId, Integer version, Operator operator) {
        String name = operatorName(operator);
        int expected = version(version);
        ActivityRow current = require(activityId);
        checkVersion(current.version(), expected);
        if (!OpsActivityStatus.PUBLISHED.name().equals(current.status())) {
            throw new OpsException(ActivityOpsError.STATUS_CONFLICT);
        }
        OffsetDateTime at = now();
        if (mapper.changeStatus(activityId, OpsActivityStatus.OFFLINE.name(), expected, at) != 1) {
            throw new OpsException(ActivityOpsError.VERSION_CONFLICT);
        }
        record(activityId, "ACTIVITY", activityId, "OFFLINE", "下线，App 上不再展示，已有报名照常", operator, name, at);
        return view(require(activityId));
    }

    /** 只删从没上线过的草稿（标 DELETED，记录还在）。 */
    @Transactional
    public void delete(UUID activityId, Integer version, Operator operator) {
        String name = operatorName(operator);
        int expected = version(version);
        ActivityRow current = require(activityId);
        checkVersion(current.version(), expected);
        if (!deletable(current)) {
            throw new OpsException(ActivityOpsError.STATUS_CONFLICT);
        }
        OffsetDateTime at = now();
        if (mapper.changeStatus(activityId, "DELETED", expected, at) != 1) {
            throw new OpsException(ActivityOpsError.VERSION_CONFLICT);
        }
        record(activityId, "ACTIVITY", activityId, "DELETE", "删除草稿“" + current.title() + "”", operator, name, at);
    }

    // ---- 场次 ----

    @Transactional
    public OpsActivityView addSession(UUID activityId, SessionDraft draft, Operator operator) {
        String name = operatorName(operator);
        require(activityId);
        if (mapper.sessions(activityId).size() >= MAX_SESSIONS) {
            throw new OpsException(ActivityOpsError.SESSION_LIMIT);
        }
        OffsetDateTime at = now();
        SessionValues values = session(draft);
        if (!values.startsAt().isAfter(at)) {
            throw new IllegalArgumentException("session must start in the future");
        }
        UUID sessionId = UUID.randomUUID();
        mapper.insertSession(sessionId, activityId, values, at);
        mapper.touch(activityId, at);
        record(activityId, "SESSION", sessionId, "CREATE", "新增场次 " + describe(values), operator, name, at);
        return view(require(activityId));
    }

    @Transactional
    public OpsActivityView updateSession(UUID activityId, UUID sessionId, SessionDraft draft, Integer version,
                                         Operator operator) {
        String name = operatorName(operator);
        int expected = version(version);
        require(activityId);
        SessionValues values = session(draft);
        SessionRow current = lockEditable(activityId, sessionId, expected);
        if (current.enrolledCount() > 0 && (!current.startsAt().isEqual(values.startsAt())
                || !sameInstant(current.endsAt(), values.endsAt()))) {
            throw new OpsException(ActivityOpsError.SESSION_HAS_ENROLLMENTS);
        }
        if (values.capacity() < current.enrolledCount()) {
            throw new OpsException(ActivityOpsError.CAPACITY_BELOW_ENROLLED);
        }
        OffsetDateTime at = now();
        if (!values.startsAt().isAfter(at)) {
            throw new IllegalArgumentException("session must start in the future");
        }
        if (mapper.updateSession(sessionId, values, expected, at) != 1) {
            throw new OpsException(ActivityOpsError.VERSION_CONFLICT);
        }
        mapper.touch(activityId, at);
        record(activityId, "SESSION", sessionId, "UPDATE", "修改场次 " + describe(values), operator, name, at);
        return view(require(activityId));
    }

    @Transactional
    public OpsActivityView deleteSession(UUID activityId, UUID sessionId, Integer version, Operator operator) {
        String name = operatorName(operator);
        int expected = version(version);
        require(activityId);
        SessionRow current = lockSession(activityId, sessionId);
        checkVersion(current.version(), expected);
        if (current.enrollmentCount() > 0) {
            throw new OpsException(ActivityOpsError.SESSION_HAS_ENROLLMENTS);
        }
        if (mapper.deleteSession(sessionId, expected) != 1) {
            throw new OpsException(ActivityOpsError.VERSION_CONFLICT);
        }
        OffsetDateTime at = now();
        mapper.touch(activityId, at);
        record(activityId, "SESSION", sessionId, "DELETE", "删除场次 " + describe(current), operator, name, at);
        return view(require(activityId));
    }

    @Transactional
    public OpsActivityView stopSale(UUID activityId, UUID sessionId, Integer version, Operator operator) {
        return changeSale(activityId, sessionId, version, operator, OpsSessionSaleStatus.STOPPED);
    }

    @Transactional
    public OpsActivityView resumeSale(UUID activityId, UUID sessionId, Integer version, Operator operator) {
        return changeSale(activityId, sessionId, version, operator, OpsSessionSaleStatus.ON_SALE);
    }

    private OpsActivityView changeSale(UUID activityId, UUID sessionId, Integer version, Operator operator,
                                       OpsSessionSaleStatus target) {
        String name = operatorName(operator);
        int expected = version(version);
        require(activityId);
        SessionRow current = target == OpsSessionSaleStatus.ON_SALE
                ? lockEditable(activityId, sessionId, expected)
                : lockSession(activityId, sessionId);
        checkVersion(current.version(), expected);
        if ("CANCELLED".equals(current.status())) {
            throw new OpsException(ActivityOpsError.STATUS_CONFLICT);
        }
        if (target.name().equals(current.saleStatus())) {
            throw new OpsException(ActivityOpsError.SALE_STATUS_UNCHANGED);
        }
        OffsetDateTime at = now();
        if (mapper.changeSaleStatus(sessionId, target.name(), expected, at) != 1) {
            throw new OpsException(ActivityOpsError.VERSION_CONFLICT);
        }
        mapper.touch(activityId, at);
        boolean stop = target == OpsSessionSaleStatus.STOPPED;
        record(activityId, "SESSION", sessionId, stop ? "STOP_SALE" : "RESUME_SALE",
                (stop ? "停售场次 " : "恢复售卖场次 ") + describe(current)
                        + (stop ? "，已报名 " + current.enrolledCount() + " 人不受影响" : ""),
                operator, name, at);
        return view(require(activityId));
    }

    /** 改场次、恢复售卖：锁住、版本对、没取消、还没开始。 */
    private SessionRow lockEditable(UUID activityId, UUID sessionId, int expected) {
        SessionRow current = lockSession(activityId, sessionId);
        checkVersion(current.version(), expected);
        if ("CANCELLED".equals(current.status())) {
            throw new OpsException(ActivityOpsError.STATUS_CONFLICT);
        }
        if (!current.startsAt().isAfter(now())) {
            throw new OpsException(ActivityOpsError.SESSION_STARTED);
        }
        return current;
    }

    private SessionRow lockSession(UUID activityId, UUID sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("session id is required");
        }
        SessionRow row = mapper.lockSession(activityId, sessionId);
        if (row == null) {
            throw new OpsException(ActivityOpsError.NOT_FOUND);
        }
        return row;
    }

    // ---- 上线前必填 ----

    /**
     * 上线还差什么（字段键，按页面上的顺序）：封面、报名须知、按人收费时的退改说明、主办机构仍在用、
     * 至少一个没取消、在售、报名还没截止的场次。
     */
    List<String> publishMissing(ActivityRow row, List<SessionRow> sessions) {
        ActivityValues values = values(row);
        List<String> missing = missing(values, organizations.find(row.organizerId())
                .filter(found -> ORGANIZER_TYPES.contains(found.type())).isPresent());
        OffsetDateTime now = now();
        boolean hasOpen = sessions.stream().anyMatch(session -> "ACTIVE".equals(session.status())
                && OpsSessionSaleStatus.ON_SALE.name().equals(session.saleStatus())
                && session.enrollmentClosesAt().isAfter(now));
        if (!hasOpen) {
            missing.add(FIELD_SESSIONS);
        }
        return missing;
    }

    private static List<String> missing(ActivityValues values, boolean organizerActive) {
        List<String> missing = new ArrayList<>();
        if (values.coverUrl() == null) {
            missing.add(FIELD_COVER);
        }
        if (values.enrollmentNotice() == null) {
            missing.add(FIELD_NOTICE);
        }
        if (ActivityPriceMode.PER_PERSON.name().equals(values.priceMode()) && values.refundNote() == null) {
            missing.add(FIELD_REFUND);
        }
        if (!organizerActive) {
            missing.add(FIELD_ORGANIZER);
        }
        return missing;
    }

    // ---- 表单整理 ----

    private record Prepared(ActivityValues values, List<String> gallery) {
    }

    /** @param current 修改时是现在的活动（开关不带时保持原值）；新建时为 null */
    private Prepared prepare(ActivityDraft draft, ActivityRow current) {
        if (draft == null) {
            throw new IllegalArgumentException("activity is required");
        }
        ActivityType type = OpsText.enumValue(ActivityType.class, draft.type(), "type");
        ActivityPriceMode priceMode = OpsText.enumValue(ActivityPriceMode.class, draft.priceMode(), "priceMode");
        Long amount = null;
        if (priceMode == ActivityPriceMode.PER_PERSON) {
            amount = draft.priceAmountMinor();
            if (amount == null || amount < 1 || amount > MAX_PRICE_MINOR) {
                throw new IllegalArgumentException("price amount is invalid");
            }
        }
        Integer minAge = age(draft.minAge());
        Integer maxAge = age(draft.maxAge());
        if (minAge != null && maxAge != null && minAge > maxAge) {
            throw new IllegalArgumentException("age range is invalid");
        }
        if (draft.organizerId() == null) {
            throw new IllegalArgumentException("organizer is required");
        }
        if (Objects.equals(draft.organizerId(), draft.coOrganizerId())) {
            throw new IllegalArgumentException("co-organizer must differ from the organizer");
        }
        String city = cityCode(draft.cityCode());
        if (cities.cityName(city).isEmpty()) {
            throw new OpsException(ActivityOpsError.CITY_UNKNOWN);
        }
        requireOrganizer(draft.organizerId());
        if (draft.coOrganizerId() != null) {
            requireOrganizer(draft.coOrganizerId());
        }
        ActivityValues values = new ActivityValues(
                type.name(),
                OpsText.required(draft.title(), MAX_TITLE, "title"),
                OpsText.optional(draft.subtitle(), MAX_SUBTITLE, "subtitle"),
                OpsText.httpsUrl(draft.coverUrl(), "coverUrl"),
                draft.organizerId(),
                draft.coOrganizerId(),
                city,
                OpsText.required(draft.venueName(), MAX_VENUE_NAME, "venueName"),
                OpsText.required(draft.venueAddress(), MAX_VENUE_ADDRESS, "venueAddress"),
                OpsText.optional(draft.scheduleNote(), MAX_SCHEDULE_NOTE, "scheduleNote"),
                priceMode.name(),
                amount,
                minAge,
                maxAge,
                OpsText.optional(draft.eligibilityNote(), MAX_SHORT_NOTE, "eligibilityNote"),
                OpsText.optional(draft.seatNote(), MAX_SHORT_NOTE, "seatNote"),
                OpsText.multiline(draft.refundNote(), MAX_REFUND_NOTE, "refundNote"),
                OpsText.multiline(draft.enrollmentNotice(), MAX_ENROLLMENT_NOTICE, "enrollmentNotice"),
                enrollmentSwitch(draft.waitlistAllowed(), current != null && current.waitlistAllowed(), priceMode,
                        "waitlistAllowed"),
                enrollmentSwitch(draft.teamEnrollmentAllowed(), current != null && current.teamEnrollmentAllowed(),
                        priceMode, "teamEnrollmentAllowed"));
        return new Prepared(values, gallery(draft.galleryUrls()));
    }

    /**
     * 候补、团队报名开关：都要能在线报名（免费或按人收费）。不带时保持原值（新建为关）；改成询价时原来开着的跟着关掉，
     * 明确要在询价活动上打开的是参数错误。
     */
    private static boolean enrollmentSwitch(Boolean requested, boolean keep, ActivityPriceMode priceMode,
                                            String field) {
        boolean online = priceMode != ActivityPriceMode.INQUIRY;
        if (requested == null) {
            return online && keep;
        }
        if (requested && !online) {
            throw new IllegalArgumentException(field + " needs an activity that takes enrollments online");
        }
        return requested;
    }

    /** 改动记录里写开关的变化；关候补时带上照常转正的排队条数。没变时为空。 */
    private static String switches(ActivityRow current, ActivityValues values) {
        List<String> parts = new ArrayList<>();
        boolean waitlistBefore = current != null && current.waitlistAllowed();
        if (values.waitlistAllowed() != waitlistBefore) {
            parts.add(values.waitlistAllowed() ? "打开候补"
                    : "关闭候补" + (current.activeWaitlists() > 0
                            ? "（" + current.activeWaitlists() + " 条排队中的照常转正）" : ""));
        }
        boolean teamBefore = current != null && current.teamEnrollmentAllowed();
        if (values.teamEnrollmentAllowed() != teamBefore) {
            parts.add(values.teamEnrollmentAllowed() ? "打开团队报名" : "关闭团队报名（已有的团队报名照常）");
        }
        return parts.isEmpty() ? "" : "；" + String.join("，", parts);
    }

    private void requireOrganizer(UUID organizationId) {
        Optional<ActivityOpsPorts.Organization> found = organizations.find(organizationId);
        if (found.isEmpty() || !ORGANIZER_TYPES.contains(found.get().type())) {
            throw new OpsException(ActivityOpsError.ORGANIZER_UNKNOWN);
        }
    }

    private static List<String> gallery(List<String> urls) {
        if (urls == null) {
            return List.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String url : urls) {
            String value = OpsText.httpsUrl(url, "galleryUrls");
            if (value == null || !cleaned.add(value)) {
                throw new IllegalArgumentException("gallery url is empty or repeated");
            }
        }
        if (cleaned.size() > MAX_GALLERY) {
            throw new IllegalArgumentException("gallery has too many images");
        }
        return List.copyOf(cleaned);
    }

    private static SessionValues session(SessionDraft draft) {
        if (draft == null || draft.startsAt() == null || draft.enrollmentClosesAt() == null
                || draft.capacity() == null) {
            throw new IllegalArgumentException("session is incomplete");
        }
        if (draft.capacity() < 1 || draft.capacity() > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity is invalid");
        }
        if (draft.endsAt() != null && !draft.endsAt().isAfter(draft.startsAt())) {
            throw new IllegalArgumentException("session must end after it starts");
        }
        if (draft.enrollmentClosesAt().isAfter(draft.startsAt())) {
            throw new IllegalArgumentException("enrollment must close before the session starts");
        }
        if (draft.checkInOpensAt() != null && draft.checkInOpensAt().isAfter(draft.startsAt())) {
            throw new IllegalArgumentException("check-in must open before the session starts");
        }
        return new SessionValues(OpsText.optional(draft.label(), MAX_LABEL, "label"), utc(draft.startsAt()),
                utc(draft.endsAt()), utc(draft.checkInOpensAt()), utc(draft.enrollmentClosesAt()), draft.capacity());
    }

    private void writeGallery(UUID activityId, List<String> gallery, OffsetDateTime at) {
        mapper.clearGallery(activityId);
        for (int index = 0; index < gallery.size(); index++) {
            mapper.insertGalleryImage(activityId, index + 1, gallery.get(index), at);
        }
    }

    // ---- 拼页面 ----

    private OpsActivityView view(ActivityRow row) {
        List<SessionRow> sessions = mapper.sessions(row.id());
        OffsetDateTime now = now();
        List<OpsActivitySessionView> sessionViews = sessions.stream()
                .map(session -> new OpsActivitySessionView(session.id(), session.label(), session.startsAt(),
                        session.endsAt(), session.checkInOpensAt(), session.enrollmentClosesAt(), session.capacity(),
                        session.enrolledCount(), OpsSessionSaleStatus.valueOf(session.saleStatus()),
                        "CANCELLED".equals(session.status()), session.enrollmentCount() > 0,
                        !session.startsAt().isAfter(now), session.version()))
                .toList();
        return new OpsActivityView(row.id(), ActivityType.valueOf(row.type()), row.title(), row.subtitle(),
                row.coverUrl(), mapper.gallery(row.id()), organizationRef(row.organizerId()),
                row.coOrganizerId() == null ? null : organizationRef(row.coOrganizerId()), row.cityCode(),
                cityNameOrNull(row.cityCode()), row.venueName(), row.venueAddress(), row.scheduleNote(),
                ActivityPriceMode.valueOf(row.priceMode()), row.priceAmountMinor(), row.minAge(), row.maxAge(),
                row.eligibilityNote(), row.seatNote(), row.refundNote(), row.enrollmentNotice(),
                row.waitlistAllowed(), row.teamEnrollmentAllowed(), row.activeWaitlists(),
                OpsActivityStatus.valueOf(row.status()), row.firstPublishedAt(), row.hasEnrollments(),
                deletable(row), publishMissing(row, sessions), sessionViews, row.createdAt(), row.updatedAt(),
                row.version());
    }

    private static ActivityValues values(ActivityRow row) {
        return new ActivityValues(row.type(), row.title(), row.subtitle(), blank(row.coverUrl()), row.organizerId(),
                row.coOrganizerId(), row.cityCode(), row.venueName(), row.venueAddress(), row.scheduleNote(),
                row.priceMode(), row.priceAmountMinor(), row.minAge(), row.maxAge(), row.eligibilityNote(),
                row.seatNote(), blank(row.refundNote()), blank(row.enrollmentNotice()), row.waitlistAllowed(),
                row.teamEnrollmentAllowed());
    }

    private static boolean deletable(ActivityRow row) {
        return OpsActivityStatus.DRAFT.name().equals(row.status()) && row.firstPublishedAt() == null
                && !row.hasEnrollments();
    }

    private OpsOrganizationRefView organizationRef(UUID organizationId) {
        return new OpsOrganizationRefView(organizationId,
                organizations.find(organizationId).map(ActivityOpsPorts.Organization::name).orElse(null));
    }

    private String cityNameOrNull(String cityCode) {
        return cities.cityName(cityCode).orElse(null);
    }

    // ---- 小工具 ----

    private ActivityRow require(UUID activityId) {
        if (activityId == null) {
            throw new IllegalArgumentException("activity id is required");
        }
        ActivityRow row = mapper.find(activityId);
        if (row == null) {
            throw new OpsException(ActivityOpsError.NOT_FOUND);
        }
        return row;
    }

    private void record(UUID activityId, String subjectType, UUID subjectId, String action, String summary,
                        Operator operator, String operatorName, OffsetDateTime at) {
        if (mapper.insertChange(activityId, subjectType, subjectId, action, OpsText.clip(summary, 200),
                operator.accountId(), operatorName, at) != 1) {
            throw new IllegalStateException("activity change was not recorded");
        }
    }

    private static String operatorName(Operator operator) {
        if (operator == null || operator.accountId() == null) {
            throw new IllegalArgumentException("operator is required");
        }
        return OpsText.operatorName(operator.displayName());
    }

    private static int version(Integer version) {
        if (version == null || version < 0) {
            throw new IllegalArgumentException("version is required");
        }
        return version;
    }

    private static void checkVersion(int current, int expected) {
        if (current != expected) {
            throw new OpsException(ActivityOpsError.VERSION_CONFLICT);
        }
    }

    private static String cityCode(String code) {
        if (code == null || !code.strip().matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return code.strip();
    }

    private static Integer age(Integer age) {
        if (age != null && (age < 0 || age > MAX_AGE)) {
            throw new IllegalArgumentException("age is invalid");
        }
        return age;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        return left == null ? right == null : right != null && left.isEqual(right);
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String describe(SessionValues values) {
        return SUMMARY_TIME.format(values.startsAt().atZoneSameInstant(BEIJING))
                + (values.label() == null ? "" : "（" + values.label() + "）") + "，名额 " + values.capacity();
    }

    private static String describe(SessionRow row) {
        return SUMMARY_TIME.format(row.startsAt().atZoneSameInstant(BEIJING))
                + (row.label() == null ? "" : "（" + row.label() + "）");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
