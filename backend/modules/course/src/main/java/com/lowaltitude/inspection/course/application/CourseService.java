package com.lowaltitude.inspection.course.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.course.application.CourseException.Error;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.BundleRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.CourseRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.EnrollmentRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.KeyRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.LessonRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.ProgressRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程（S5-5，契约 contracts/openapi-course.yaml 1.1.0，说明见 docs/redesign/09-课程实现说明.md）。
 *
 * <ul>
 *   <li>列表：上架的线上课（单独出售的）全国都列；线下班只列对应活动在当前城市公开可见的，标题、封面、价格取自活动。</li>
 *   <li>购买：上架、单独出售、开课机构能在线收款；一个账号一门课同时只开通一次；已有待付款的直接返回那一条。
 *       经交易模块建订单（来源 COURSE），付款 15 分钟内有效。付款到账即开通，有效期从开通算。</li>
 *   <li>商品附带：商城订单完成时按商品找附带的课程，没开通着的给买家开通（来源 BUNDLE）。</li>
 *   <li>学习：开通着、没过期才能拿播放地址；第一次开始一节非试听课时之后不能在线退款。播完记一次，重看不重复计。</li>
 *   <li>退款：自己买的、开通着、付款后 7 天内、没开始学非试听课时，全额原路退回，课程关闭。</li>
 * </ul>
 *
 * <p>加锁顺序：交易订单 → 课程 → 开通记录。同一个账号同一门课的购买、开通都先锁课程那一行，互相排队。
 */
public class CourseService {

    /** 付款时限。 */
    static final Duration PAY_WINDOW = Duration.ofMinutes(15);
    /** 付款后多久内、没开始学时可以在线全额退款（D15）。 */
    static final Duration REFUND_WINDOW = Duration.ofDays(7);
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_KEYWORD = 30;
    /** 列表一次最多从库里取多少门上架的课程（按城市、关键词、价格在内存里处理）。 */
    static final int LISTED_LIMIT = 500;
    /** 一门课只有一种规格：一个账号开通（家庭账号等多人规格不在一期）。 */
    static final String OPTION_NAME = "单人账号";
    static final String REFUND_REASON = "课程退款（付款后 7 天内，未开始学习）";
    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");
    private static final Logger log = LoggerFactory.getLogger(CourseService.class);

    private final CourseMapper mapper;
    private final CoursePorts.Payments payments;
    private final CoursePorts.Activities activities;
    private final CoursePorts.Organizations organizations;
    private final CoursePorts.Simulations simulations;
    private final Clock clock;

    CourseService(
            CourseMapper mapper,
            CoursePorts.Payments payments,
            CoursePorts.Activities activities,
            CoursePorts.Organizations organizations,
            CoursePorts.Simulations simulations,
            Clock clock
    ) {
        this.mapper = mapper;
        this.payments = payments;
        this.activities = activities;
        this.organizations = organizations;
        this.simulations = simulations;
        this.clock = clock;
    }

    // ---- 目录（公开） ----

