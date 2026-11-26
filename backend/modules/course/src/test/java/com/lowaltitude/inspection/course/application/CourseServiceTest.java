package com.lowaltitude.inspection.course.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.course.application.CourseException.Error;
import com.lowaltitude.inspection.course.application.ops.CourseOpsError;
import com.lowaltitude.inspection.course.application.ops.CourseOpsService;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.CourseDraft;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.CreateCommand;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.Operator;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.BundleRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.CourseRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.EnrollmentRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.LessonRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.ProgressRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseOpsMapper;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 课程的规则（S5-5）：列表里线下班跟着活动走、价格排序把没有标价的放最后；购买的拒绝顺序和已有待付款的直接返回；
 * 付款到账时已经开通着就收不下；附带课程开通一次；学习先拿播放地址再算学完；退款的四种拒绝。
 * SQL 本身在真实库上由冒烟脚本 infra/scripts/trade-smoke/course.cjs 验证。
 */
class CourseServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG = UUID.fromString("a1000000-0000-4000-8000-000000000003");
    private static final UUID FLY = UUID.fromString("d7c00000-0000-4000-8000-000000000001");
    private static final UUID CAMP = UUID.fromString("d7c00000-0000-4000-8000-000000000003");
    private static final UUID CERT = UUID.fromString("d7c00000-0000-4000-8000-000000000004");
    private static final UUID HIDDEN = UUID.fromString("d7c00000-0000-4000-8000-000000000005");
    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000002");
    private static final UUID INQUIRY_ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000004");
    private static final UUID CHAPTER = UUID.randomUUID();
    private static final UUID TRIAL = UUID.randomUUID();
    private static final UUID PAID_LESSON = UUID.randomUUID();
    private static final UUID TRADE_ORDER = UUID.randomUUID();

    private final CourseMapper mapper = mock(CourseMapper.class);
    private final CoursePorts.Payments payments = mock(CoursePorts.Payments.class);
    private final CoursePorts.Activities activities = mock(CoursePorts.Activities.class);
    private final CoursePorts.Organizations organizations = mock(CoursePorts.Organizations.class);
    private final CoursePorts.Simulations simulations = mock(CoursePorts.Simulations.class);
    private final CourseService service = new CourseService(mapper, payments, activities, organizations, simulations,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void organizationsAreKnown() {
        when(organizations.names(anyCollection())).thenReturn(Map.of(ORG, "示例培训机构"));
        when(organizations.reviewed(anyCollection())).thenReturn(Set.of());
        when(mapper.findLessons(anyCollection())).thenAnswer(call -> {
            Collection<?> ids = call.getArgument(0);
            return ids.contains(FLY) ? List.of(lesson(TRIAL, true, 1), lesson(PAID_LESSON, false, 2)) : List.of();
        });
    }

    // ---- 列表 ----

    @Test
    void offlineClassesFollowTheirActivityAndUnpricedCardsSortLast() {
        when(mapper.findListedCourses(isNull(), anyInt())).thenReturn(List.of(
                online(FLY, 19900L, "LISTED"), offline(CAMP, ACTIVITY), offline(CERT, INQUIRY_ACTIVITY),
                offline(HIDDEN, UUID.randomUUID())));
        when(activities.find(eq(ACTIVITY), eq("370500"))).thenReturn(Optional.of(new CoursePorts.Activity(
                ACTIVITY, "机器人研学体验", null, null, "370500", "PER_PERSON", 16800L)));
        when(activities.find(eq(INQUIRY_ACTIVITY), eq("370500"))).thenReturn(Optional.of(new CoursePorts.Activity(
                INQUIRY_ACTIVITY, "多旋翼无人机驾驶员培训", null, null, "370500", "INQUIRY", null)));

        CoursePageView page = service.list("370500", null, null, "PRICE_ASC", 1, 20);

        assertThat(page.items()).extracting(CourseCardView::courseId).containsExactly(CAMP, FLY, CERT);
        assertThat(page.items().getFirst().title()).isEqualTo("机器人研学体验");
        assertThat(page.items().getFirst().activityId()).isEqualTo(ACTIVITY);
        assertThat(page.items().getLast().price()).isEqualTo(new CoursePriceView(CoursePriceMode.INQUIRY, null));
        assertThat(page.total()).isEqualTo(3);
        assertThatThrownBy(() -> service.list("东营", null, null, null, 1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("370500", "COOKING", null, null, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trialAndDetailOnlyExposeTheCoursesCurrentPlayableSimulation() {
        UUID trialEntry = UUID.randomUUID();
        UUID simulation = UUID.randomUUID();
        CourseRow linked = online(FLY, 19900L, "LISTED", trialEntry, simulation);
        when(mapper.findCourse(FLY)).thenReturn(linked);
        when(mapper.findCourseByExperience(trialEntry)).thenReturn(linked);
        when(simulations.isPlayable(simulation)).thenReturn(true);
        when(simulations.isPublishedCourseTrial(trialEntry)).thenReturn(true);
        assertThat(service.trial(FLY, null).simulationItemId()).isEqualTo(simulation);
        assertThat(service.trial(null, trialEntry).simulationItemId()).isEqualTo(simulation);
        assertThat(service.detail(FLY).simulationItemId()).isEqualTo(simulation);

        when(simulations.isPlayable(simulation)).thenReturn(false);
        assertThat(service.trial(null, trialEntry).simulationItemId()).isNull();
        assertThat(service.detail(FLY).experienceItemId()).isEqualTo(trialEntry);
        assertThat(service.detail(FLY).simulationItemId()).isNull();

        when(mapper.findCourse(FLY)).thenReturn(online(FLY, 19900L, "LISTED", trialEntry, null));
        assertThat(service.trial(FLY, null).simulationItemId()).isNull();
        verify(simulations, never()).isPlayable(trialEntry);

        when(simulations.isPublishedCourseTrial(trialEntry)).thenReturn(false);
        assertThatThrownBy(() -> service.trial(null, trialEntry)).isInstanceOf(CourseException.class);
        assertThat(service.trial(FLY, null).courseId()).isEqualTo(FLY);
    }

    @Test
    void operationsRejectInvalidSimulationTargetsOnCreationAndUpdate() {
        CourseOpsMapper writes = mock(CourseOpsMapper.class);
        CourseOpsService operations = new CourseOpsService(mapper, writes, organizations, payments, activities,
                simulations, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID item = UUID.randomUUID();
        Operator operator = new Operator(ACCOUNT, "课程运营");
        when(organizations.canHostCourses(ORG)).thenReturn(true);
        when(mapper.lockCourse(FLY)).thenReturn(online(FLY, 19900L, "DRAFT"));
        CreateCommand online = new CreateCommand("ONLINE", "DRONE", "录播课", ORG, null, item);
        CourseDraft edit = new CourseDraft("DRONE", "录播课", null, ORG, null, List.of(), 19900L, 365,
                null, null, null, List.of(), List.of(), List.of(), null, item, true, List.of(), null, null, 0);
        assertThatThrownBy(() -> operations.create(online, operator)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("simulationItemId");
        assertThatThrownBy(() -> operations.update(FLY, edit, 1, operator)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("simulationItemId");

        when(simulations.isPlayable(item)).thenReturn(true);
        CreateCommand offline = new CreateCommand("OFFLINE", "DRONE", "线下班", ORG, ACTIVITY, item);
        assertThatThrownBy(() -> operations.create(offline, operator)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("simulationItemId");
        org.mockito.Mockito.verifyNoInteractions(writes);
    }

    @Test
    void listedCoursesRetainTheirRequiredFieldsAndOnlyUnlistOnce() {
        CourseOpsMapper writes = mock(CourseOpsMapper.class);
        CourseOpsService operations = new CourseOpsService(mapper, writes, organizations, payments, activities,
                simulations, Clock.fixed(NOW, ZoneOffset.UTC));
        CourseRow listed = online(FLY, 19900L, "LISTED");
        when(mapper.lockCourse(FLY)).thenReturn(listed);
        when(mapper.findCourse(FLY)).thenReturn(listed);
        when(writes.bumpVersion(eq(FLY), eq(1), any())).thenReturn(1);
        Operator operator = new Operator(ACCOUNT, "课程运营");

        assertThatThrownBy(() -> operations.outline(FLY, 1, List.of(), operator)).isInstanceOfSatisfying(
                OpsException.class, ex -> {
                    assertThat(ex.error()).isEqualTo(CourseOpsError.PUBLISH_INCOMPLETE);
                    assertThat(ex.details()).containsKey("missing");
                });
        assertThatThrownBy(() -> operations.list(FLY, 1, operator)).isInstanceOfSatisfying(OpsException.class,
                ex -> assertThat(ex.error()).isEqualTo(CourseOpsError.STATUS_CONFLICT));

        when(writes.setStatus(eq(FLY), eq(1), eq("UNLISTED"), any())).thenReturn(1);
        CourseRow unlisted = online(FLY, 19900L, "UNLISTED");
        when(mapper.findCourse(FLY)).thenReturn(unlisted);
        assertThat(operations.unlist(FLY, 1, operator).status().name()).isEqualTo("UNLISTED");
        when(mapper.lockCourse(FLY)).thenReturn(unlisted);
        assertThatThrownBy(() -> operations.unlist(FLY, 1, operator)).isInstanceOfSatisfying(OpsException.class,
                ex -> assertThat(ex.error()).isEqualTo(CourseOpsError.STATUS_CONFLICT));
    }

    // ---- 购买 ----

    @Test
    void buyingIsRefusedInTheOrderThePageNeedsAndAPendingOneComesBack() {
        when(mapper.lockCourse(FLY)).thenReturn(online(FLY, null, "LISTED"));
        refused(() -> service.enroll(ACCOUNT, UUID.randomUUID(), FLY, 19900L), Error.NOT_PURCHASABLE);

        when(mapper.lockCourse(FLY)).thenReturn(online(FLY, 19900L, "LISTED"));
        when(mapper.findLiveEnrollments(ACCOUNT, FLY)).thenReturn(List.of(enrollment("ACTIVE", "PURCHASE", AT, null)));
        refused(() -> service.enroll(ACCOUNT, UUID.randomUUID(), FLY, 19900L), Error.ALREADY_OWNED);

        EnrollmentRow pending = enrollment("PENDING_PAYMENT", "PURCHASE", null, null);
        when(mapper.findLiveEnrollments(ACCOUNT, FLY)).thenReturn(List.of(pending));
        when(mapper.findEnrollment(ACCOUNT, pending.id())).thenReturn(pending);
        when(mapper.findCourse(FLY)).thenReturn(online(FLY, 19900L, "LISTED"));
        CourseEnrollResult again = service.enroll(ACCOUNT, UUID.randomUUID(), FLY, 19900L);
        assertThat(again.created()).isFalse();
        assertThat(again.enrollment().enrollmentId()).isEqualTo(pending.id());

        when(mapper.findLiveEnrollments(ACCOUNT, FLY)).thenReturn(List.of());
        when(payments.merchantCanCollect(ORG)).thenReturn(false);
        refused(() -> service.enroll(ACCOUNT, UUID.randomUUID(), FLY, 19900L), Error.PAYMENT_UNAVAILABLE);
        when(payments.merchantCanCollect(ORG)).thenReturn(true);
        refused(() -> service.enroll(ACCOUNT, UUID.randomUUID(), FLY, 9900L), Error.AMOUNT_CHANGED);
        verify(payments, never()).open(any());
    }

    // 身份 V013：开课机构资质没审核通过，课程详情、试听、购买都当作没上架。
    @Test
    void aCourseOfASuspendedOrganizationCannotBeSeenTriedOrBought() {
        when(mapper.lockCourse(FLY)).thenReturn(online(FLY, 19900L, "LISTED"));
        when(mapper.findCourse(FLY)).thenReturn(online(FLY, 19900L, "LISTED"));
        when(mapper.isListingSuspended(ORG)).thenReturn(true);

        refused(() -> service.enroll(ACCOUNT, UUID.randomUUID(), FLY, 19900L), Error.NOT_PURCHASABLE);
        refused(() -> service.trial(FLY, null), Error.NOT_FOUND);
        refused(() -> service.mine(ACCOUNT, FLY), Error.NOT_FOUND);
        verify(payments, never()).open(any());
    }

    @Test
    void aPurchaseOpensOneTradeOrderForTheOrganizationWithAFifteenMinuteWindow() {
        when(mapper.lockCourse(FLY)).thenReturn(online(FLY, 19900L, "LISTED"));
        when(mapper.findCourse(FLY)).thenReturn(online(FLY, 19900L, "LISTED"));
        when(payments.merchantCanCollect(ORG)).thenReturn(true);
        when(payments.open(any())).thenAnswer(call -> new CoursePorts.OpenedPayment(TRADE_ORDER,
                ((CoursePorts.OpenPayment) call.getArgument(0)).payBefore()));
        when(mapper.findEnrollment(eq(ACCOUNT), any())).thenAnswer(call -> new EnrollmentRow(call.getArgument(1), ACCOUNT,
                FLY, "PURCHASE", "PENDING_PAYMENT", 19900, TRADE_ORDER, AT.plusMinutes(15), null, null, null, null, null,
                null, null, null, null, null, null, 0, AT));

        CourseEnrollResult result = service.enroll(ACCOUNT, UUID.randomUUID(), FLY, 19900L);

        ArgumentCaptor<CoursePorts.OpenPayment> opened = ArgumentCaptor.forClass(CoursePorts.OpenPayment.class);
        verify(payments).open(opened.capture());
        assertThat(opened.getValue().organizationId()).isEqualTo(ORG);
        assertThat(opened.getValue().optionName()).isEqualTo("单人账号");
        assertThat(opened.getValue().amountMinor()).isEqualTo(19900);
        assertThat(opened.getValue().payBefore()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(result.created()).isTrue();
        assertThat(result.enrollment().status()).isEqualTo(CourseEnrollmentStatus.PENDING_PAYMENT);
        assertThat(result.enrollment().refundability().refundable()).isFalse();
    }

    // ---- 付款到账、附带开通 ----

    @Test
    void aPaymentForACourseAlreadyOpenIsRefusedSoTradeRefundsIt() {
        EnrollmentRow pending = enrollment("PENDING_PAYMENT", "PURCHASE", null, null);
        EnrollmentRow bundled = enrollment("ACTIVE", "BUNDLE", AT, null);
        when(mapper.findPaidEnrollment(pending.id(), TRADE_ORDER)).thenReturn(pending);
        when(mapper.lockPaidEnrollment(pending.id(), TRADE_ORDER)).thenReturn(pending);
        when(mapper.lockCourse(FLY)).thenReturn(online(FLY, 19900L, "LISTED"));
        when(mapper.findLiveEnrollments(ACCOUNT, FLY)).thenReturn(List.of(pending, bundled));

        assertThat(service.onPaymentConfirmed(TRADE_ORDER, pending.id())).isFalse();
        verify(mapper).closeUnpaid(pending.id(), AT);
        verify(mapper, never()).activate(any(), any(), any());

        when(mapper.findLiveEnrollments(ACCOUNT, FLY)).thenReturn(List.of(pending));
        assertThat(service.onPaymentConfirmed(TRADE_ORDER, pending.id())).isTrue();
        verify(mapper).activate(pending.id(), AT.plusDays(365), AT);
    }

    @Test
    void aCompletedMallOrderOpensItsBundledCourseOnceAndSkipsOnesAlreadyOpen() {
        UUID product = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(mapper.findBundledCourses(anyCollection())).thenReturn(List.of(new BundleRow(FLY, product)));
        when(mapper.lockCourse(FLY)).thenReturn(online(FLY, null, "LISTED"));
        when(mapper.insertBundle(any(), eq(ACCOUNT), eq(FLY), any(), eq(orderId), eq("MO1"), eq("编程机器人探索套装"),
                eq("标准套装（1 台 + 数字课程）"), any())).thenReturn(1);

        List<CourseService.BundledLine> lines = List.of(
                new CourseService.BundledLine(product, "编程机器人探索套装", "标准套装（1 台 + 数字课程）"));
        assertThat(service.grantBundled(ACCOUNT, orderId, "MO1", lines, NOW)).isEqualTo(1);

        when(mapper.findLiveEnrollments(ACCOUNT, FLY)).thenReturn(List.of(enrollment("ACTIVE", "PURCHASE", AT, null)));
        assertThat(service.grantBundled(ACCOUNT, orderId, "MO1", lines, NOW)).isZero();
    }

    // ---- 学习、退款 ----

    @Test
    void aLessonIsLearnedOnlyAfterItWasPlayedAndAPaidLessonEndsTheRefund() {
        EnrollmentRow active = enrollment("ACTIVE", "PURCHASE", AT.minusDays(1), null);
        when(mapper.lockEnrollment(ACCOUNT, active.id())).thenReturn(active);
        when(mapper.findProgress(anyCollection())).thenReturn(List.of());
        refused(() -> service.complete(ACCOUNT, active.id(), TRIAL), Error.LESSON_NOT_STARTED);

        EnrollmentRow started = withStarted(active, AT);
        when(mapper.findEnrollment(ACCOUNT, active.id())).thenReturn(started);
        CoursePlaybackView playback = service.playback(ACCOUNT, active.id(), PAID_LESSON);

        verify(mapper).recordPlayback(active.id(), PAID_LESSON, AT);
        verify(mapper).markStarted(active.id(), AT);
        assertThat(playback.refundability().blockedReason()).isEqualTo(CourseRefundBlock.STARTED);
        assertThat(playback.playUrl()).isEqualTo("https://cdn.example.com/2.mp4");

        service.playback(ACCOUNT, active.id(), TRIAL);
        verify(mapper).markStarted(eq(active.id()), any());
    }

    @Test
    void refundsAreOnlineOnlyForAPurchaseWithinSevenDaysBeforeLearning() {
        assertThat(CourseService.refundability(enrollment("ACTIVE", "BUNDLE", AT, null), AT).blockedReason())
                .isEqualTo(CourseRefundBlock.BUNDLED);
        assertThat(CourseService.refundability(enrollment("ACTIVE", "PURCHASE", AT.minusDays(8), null), AT).blockedReason())
                .isEqualTo(CourseRefundBlock.WINDOW_PASSED);
        assertThat(CourseService.refundability(withStarted(enrollment("ACTIVE", "PURCHASE", AT, null), AT), AT)
                .blockedReason()).isEqualTo(CourseRefundBlock.STARTED);
        assertThat(CourseService.refundability(enrollment("PENDING_PAYMENT", "PURCHASE", null, null), AT).blockedReason())
                .isEqualTo(CourseRefundBlock.NOT_ACTIVE);
        CourseRefundabilityView open = CourseService.refundability(enrollment("ACTIVE", "PURCHASE", AT.minusDays(6), null), AT);
        assertThat(open.refundable()).isTrue();
        assertThat(open.deadline()).isEqualTo(AT.plusDays(1));
        // 有效期过了读成已过期，也不能退。
        EnrollmentRow expired = enrollment("ACTIVE", "PURCHASE", AT.minusDays(3), AT.minusSeconds(1));
        assertThat(CourseService.status(expired, AT)).isEqualTo(CourseEnrollmentStatus.EXPIRED);
        assertThat(CourseService.refundability(expired, AT).blockedReason()).isEqualTo(CourseRefundBlock.NOT_ACTIVE);
    }

    @Test
    void theNextLessonIsTheFirstNotLearnedAfterTheLastPlayed() {
        List<LessonRow> lessons = List.of(lesson(TRIAL, true, 1), lesson(PAID_LESSON, false, 2));
        UUID enrollmentId = UUID.randomUUID();
        assertThat(CourseService.resume(lessons, List.of()).lessonId()).isEqualTo(TRIAL);
        assertThat(CourseService.resume(lessons, List.of(new ProgressRow(enrollmentId, TRIAL, AT, AT, AT))).no()).isEqualTo(2);
        assertThat(CourseService.resume(lessons, List.of(
                new ProgressRow(enrollmentId, TRIAL, AT, AT, AT),
                new ProgressRow(enrollmentId, PAID_LESSON, AT, AT, AT.plusMinutes(1))))).isNull();
    }

    // ---- 工具 ----

    private static void refused(Runnable call, Error error) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CourseException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    private static CourseRow online(UUID id, Long price, String status) {
        return online(id, price, status, null, null);
    }

    private static CourseRow online(UUID id, Long price, String status, UUID trialEntry, UUID simulation) {
        return new CourseRow(id, ORG, "ONLINE", "DRONE", "无人机飞行原理 线上课", "从升力到航线规划", null, "[]", price, 365,
                null, null, null, "[]", "[]", "[]", trialEntry, simulation, null, null, 30, status, AT, 1, AT, AT);
    }

    private static CourseRow offline(UUID id, UUID activityId) {
        return new CourseRow(id, ORG, "OFFLINE", "ROBOT", "线下班", null, null, "[]", null, null, null, null, null, "[]",
                "[]", "[]", null, null, activityId, "按场次", 20, "LISTED", AT, 1, AT, AT);
    }

    private static LessonRow lesson(UUID id, boolean trial, int no) {
        return new LessonRow(id, FLY, CHAPTER, "第 1 章", 1, "第 " + no + " 课", 900, trial,
                "https://cdn.example.com/" + no + ".mp4", no);
    }

    private static EnrollmentRow enrollment(String status, String source, OffsetDateTime paidAt, OffsetDateTime expiresAt) {
        boolean bundle = "BUNDLE".equals(source);
        return new EnrollmentRow(UUID.randomUUID(), ACCOUNT, FLY, source, status, bundle ? 0 : 19900,
                bundle ? null : TRADE_ORDER, null, bundle ? null : paidAt, paidAt, expiresAt, null, null, null, null,
                bundle ? UUID.randomUUID() : null, bundle ? "MO1" : null, bundle ? "套装" : null, bundle ? "标准套装" : null,
                0, AT);
    }

    private static EnrollmentRow withStarted(EnrollmentRow row, OffsetDateTime startedAt) {
        return new EnrollmentRow(row.id(), row.accountId(), row.courseId(), row.source(), row.status(), row.amountMinor(),
                row.tradeOrderId(), row.payBefore(), row.paidAt(), row.activatedAt(), row.expiresAt(), startedAt,
                row.refundId(), row.refundAmountMinor(), row.refundedAt(), row.bundleOrderId(), row.bundleOrderNo(),
                row.bundleProductName(), row.bundleSkuName(), row.version() + 1, row.createdAt());
    }
}