    @Transactional(readOnly = true)
    public CoursePageView list(String cityCode, String topicCode, String keywordText, String sortCode, int page, int size) {
        if (cityCode == null || !CITY_CODE.matcher(cityCode).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        requirePage(page, size);
        CourseTopic topic = topicCode == null || topicCode.isBlank() ? null : parse(CourseTopic.class, topicCode);
        CourseSort sort = sortCode == null || sortCode.isBlank() ? CourseSort.DEFAULT : parse(CourseSort.class, sortCode);
        String keyword = keywordText == null ? "" : keywordText.strip();
        if (keyword.length() > MAX_KEYWORD) {
            throw new IllegalArgumentException("keyword is too long");
        }

        List<CourseRow> rows = mapper.findListedCourses(topic == null ? null : topic.name(), LISTED_LIMIT);
        List<UUID> online = rows.stream().filter(CourseService::isOnline).map(CourseRow::id).toList();
        Map<UUID, List<LessonRow>> lessons = lessonsByCourse(online);
        List<CourseCardView> cards = new ArrayList<>();
        for (CourseRow row : rows) {
            card(row, cityCode, lessons.getOrDefault(row.id(), List.of())).ifPresent(cards::add);
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        Map<UUID, CourseRow> byId = rows.stream().collect(Collectors.toMap(CourseRow::id, Function.identity()));
        List<CourseCardView> matched = cards.stream()
                .filter(card -> needle.isEmpty() || matches(card, byId.get(card.courseId()), needle))
                .collect(Collectors.toCollection(ArrayList::new));
        if (sort != CourseSort.DEFAULT) {
            // 稳定排序：有标价的按价格，没有标价的（免费、面议）保持运营排序、排在最后。
            Comparator<CourseCardView> byPrice = Comparator.comparingLong(card -> card.price().amountMinor());
            List<CourseCardView> priced = matched.stream().filter(card -> card.price().amountMinor() != null)
                    .sorted(sort == CourseSort.PRICE_ASC ? byPrice : byPrice.reversed())
                    .toList();
            List<CourseCardView> unpriced = matched.stream().filter(card -> card.price().amountMinor() == null).toList();
            matched = new ArrayList<>(priced);
            matched.addAll(unpriced);
        }
        int from = Math.min(matched.size(), Math.multiplyExact(page - 1, size));
        int to = Math.min(matched.size(), from + size);
        return new CoursePageView(page, size, matched.size(), to < matched.size(), List.copyOf(matched.subList(from, to)));
    }

    private Optional<CourseCardView> card(CourseRow row, String cityCode, List<LessonRow> lessons) {
        CourseTopic topic = CourseTopic.valueOf(row.topic());
        if (isOnline(row)) {
            if (row.priceAmountMinor() == null || lessons.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new CourseCardView(row.id(), CourseMode.ONLINE, topic, row.title(), cover(row),
                    new CoursePriceView(CoursePriceMode.FIXED, row.priceAmountMinor()), lessons.size(), null, null, null));
        }
        return activities.find(row.activityId(), cityCode).map(activity -> new CourseCardView(
                row.id(),
                CourseMode.OFFLINE,
                topic,
                activity.title(),
                activity.coverUrl() == null ? null
                        : new CourseImageView(activity.coverUrl(), activity.coverAlt() == null ? activity.title() : activity.coverAlt()),
                activityPrice(activity),
                null,
                row.scheduleLabel(),
                activity.activityId(),
                activity.cityCode()));
    }

    private static CoursePriceView activityPrice(CoursePorts.Activity activity) {
        if ("PER_PERSON".equals(activity.priceMode()) && activity.amountMinor() != null && activity.amountMinor() > 0) {
            return new CoursePriceView(CoursePriceMode.FIXED, activity.amountMinor());
        }
        if ("FREE".equals(activity.priceMode())) {
            return new CoursePriceView(CoursePriceMode.FREE, null);
        }
        return new CoursePriceView(CoursePriceMode.INQUIRY, null);
    }

    private static boolean matches(CourseCardView card, CourseRow row, String needle) {
        if (card.title().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return card.mode() == CourseMode.ONLINE && row.subtitle() != null
                && row.subtitle().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** 线上课详情。不单独出售的（只随商品附带）也能看，price 为 null；线下班、没上架的不存在。 */
    @Transactional(readOnly = true)
    public CourseDetailView detail(UUID courseId) {
        CourseRow row = listedOnline(courseId);
        List<LessonRow> lessons = lessonsByCourse(List.of(row.id())).getOrDefault(row.id(), List.of());
        boolean collecting = payments.merchantCanCollect(row.organizationId());
        List<CourseImageView> gallery = CourseJson.strings(row.galleryUrls()).stream()
                .filter(CourseService::isHttps)
                .map(url -> new CourseImageView(url, row.title()))
                .toList();
        return new CourseDetailView(
                row.id(),
                CourseTopic.valueOf(row.topic()),
                row.title(),
                row.subtitle(),
                cover(row),
                gallery,
                row.priceAmountMinor() == null ? null : new CoursePriceView(CoursePriceMode.FIXED, row.priceAmountMinor()),
                row.validityDays(),
                row.ageNote(),
                row.instructorName() == null ? null : new CourseInstructorView(row.instructorName(), row.instructorTitle()),
                organization(row.organizationId()),
                lessons.size(),
                lessons.stream().mapToInt(LessonRow::durationSeconds).sum(),
                (int) lessons.stream().filter(LessonRow::trial).count(),
                chapters(lessons),
                CourseJson.includes(row.includes()).stream()
                        .map(item -> new CourseIncludeView(item.name(), item.quantity())).toList(),
                CourseJson.specs(row.extraSpecs()).stream()
                        .map(item -> new CourseSpecView(item.label(), item.value())).toList(),
                CourseJson.strings(row.intro()),
                row.priceAmountMinor() != null && collecting && !lessons.isEmpty(),
                collecting,
                row.experienceItemId(), playableSimulation(row));
    }

    /** 这个账号和这门课的关系：开通着（没过期）或待付款的那一条。 */
    @Transactional(readOnly = true)
    public CourseMineView mine(UUID accountId, UUID courseId) {
        requireAccount(accountId);
        CourseRow row = listedOnline(courseId);
        OffsetDateTime now = now();
        for (EnrollmentRow enrollment : mapper.findLiveEnrollments(accountId, row.id())) {
            if (CourseEnrollmentRecord.ACTIVE.equals(enrollment.status()) && !expired(enrollment, now)) {
                return new CourseMineView(row.id(), enrollment.id(), CourseEnrollmentStatus.ACTIVE, null);
            }
        }
        for (EnrollmentRow enrollment : mapper.findLiveEnrollments(accountId, row.id())) {
            if (CourseEnrollmentRecord.PENDING_PAYMENT.equals(enrollment.status())) {
                return new CourseMineView(row.id(), enrollment.id(), CourseEnrollmentStatus.PENDING_PAYMENT,
                        enrollment.tradeOrderId());
            }
        }
        return new CourseMineView(row.id(), null, null, null);
    }

    /** 试听：courseId 和 experienceItemId 二选一。只给试听课时；不登录也能看，不记进度。 */
    @Transactional(readOnly = true)
    public CourseTrialView trial(UUID courseId, UUID experienceItemId) {
        if ((courseId == null) == (experienceItemId == null)) {
            throw new IllegalArgumentException("exactly one of courseId and experienceItemId is required");
        }
        CourseRow row = courseId != null ? mapper.findCourse(courseId) : mapper.findCourseByExperience(experienceItemId);
        if (row == null || !isOnline(row) || !CourseRecordStatus.LISTED.equals(row.status())) {
            throw new CourseException(Error.NOT_FOUND);
        }
        List<LessonRow> lessons = lessonsByCourse(List.of(row.id())).getOrDefault(row.id(), List.of());
        List<CourseTrialLessonView> trial = new ArrayList<>();
        for (int index = 0; index < lessons.size(); index++) {
            LessonRow lesson = lessons.get(index);
            if (lesson.trial()) {
                trial.add(new CourseTrialLessonView(lesson.id(), index + 1, lesson.chapterTitle(), lesson.title(),
                        lesson.durationSeconds(), secureUrl(lesson.videoUrl())));
            }
        }
        if (trial.isEmpty()) {
            throw new CourseException(Error.NOT_FOUND);
        }
        boolean collecting = payments.merchantCanCollect(row.organizationId());
        return new CourseTrialView(row.id(), row.title(), cover(row), organization(row.organizationId()), lessons.size(),
                row.priceAmountMinor() == null ? null : new CoursePriceView(CoursePriceMode.FIXED, row.priceAmountMinor()),
                row.priceAmountMinor() != null && collecting, playableSimulation(row), List.copyOf(trial));
    }

    private UUID playableSimulation(CourseRow course) {
        UUID itemId = course.simulationItemId();
        return itemId != null && simulations.isPlayable(itemId) ? itemId : null;
    }

    // ---- 购买 ----

    /**
     * 买一门线上课。同一个幂等键重复提交返回第一次的结果（created 为 false）；这门课已有一张待付款的也直接返回它。
     */
    @Transactional
    public CourseEnrollResult enroll(UUID accountId, UUID idempotencyKey, UUID courseId, Long expectedAmountMinor) {
        requireAccount(accountId);
        if (idempotencyKey == null || courseId == null || expectedAmountMinor == null || expectedAmountMinor < 1) {
            throw new IllegalArgumentException("enrollment is invalid");
        }
        String requestHash = hash(courseId + "|" + expectedAmountMinor);
        CourseRow course = mapper.lockCourse(courseId);
        if (course == null) {
            throw new CourseException(Error.NOT_FOUND);
        }
        // 拿到课程锁之后再查幂等键：两个相同的请求同时到，后到的在这里读到先到的结果。
        KeyRow replayed = mapper.findByKey(accountId, idempotencyKey);
        if (replayed != null) {
            if (!requestHash.equals(replayed.requestHash().trim())) {
                throw new CourseException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new CourseEnrollResult(enrollment(accountId, replayed.id()), false);
        }
        if (!CourseRecordStatus.LISTED.equals(course.status()) || !isOnline(course) || course.priceAmountMinor() == null) {
            throw new CourseException(Error.NOT_PURCHASABLE);
        }
        OffsetDateTime now = now();
        for (EnrollmentRow live : mapper.findLiveEnrollments(accountId, courseId)) {
            if (CourseEnrollmentRecord.ACTIVE.equals(live.status()) && !expired(live, now)) {
                throw new CourseException(Error.ALREADY_OWNED);
            }
        }
        for (EnrollmentRow live : mapper.findLiveEnrollments(accountId, courseId)) {
            if (CourseEnrollmentRecord.PENDING_PAYMENT.equals(live.status())) {
                return new CourseEnrollResult(enrollment(accountId, live.id()), false);
            }
        }
        if (!payments.merchantCanCollect(course.organizationId())) {
            throw new CourseException(Error.PAYMENT_UNAVAILABLE);
        }
        long amount = course.priceAmountMinor();
        if (expectedAmountMinor != amount) {
            throw new CourseException(Error.AMOUNT_CHANGED);
        }
        if (lessonsByCourse(List.of(courseId)).getOrDefault(courseId, List.of()).isEmpty()) {
            throw new CourseException(Error.NOT_PURCHASABLE);
        }
        UUID enrollmentId = UUID.randomUUID();
        CoursePorts.OpenedPayment opened = payments.open(new CoursePorts.OpenPayment(
                enrollmentId,
                accountId,
                course.organizationId(),
                course.id(),
                course.title(),
                OPTION_NAME,
                course.coverUrl(),
                amount,
                now.plus(PAY_WINDOW).toInstant()));
        mapper.insertPurchase(enrollmentId, accountId, courseId, amount, opened.tradeOrderId(),
                OffsetDateTime.ofInstant(opened.payBefore(), ZoneOffset.UTC), idempotencyKey, requestHash, now);
        mapper.insertEvent(enrollmentId, "ORDERED", accountId, now);
        return new CourseEnrollResult(enrollment(accountId, enrollmentId), true);
    }

    // ---- 付款结果（交易模块在改交易订单状态的同一个事务里调用，交易订单已锁） ----

    /**
     * 付款到账：待付款的开通；没付款就关闭了的，这门课没有别的开通着时恢复开通。
     *
     * @return false 表示收不下（已经通过别的方式开通着），交易模块随即整单原路退款
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onPaymentConfirmed(UUID tradeOrderId, UUID enrollmentId) {
        EnrollmentRow found = mapper.findPaidEnrollment(enrollmentId, tradeOrderId);
        if (found == null) {
            log.error("trade order {} was paid for an unknown course enrollment {}", tradeOrderId, enrollmentId);
            return false;
        }
        CourseRow course = mapper.lockCourse(found.courseId());
        EnrollmentRow enrollment = mapper.lockPaidEnrollment(enrollmentId, tradeOrderId);
        OffsetDateTime now = now();
        boolean pending = CourseEnrollmentRecord.PENDING_PAYMENT.equals(enrollment.status());
        if (!pending && !CourseEnrollmentRecord.CLOSED.equals(enrollment.status())) {
            // 已经按付款处理过（之后可能退款了）。
            return true;
        }
        for (EnrollmentRow live : mapper.findLiveEnrollments(enrollment.accountId(), enrollment.courseId())) {
            if (!live.id().equals(enrollmentId) && CourseEnrollmentRecord.ACTIVE.equals(live.status())
                    && !expired(live, now)) {
                log.warn("course enrollment {} was paid but the account already has the course; it will be refunded",
                        enrollmentId);
                if (pending) {
                    mapper.closeUnpaid(enrollmentId, now);
                    mapper.insertEvent(enrollmentId, "PAYMENT_CLOSED", null, now);
                }
                return false;
            }
        }
        mapper.expireSuperseded(enrollment.accountId(), enrollment.courseId(), now);
        mapper.activate(enrollmentId, expiresAt(course, now), now);
        mapper.insertEvent(enrollmentId, pending ? "PAYMENT_CONFIRMED" : "PAYMENT_RESTORED", null, now);
        return true;
    }

    /** 交易订单没付款就关闭了（超时或取消）：待付款的开通记录关闭。别的状态不动。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentClosed(UUID tradeOrderId, UUID enrollmentId) {
        EnrollmentRow found = mapper.findPaidEnrollment(enrollmentId, tradeOrderId);
        if (found == null) {
            log.error("trade order {} closed for an unknown course enrollment {}", tradeOrderId, enrollmentId);
            return;
        }
        mapper.lockCourse(found.courseId());
        EnrollmentRow enrollment = mapper.lockPaidEnrollment(enrollmentId, tradeOrderId);
        if (!CourseEnrollmentRecord.PENDING_PAYMENT.equals(enrollment.status())) {
            return;
        }
        OffsetDateTime now = now();
        mapper.closeUnpaid(enrollmentId, now);
        mapper.insertEvent(enrollmentId, "PAYMENT_CLOSED", null, now);
    }

    // ---- 商品附带 ----

    /** 完成的商城订单的一行：商品、商品名、规格名。 */
    public record BundledLine(UUID productId, String productName, String skuName) {
    }

    /**
     * 商城订单完成：订单里附带数字课程的商品，给买家开通这些课程（同一门课只开一次；已经开通着的跳过）。
     * 同一张订单再告诉一次没有变化。返回这次新开通了几门。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int grantBundled(UUID buyerId, UUID orderId, String orderNo, List<BundledLine> lines, Instant at) {
        if (buyerId == null || orderId == null || lines == null || lines.isEmpty()) {
            return 0;
        }
        Map<UUID, BundledLine> byProduct = new LinkedHashMap<>();
        lines.stream().filter(line -> line.productId() != null).forEach(line -> byProduct.putIfAbsent(line.productId(), line));
        if (byProduct.isEmpty()) {
            return 0;
        }
        Map<UUID, BundledLine> byCourse = new LinkedHashMap<>();
        for (BundleRow bundle : mapper.findBundledCourses(byProduct.keySet())) {
            byCourse.putIfAbsent(bundle.courseId(), byProduct.get(bundle.productId()));
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(at == null ? clock.instant() : at, ZoneOffset.UTC);
        int granted = 0;
        // 按课程 ID 排着锁，两张订单同时开通同几门课也不会互相等死。
        List<UUID> courseIds = byCourse.keySet().stream().sorted().toList();
        for (UUID courseId : courseIds) {
            CourseRow course = mapper.lockCourse(courseId);
            if (course == null) {
                continue;
            }
            boolean owned = mapper.findLiveEnrollments(buyerId, courseId).stream()
                    .anyMatch(live -> CourseEnrollmentRecord.ACTIVE.equals(live.status()) && !expired(live, now));
            if (owned) {
                continue;
            }
            mapper.expireSuperseded(buyerId, courseId, now);
            BundledLine line = byCourse.get(courseId);
            UUID enrollmentId = UUID.randomUUID();
            if (mapper.insertBundle(enrollmentId, buyerId, courseId, expiresAt(course, now), orderId,
                    clip(orderNo, 40), clip(line.productName(), 160), clip(line.skuName(), 80), now) > 0) {
                mapper.insertEvent(enrollmentId, "BUNDLED", null, now);
                granted++;
            }
        }
        return granted;
    }

    // ---- 我的课程、学习 ----

    @Transactional(readOnly = true)
    public CourseEnrollmentPageView myEnrollments(UUID accountId, String bucketCode, int page, int size) {
        requireAccount(accountId);
        requirePage(page, size);
        CourseEnrollmentBucket bucket = bucketCode == null || bucketCode.isBlank()
                ? CourseEnrollmentBucket.ALL : parse(CourseEnrollmentBucket.class, bucketCode);
        OffsetDateTime now = now();
        long total = mapper.countEnrollments(accountId, bucket.name(), now);
        List<EnrollmentRow> rows = mapper.findEnrollmentPage(accountId, bucket.name(), now, size,
                (long) (page - 1) * size);
        Map<UUID, CourseRow> courses = courses(rows.stream().map(EnrollmentRow::courseId).toList());
        Map<UUID, List<LessonRow>> lessons = lessonsByCourse(courses.keySet());
        Map<UUID, List<ProgressRow>> progress = progressByEnrollment(rows.stream().map(EnrollmentRow::id).toList());
        List<CourseEnrollmentCardView> items = new ArrayList<>();
        for (EnrollmentRow row : rows) {
            CourseRow course = courses.get(row.courseId());
            List<LessonRow> courseLessons = lessons.getOrDefault(row.courseId(), List.of());
            List<ProgressRow> learned = progress.getOrDefault(row.id(), List.of());
            items.add(new CourseEnrollmentCardView(
                    row.id(),
                    row.courseId(),
                    course.title(),
                    cover(course),
                    CourseEnrollmentSource.valueOf(row.source()),
                    status(row, now),
                    courseLessons.size(),
                    completedCount(courseLessons, learned),
                    resume(courseLessons, learned),
                    bundle(row),
                    row.expiresAt()));
        }
        List<CourseTrialCardView> trials = bucket == CourseEnrollmentBucket.ALL && page == 1
                ? trialCards(accountId) : List.of();
        return new CourseEnrollmentPageView(page, size, total, (long) page * size < total, List.copyOf(items), trials);
    }

    /** 体验中心“课程试听”的那些课程里，这个账号还没开通的。 */
    private List<CourseTrialCardView> trialCards(UUID accountId) {
        Set<UUID> owned = Set.copyOf(mapper.findActiveCourseIds(accountId));
        List<CourseRow> listed = mapper.findListedCourses(null, LISTED_LIMIT).stream()
                .filter(CourseService::isOnline)
                .filter(row -> row.experienceItemId() != null && !owned.contains(row.id()))
                .toList();
        Map<UUID, List<LessonRow>> lessons = lessonsByCourse(listed.stream().map(CourseRow::id).toList());
        List<CourseTrialCardView> cards = new ArrayList<>();
        for (CourseRow row : listed) {
            int trial = (int) lessons.getOrDefault(row.id(), List.of()).stream().filter(LessonRow::trial).count();
            if (trial > 0) {
                cards.add(new CourseTrialCardView(row.id(), row.title(), cover(row), trial));
            }
        }
        return List.copyOf(cards);
    }

    @Transactional(readOnly = true)
    public CourseEnrollmentView enrollment(UUID accountId, UUID enrollmentId) {
        requireAccount(accountId);
        if (enrollmentId == null) {
            throw new IllegalArgumentException("enrollment id is required");
        }
        EnrollmentRow row = mapper.findEnrollment(accountId, enrollmentId);
        if (row == null) {
            throw new CourseException(Error.NOT_FOUND);
        }
        return view(row, now());
    }

    /** 拿一节课的播放地址，记“开始学这一节”；第一次开始一节非试听课时之后不能在线退款。 */
    @Transactional
    public CoursePlaybackView playback(UUID accountId, UUID enrollmentId, UUID lessonId) {
        requireAccount(accountId);
        if (enrollmentId == null || lessonId == null) {
            throw new IllegalArgumentException("lesson is required");
        }
        EnrollmentRow row = mapper.lockEnrollment(accountId, enrollmentId);
        if (row == null) {
            throw new CourseException(Error.NOT_FOUND);
        }
        LessonRow lesson = lessonOf(row.courseId(), lessonId);
        OffsetDateTime now = now();
        requireLearnable(row, now);
        mapper.recordPlayback(enrollmentId, lessonId, now);
        EnrollmentRow after = row;
        if (!lesson.trial() && row.startedAt() == null) {
            mapper.markStarted(enrollmentId, now);
            mapper.insertEvent(enrollmentId, "STARTED", accountId, now);
            after = mapper.findEnrollment(accountId, enrollmentId);
        }
        return new CoursePlaybackView(enrollmentId, lessonId, secureUrl(lesson.videoUrl()), lesson.durationSeconds(),
                refundability(after, now));
    }

    /** 这一节播完了。要先拿过这一节的播放地址；已经学完的再说一次没有变化。 */
    @Transactional
    public CourseEnrollmentView complete(UUID accountId, UUID enrollmentId, UUID lessonId) {
        requireAccount(accountId);
        if (enrollmentId == null || lessonId == null) {
            throw new IllegalArgumentException("lesson is required");
        }
        EnrollmentRow row = mapper.lockEnrollment(accountId, enrollmentId);
        if (row == null) {
            throw new CourseException(Error.NOT_FOUND);
        }
        lessonOf(row.courseId(), lessonId);
        OffsetDateTime now = now();
        requireLearnable(row, now);
        boolean started = mapper.findProgress(List.of(enrollmentId)).stream()
                .anyMatch(progress -> progress.lessonId().equals(lessonId));
        if (!started) {
            throw new CourseException(Error.LESSON_NOT_STARTED);
        }
        mapper.markLessonCompleted(enrollmentId, lessonId, now);
        return view(mapper.findEnrollment(accountId, enrollmentId), now);
    }

    // ---- 退款 ----

    /** 自己买的、开通着、付款后 7 天内、没开始学非试听课时：全额原路退回，课程关闭。已经退过的返回原样。 */
    @Transactional
    public CourseEnrollmentView refund(UUID accountId, UUID enrollmentId) {
        requireAccount(accountId);
        if (enrollmentId == null) {
            throw new IllegalArgumentException("enrollment id is required");
        }
        EnrollmentRow found = mapper.findEnrollment(accountId, enrollmentId);
        if (found == null) {
            throw new CourseException(Error.NOT_FOUND);
        }
        if (CourseEnrollmentRecord.REFUNDED.equals(found.status())) {
            return view(found, now());
        }
        if (found.tradeOrderId() != null) {
            payments.lockOrder(found.tradeOrderId());
        }
        EnrollmentRow row = mapper.lockEnrollment(accountId, enrollmentId);
        OffsetDateTime now = now();
        CourseRefundabilityView refundability = refundability(row, now);
        if (!refundability.refundable()) {
            throw new CourseException(Error.REFUND_NOT_ALLOWED, Map.of("reason", refundability.blockedReason().name()));
        }
        UUID refundId = payments.requestRefund(
                row.tradeOrderId(),
                row.amountMinor(),
                REFUND_REASON,
                UUID.nameUUIDFromBytes(("course-refund:" + enrollmentId).getBytes(StandardCharsets.UTF_8)),
                accountId);
        if (mapper.markRefunded(enrollmentId, refundId, row.amountMinor(), now) == 0) {
            throw new IllegalStateException("course enrollment changed while refunding");
        }
        mapper.insertEvent(enrollmentId, "REFUND_REQUESTED", accountId, now);
        return view(mapper.findEnrollment(accountId, enrollmentId), now);
    }

    // ---- 拼视图 ----

    private CourseEnrollmentView view(EnrollmentRow row, OffsetDateTime now) {
        CourseRow course = mapper.findCourse(row.courseId());
        List<LessonRow> lessons = lessonsByCourse(List.of(row.courseId())).getOrDefault(row.courseId(), List.of());
        List<ProgressRow> learned = progressByEnrollment(List.of(row.id())).getOrDefault(row.id(), List.of());
        Map<UUID, ProgressRow> byLesson = learned.stream()
                .collect(Collectors.toMap(ProgressRow::lessonId, Function.identity(), (a, b) -> a));
        List<CourseProgressChapterView> chapters = new ArrayList<>();
        Map<UUID, List<CourseProgressLessonView>> grouped = new LinkedHashMap<>();
        Map<UUID, String> titles = new LinkedHashMap<>();
        for (int index = 0; index < lessons.size(); index++) {
            LessonRow lesson = lessons.get(index);
            titles.putIfAbsent(lesson.chapterId(), lesson.chapterTitle());
            ProgressRow progress = byLesson.get(lesson.id());
            CourseLessonState state = progress == null ? CourseLessonState.NOT_STARTED
                    : progress.completedAt() != null ? CourseLessonState.COMPLETED : CourseLessonState.STARTED;
            grouped.computeIfAbsent(lesson.chapterId(), ignored -> new ArrayList<>()).add(new CourseProgressLessonView(
                    lesson.id(), index + 1, lesson.title(), lesson.durationSeconds(), lesson.trial(), state));
        }
        grouped.forEach((chapterId, items) -> chapters.add(new CourseProgressChapterView(
                chapterId,
                titles.get(chapterId),
                (int) items.stream().filter(item -> item.state() == CourseLessonState.COMPLETED).count(),
                List.copyOf(items))));
        CoursePaymentView payment = row.tradeOrderId() == null ? null
                : new CoursePaymentView(row.tradeOrderId(), row.amountMinor(), row.payBefore(), row.paidAt());
        CourseRefundView refund = null;
        if (row.refundId() != null) {
            CoursePorts.RefundProgress progress = payments.refundStates(List.of(row.refundId())).get(row.refundId());
            refund = new CourseRefundView(row.refundId(),
                    row.refundAmountMinor() == null ? row.amountMinor() : row.refundAmountMinor(),
                    progress == null ? CourseRefundStatus.REQUESTED : refundStatus(progress.status()),
                    row.refundedAt());
        }
        return new CourseEnrollmentView(
                row.id(),
                row.courseId(),
                course.title(),
                cover(course),
                organization(course.organizationId()),
                CourseEnrollmentSource.valueOf(row.source()),
                status(row, now),
                lessons.size(),
                completedCount(lessons, learned),
                resume(lessons, learned),
                row.activatedAt(),
                row.expiresAt(),
                payment,
                bundle(row),
                refund,
                refundability(row, now),
                List.copyOf(chapters),
                row.version());
    }

    private static CourseRefundStatus refundStatus(String status) {
        try {
            return CourseRefundStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return CourseRefundStatus.REQUESTED;
        }
    }

    static CourseRefundabilityView refundability(EnrollmentRow row, OffsetDateTime now) {
        if (CourseEnrollmentSource.BUNDLE.name().equals(row.source())) {
            return new CourseRefundabilityView(false, null, CourseRefundBlock.BUNDLED);
        }
        OffsetDateTime deadline = row.paidAt() == null ? null : row.paidAt().plus(REFUND_WINDOW);
        if (!CourseEnrollmentRecord.ACTIVE.equals(row.status()) || expired(row, now) || deadline == null) {
            return new CourseRefundabilityView(false, deadline, CourseRefundBlock.NOT_ACTIVE);
        }
        if (row.startedAt() != null) {
            return new CourseRefundabilityView(false, deadline, CourseRefundBlock.STARTED);
        }
        if (!now.isBefore(deadline)) {
            return new CourseRefundabilityView(false, deadline, CourseRefundBlock.WINDOW_PASSED);
        }
        return new CourseRefundabilityView(true, deadline, null);
    }

    static CourseEnrollmentStatus status(EnrollmentRow row, OffsetDateTime now) {
        return switch (row.status()) {
            case CourseEnrollmentRecord.ACTIVE -> expired(row, now) ? CourseEnrollmentStatus.EXPIRED : CourseEnrollmentStatus.ACTIVE;
            case CourseEnrollmentRecord.EXPIRED -> CourseEnrollmentStatus.EXPIRED;
            case CourseEnrollmentRecord.PENDING_PAYMENT -> CourseEnrollmentStatus.PENDING_PAYMENT;
            case CourseEnrollmentRecord.REFUNDED -> CourseEnrollmentStatus.REFUNDED;
            default -> CourseEnrollmentStatus.CLOSED;
        };
    }

    private static boolean expired(EnrollmentRow row, OffsetDateTime now) {
        return row.expiresAt() != null && !now.isBefore(row.expiresAt());
    }

    private static void requireLearnable(EnrollmentRow row, OffsetDateTime now) {
        if (!CourseEnrollmentRecord.ACTIVE.equals(row.status()) || expired(row, now)) {
            throw new CourseException(Error.NOT_LEARNABLE);
        }
    }

    private static int completedCount(List<LessonRow> lessons, List<ProgressRow> learned) {
        Set<UUID> ids = lessons.stream().map(LessonRow::id).collect(Collectors.toSet());
        return (int) learned.stream().filter(progress -> progress.completedAt() != null && ids.contains(progress.lessonId()))
                .count();
    }

    /** 接着学的那一节：从最后看的一节往后第一节没学完的，到末尾回到开头；一节都没看过时是第一节没学完的。 */
    static CourseResumeView resume(List<LessonRow> lessons, List<ProgressRow> learned) {
        if (lessons.isEmpty()) {
            return null;
        }
        Map<UUID, ProgressRow> byLesson = learned.stream()
                .collect(Collectors.toMap(ProgressRow::lessonId, Function.identity(), (a, b) -> a));
        int last = -1;
        OffsetDateTime latest = null;
        for (int index = 0; index < lessons.size(); index++) {
            ProgressRow progress = byLesson.get(lessons.get(index).id());
            if (progress != null && (latest == null || progress.lastPlayedAt().isAfter(latest))) {
                latest = progress.lastPlayedAt();
                last = index;
            }
        }
        int start = last < 0 ? 0 : last;
        for (int step = 0; step < lessons.size(); step++) {
            int index = (start + step) % lessons.size();
            LessonRow lesson = lessons.get(index);
            ProgressRow progress = byLesson.get(lesson.id());
            if (progress == null || progress.completedAt() == null) {
                return new CourseResumeView(lesson.id(), index + 1, lesson.title());
            }
        }
        return null;
    }

    private static CourseBundleSourceView bundle(EnrollmentRow row) {
        return row.bundleOrderId() == null ? null : new CourseBundleSourceView(row.bundleOrderId(),
                Objects.requireNonNullElse(row.bundleOrderNo(), ""),
                Objects.requireNonNullElse(row.bundleProductName(), ""),
                Objects.requireNonNullElse(row.bundleSkuName(), ""));
    }

    private static List<CourseChapterView> chapters(List<LessonRow> lessons) {
        Map<UUID, List<CourseLessonView>> grouped = new LinkedHashMap<>();
        Map<UUID, String> titles = new LinkedHashMap<>();
        for (int index = 0; index < lessons.size(); index++) {
            LessonRow lesson = lessons.get(index);
            titles.putIfAbsent(lesson.chapterId(), lesson.chapterTitle());
            grouped.computeIfAbsent(lesson.chapterId(), ignored -> new ArrayList<>()).add(new CourseLessonView(
                    lesson.id(), index + 1, lesson.title(), lesson.durationSeconds(), lesson.trial()));
        }
        List<CourseChapterView> chapters = new ArrayList<>();
        grouped.forEach((chapterId, items) -> chapters.add(new CourseChapterView(chapterId, titles.get(chapterId),
                List.copyOf(items))));
        return List.copyOf(chapters);
    }

    private CourseOrganizationView organization(UUID organizationId) {
        String name = organizations.names(List.of(organizationId)).getOrDefault(organizationId, "");
        boolean reviewed = organizations.reviewed(List.of(organizationId)).contains(organizationId);
        return new CourseOrganizationView(organizationId, name, reviewed);
    }

    private CourseRow listedOnline(UUID courseId) {
        if (courseId == null) {
            throw new IllegalArgumentException("course id is required");
        }
        CourseRow row = mapper.findCourse(courseId);
        if (row == null || !isOnline(row) || !CourseRecordStatus.LISTED.equals(row.status())) {
            throw new CourseException(Error.NOT_FOUND);
        }
        return row;
    }

    private LessonRow lessonOf(UUID courseId, UUID lessonId) {
        return lessonsByCourse(List.of(courseId)).getOrDefault(courseId, List.of()).stream()
                .filter(lesson -> lesson.id().equals(lessonId))
                .findFirst()
                .orElseThrow(() -> new CourseException(Error.NOT_FOUND));
    }

    private Map<UUID, CourseRow> courses(Collection<UUID> ids) {
        Map<UUID, CourseRow> courses = new LinkedHashMap<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            CourseRow row = mapper.findCourse(id);
            if (row != null) {
                courses.put(id, row);
            }
        }
        return courses;
    }

    /** 课时按全课顺序。 */
    private Map<UUID, List<LessonRow>> lessonsByCourse(Collection<UUID> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<LessonRow>> grouped = new LinkedHashMap<>();
        for (LessonRow lesson : mapper.findLessons(List.copyOf(new LinkedHashSet<>(courseIds)))) {
            grouped.computeIfAbsent(lesson.courseId(), ignored -> new ArrayList<>()).add(lesson);
        }
        grouped.replaceAll((id, list) -> list.stream().sorted(Comparator.comparingInt(LessonRow::sortOrder)).toList());
        return grouped;
    }

    private Map<UUID, List<ProgressRow>> progressByEnrollment(Collection<UUID> enrollmentIds) {
        if (enrollmentIds.isEmpty()) {
            return Map.of();
        }
        return mapper.findProgress(List.copyOf(enrollmentIds)).stream()
                .collect(Collectors.groupingBy(ProgressRow::enrollmentId));
    }

    private static OffsetDateTime expiresAt(CourseRow course, OffsetDateTime activatedAt) {
        return course.validityDays() == null ? null : activatedAt.plusDays(course.validityDays());
    }

    private static CourseImageView cover(CourseRow row) {
        return isHttps(row.coverUrl()) ? new CourseImageView(row.coverUrl(), row.title()) : null;
    }

    private static boolean isOnline(CourseRow row) {
        return CourseMode.ONLINE.name().equals(row.deliveryMode());
    }

    private static boolean isHttps(String url) {
        return url != null && url.startsWith("https://") && url.length() > "https://".length();
    }

    /** 只给受控 HTTPS 地址。 */
    static String secureUrl(String url) {
        return isHttps(url) ? url : null;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String code) {
        try {
            return Enum.valueOf(type, code);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(type.getSimpleName() + " is invalid", unknown);
        }
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || page > 10_000 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
    }

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new CourseException(Error.FORBIDDEN);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
