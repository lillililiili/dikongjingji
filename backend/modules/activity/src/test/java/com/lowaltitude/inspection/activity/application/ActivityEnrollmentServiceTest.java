package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 报名服务的规则测试。mapper 换成内存实现（按 SQL 的条件和 V002 的约束写），时钟固定；
 * 要换时间时用同一份内存数据另起一个服务。SQL 文本本身由 ActivityEnrollmentMapperContractTest 检查。
 */
class ActivityEnrollmentServiceTest {

    /** 北京时间 2026-11-17 12:00。 */
    private static final Instant NOW = Instant.parse("2026-11-17T04:00:00Z");
    /** 北京时间 2026-11-28 09:00 开始、11:00 结束，前一天 18:00 截止报名。 */
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2026-11-28T01:00:00Z");
    private static final OffsetDateTime ENDS_AT = OffsetDateTime.parse("2026-11-28T03:00:00Z");
    private static final OffsetDateTime CLOSES_AT = OffsetDateTime.parse("2026-11-27T10:00:00Z");

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID ACTIVITY_ID = UUID.fromString("a2000000-0000-4000-8000-000000000004");
    private static final UUID SESSION_ID = UUID.fromString("a3000000-0000-4000-8000-000000000401");
    private static final UUID ORGANIZER = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID CO_ORGANIZER = UUID.fromString("a1000000-0000-4000-8000-000000000005");
    private static final UUID OTHER_ORGANIZATION = UUID.fromString("a1000000-0000-4000-8000-000000000009");
    private static final UUID OPERATOR = UUID.fromString("a1000000-0000-4000-8000-0000000000a4");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_KEY = UUID.fromString("e0000000-0000-4000-8000-000000000002");
    private static final UUID THIRD_KEY = UUID.fromString("e0000000-0000-4000-8000-000000000003");

    private static final String TITLE = "无人机开放体验日";
    private static final String COVER = "https://media.example.com/open-day.jpg";

    private final InMemoryMapper mapper = new InMemoryMapper();
    private int issuedCodes;
    private final ActivityEnrollmentService service = at(NOW);

    // ---- 参与人 ----

    @Test
    void selfIsCreatedOnceOnTheFirstListAndTakesTheAccountName() {
        ParticipantListView first = service.listParticipants(ACCOUNT, " 王女士 ", null);
        ParticipantListView second = service.listParticipants(ACCOUNT, "改过的名字", null);

        assertThat(first.items()).hasSize(1);
        ParticipantView self = first.items().get(0);
        assertThat(self.relation()).isEqualTo(ParticipantRelation.SELF);
        assertThat(self.name()).isEqualTo("王女士");
        // “本人”不收出生年月，没有年龄；不带 sessionId 时也没有能不能报的判断。
        assertThat(self.birthMonth()).isNull();
        assertThat(self.age()).isNull();
        assertThat(self.photoUseAuthorized()).isFalse();
        assertThat(self.eligibility()).isNull();
        assertThat(second.items()).containsExactly(self);
        assertThat(mapper.participants).hasSize(1);
    }

    @Test
    void selfGetsTheDefaultNameWhenTheAccountNameIsUnusableAndALongNameIsCut() {
        UUID blank = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID control = UUID.randomUUID();
        UUID longName = UUID.randomUUID();

        assertThat(service.listParticipants(blank, "   ", null).items().get(0).name()).isEqualTo("本人");
        assertThat(service.listParticipants(missing, null, null).items().get(0).name()).isEqualTo("本人");
        assertThat(service.listParticipants(control, "王\n女士", null).items().get(0).name()).isEqualTo("本人");
        assertThat(service.listParticipants(longName, "名".repeat(25), null).items().get(0).name())
                .isEqualTo("名".repeat(20));
    }

    @Test
    void listPutsSelfFirstThenChildrenInTheOrderTheyWereAdded() {
        UUID first = child("小明", "2018-05");
        UUID second = child("小红", "2018-12");

        List<ParticipantView> items = service.listParticipants(ACCOUNT, "王女士", null).items();

        // 小明 2018-05 生，到 2026-11 满 8 周岁；小红 2018-12 生，还差一个月，7 周岁。
        assertThat(items)
                .extracting(
                        ParticipantView::participantId,
                        ParticipantView::relation,
                        ParticipantView::name,
                        ParticipantView::birthMonth,
                        ParticipantView::age)
                .containsExactly(
                        tuple(items.get(0).participantId(), ParticipantRelation.SELF, "王女士", null, null),
                        tuple(first, ParticipantRelation.CHILD, "小明", "2018-05", 8),
                        tuple(second, ParticipantRelation.CHILD, "小红", "2018-12", 7));
    }

    @Test
    void eligibilityUsesTheChildsAgeOnTheDayOfTheSession() {
        mapper.session.minAge = 8;
        mapper.session.maxAge = 15;
        child("刚满八岁", "2018-11");
        child("差一个月", "2018-12");
        child("刚好十五", "2010-12");
        child("已满十六", "2010-11");

        List<ParticipantView> items = service.listParticipants(ACCOUNT, "王女士", SESSION_ID).items();

        assertThat(items.subList(1, 5))
                .extracting(
                        ParticipantView::name,
                        item -> item.eligibility().eligible(),
                        item -> item.eligibility().reason(),
                        item -> item.eligibility().ageAtSession())
                .containsExactly(
                        tuple("刚满八岁", true, ParticipantEligibilityReason.ELIGIBLE, 8),
                        tuple("差一个月", false, ParticipantEligibilityReason.UNDER_MIN_AGE, 7),
                        tuple("刚好十五", true, ParticipantEligibilityReason.ELIGIBLE, 15),
                        tuple("已满十六", false, ParticipantEligibilityReason.OVER_MAX_AGE, 16));
    }

    // 场次在北京时间 12 月 1 日 00:30 开始，按 UTC 还是 11 月 30 日：年龄要按北京的日期算。
    @Test
    void childAgeIsCountedByYearMonthOnTheBeijingDateOfTheSession() {
        mapper.session.startsAt = OffsetDateTime.parse("2026-11-30T16:30:00Z");
        mapper.session.endsAt = OffsetDateTime.parse("2026-11-30T18:30:00Z");
        mapper.session.minAge = 8;
        UUID self = self();
        UUID child = child("小红", "2018-12");

        ParticipantView listed = service.listParticipants(ACCOUNT, "王女士", SESSION_ID).items().get(1);
        EnrollmentView enrollment = enroll(KEY, self, child);

        // 今天（11 月 17 日）7 周岁，场次当天（12 月 1 日）已经 8 周岁。
        assertThat(listed.age()).isEqualTo(7);
        assertThat(listed.eligibility())
                .isEqualTo(new ParticipantEligibilityView(true, ParticipantEligibilityReason.ELIGIBLE, 8));
        assertThat(enrollment.tickets().get(1).age()).isEqualTo(8);
    }

    // “本人”不知道出生年月，按成年人处理：只有活动把年龄上限定在 18 岁以下时不能报。
    @Test
    void selfCountsAsAnAdult() {
        mapper.session.minAge = 8;
        mapper.session.maxAge = 15;
        assertThat(selfEligibility())
                .isEqualTo(new ParticipantEligibilityView(false, ParticipantEligibilityReason.OVER_MAX_AGE, null));

        mapper.session.maxAge = 17;
        assertThat(selfEligibility().reason()).isEqualTo(ParticipantEligibilityReason.OVER_MAX_AGE);

        mapper.session.maxAge = 18;
        assertThat(selfEligibility())
                .isEqualTo(new ParticipantEligibilityView(true, ParticipantEligibilityReason.ELIGIBLE, null));

        mapper.session.maxAge = null;
        mapper.session.minAge = 16;
        assertThat(selfEligibility().reason()).isEqualTo(ParticipantEligibilityReason.ELIGIBLE);
    }

    @Test
    void alreadyEnrolledWinsOverTheAgeRules() {
        UUID child = child("小明", "2018-05");
        enroll(KEY, self(), child);
        // 报完之后主办把年龄改成只收 12–15 岁：两个人都不再符合，但提示的是“已报名”。
        mapper.session.minAge = 12;
        mapper.session.maxAge = 15;

        List<ParticipantView> items = service.listParticipants(ACCOUNT, "王女士", SESSION_ID).items();

        assertThat(items)
                .extracting(item -> item.eligibility().eligible(), item -> item.eligibility().reason())
                .containsExactly(
                        tuple(false, ParticipantEligibilityReason.ALREADY_ENROLLED),
                        tuple(false, ParticipantEligibilityReason.ALREADY_ENROLLED));
        assertThat(items.get(1).eligibility().ageAtSession()).isEqualTo(8);
    }

    @Test
    void listWithASessionThatIsNotOnTheShelfIsNotFound() {
        assertRejected(Error.NOT_FOUND, () -> service.listParticipants(ACCOUNT, "王女士", UUID.randomUUID()));

        mapper.session.activityStatus = "DRAFT";
        assertRejected(Error.NOT_FOUND, () -> service.listParticipants(ACCOUNT, "王女士", SESSION_ID));

        mapper.session.activityStatus = "PUBLISHED";
        mapper.session.sessionStatus = "CANCELLED";
        assertRejected(Error.NOT_FOUND, () -> service.listParticipants(ACCOUNT, "王女士", SESSION_ID));
        // 只是读能不能报，不锁场次。
        assertThat(mapper.sessionLocks).isZero();
    }

    @Test
    void addChildRejectsAFutureMonthAnAdultAndAMissingGuardianConfirmation() {
        // 今天是北京时间 2026-11-17：本月出生的可以添加，下个月的不行。
        assertInvalid(() -> service.addChild(ACCOUNT, "未出生", "2026-12", true));
        // 2008-11 出生的已满 18 周岁，2008-12 的还差一个月。
        assertInvalid(() -> service.addChild(ACCOUNT, "已成年", "2008-11", true));
        assertInvalid(() -> service.addChild(ACCOUNT, "没勾确认", "2018-05", false));
        assertThat(mapper.participants).isEmpty();

        ParticipantView newborn = service.addChild(ACCOUNT, " 小宝 ", " 2026-11 ", true);
        ParticipantView almostAdult = service.addChild(ACCOUNT, "大宝", "2008-12", true);

        assertThat(newborn)
                .isEqualTo(new ParticipantView(
                        newborn.participantId(), ParticipantRelation.CHILD, "小宝", "2026-11", 0, false, null));
        assertThat(almostAdult.age()).isEqualTo(17);
        // 监护人确认的时间落库，出生年月存当月 1 日。
        assertThat(mapper.participants)
                .extracting(row -> row.birthMonth, row -> row.guardianConfirmedAt)
                .containsExactly(
                        tuple(LocalDate.parse("2026-11-01"), now()),
                        tuple(LocalDate.parse("2008-12-01"), now()));
    }

    // 北京时间已经是 12 月 1 日（UTC 还在 11 月 30 日）：“今天”也按北京的日期算。
    @Test
    void theMonthOfTodayFollowsTheBeijingDate() {
        ActivityEnrollmentService decemberFirst = at(Instant.parse("2026-11-30T16:30:00Z"));

        assertThat(decemberFirst.addChild(ACCOUNT, "小宝", "2026-12", true).age()).isZero();
        assertInvalid(() -> decemberFirst.addChild(ACCOUNT, "已成年", "2008-12", true));
    }

    @Test
    void addChildRejectsBadNamesAndBirthMonths() {
        assertInvalid(() -> service.addChild(ACCOUNT, null, "2018-05", true));
        assertInvalid(() -> service.addChild(ACCOUNT, "   ", "2018-05", true));
        assertInvalid(() -> service.addChild(ACCOUNT, "名".repeat(21), "2018-05", true));
        assertInvalid(() -> service.addChild(ACCOUNT, "小\t明", "2018-05", true));
        assertInvalid(() -> service.addChild(ACCOUNT, "小明", null, true));
        assertInvalid(() -> service.addChild(ACCOUNT, "小明", " ", true));
        assertInvalid(() -> service.addChild(ACCOUNT, "小明", "2018-13", true));
        assertInvalid(() -> service.addChild(ACCOUNT, "小明", "2018-05-20", true));
        assertInvalid(() -> service.addChild(null, "小明", "2018-05", true));
        assertThat(mapper.participants).isEmpty();

        assertThat(service.addChild(ACCOUNT, "名".repeat(20), "2018-05", true).name()).isEqualTo("名".repeat(20));
    }

    @Test
    void theEleventhChildIsRefusedAndADuplicateIsNotAddedTwice() {
        for (int index = 1; index <= 10; index++) {
            child("孩子" + index, "2018-05");
        }

        assertRejected(Error.PARTICIPANT_LIMIT, () -> service.addChild(ACCOUNT, "孩子11", "2018-05", true));
        // 上限按账号算，别的账号不受影响；同名同出生年月算重复，同名不同月不算。
        UUID first = service.addChild(OTHER_ACCOUNT, "小明", "2018-05", true).participantId();
        assertRejected(
                Error.PARTICIPANT_DUPLICATE, () -> service.addChild(OTHER_ACCOUNT, " 小明 ", "2018-05", true));
        UUID sameNameOtherMonth = service.addChild(OTHER_ACCOUNT, "小明", "2018-06", true).participantId();

        assertThat(sameNameOtherMonth).isNotEqualTo(first);
        assertThat(mapper.countChildren(ACCOUNT)).isEqualTo(10);
        assertThat(mapper.countChildren(OTHER_ACCOUNT)).isEqualTo(2);
    }

    @Test
    void renameChecksDuplicatesAmongTheAccountsChildren() {
        UUID self = self();
        UUID first = child("小明", "2018-05");
        UUID second = child("小红", "2018-05");
        UUID otherMonth = child("小刚", "2019-01");

        assertRejected(Error.PARTICIPANT_DUPLICATE, () -> service.renameParticipant(ACCOUNT, second, " 小明 "));
        assertThat(service.renameParticipant(ACCOUNT, otherMonth, "小明").name()).isEqualTo("小明");
        // 改成自己现在的名字不算重复；“本人”没有出生年月，不参与查重。
        assertThat(service.renameParticipant(ACCOUNT, first, "小明").participantId()).isEqualTo(first);
        assertThat(service.renameParticipant(ACCOUNT, self, " 小明 ").name()).isEqualTo("小明");
        assertThat(service.renameParticipant(ACCOUNT, second, "小红红"))
                .isEqualTo(new ParticipantView(second, ParticipantRelation.CHILD, "小红红", "2018-05", 8, false, null));

        assertInvalid(() -> service.renameParticipant(ACCOUNT, second, "  "));
        assertInvalid(() -> service.renameParticipant(ACCOUNT, second, "名".repeat(21)));
        assertInvalid(() -> service.renameParticipant(ACCOUNT, null, "小红"));
    }

    @Test
    void photoUseIsForChildrenOnlyAndCanBeWithdrawn() {
        UUID self = self();
        UUID child = child("小明", "2018-05");

        assertInvalid(() -> service.setPhotoUse(ACCOUNT, self, true));
        assertThat(service.setPhotoUse(ACCOUNT, child, true).photoUseAuthorized()).isTrue();
        assertThat(service.listParticipants(ACCOUNT, "王女士", null).items())
                .extracting(ParticipantView::photoUseAuthorized)
                .containsExactly(false, true);
        assertThat(service.setPhotoUse(ACCOUNT, child, false).photoUseAuthorized()).isFalse();
    }

    @Test
    void deleteRefusesSelfAndRemovesAChildFromTheList() {
        UUID self = self();
        UUID child = child("小明", "2018-05");
        service.setPhotoUse(ACCOUNT, child, true);

        assertInvalid(() -> service.deleteChild(ACCOUNT, self));
        service.deleteChild(ACCOUNT, child);

        assertThat(service.listParticipants(ACCOUNT, "王女士", null).items())
                .extracting(ParticipantView::participantId)
                .containsExactly(self);
        // 软删除，照片授权一并收回；删掉之后再操作就是不存在，名字也可以重新添加。
        assertThat(mapper.participant(child).status).isEqualTo("DELETED");
        assertThat(mapper.participant(child).photoUseAuthorized).isFalse();
        assertRejected(Error.NOT_FOUND, () -> service.deleteChild(ACCOUNT, child));
        assertRejected(Error.NOT_FOUND, () -> service.renameParticipant(ACCOUNT, child, "小明明"));
        assertThat(child("小明", "2018-05")).isNotEqualTo(child);
    }

    @Test
    void deletedChildKeepsTheSnapshotOnItsTicket() {
        UUID child = child("小明", "2018-05");
        EnrollmentView enrolled = enroll(KEY, child);

        service.renameParticipant(ACCOUNT, child, "小明明");
        service.deleteChild(ACCOUNT, child);

        assertThat(service.readEnrollment(ACCOUNT, enrolled.enrollmentId()).tickets())
                .extracting(TicketView::participantId, TicketView::participantName, TicketView::age)
                .containsExactly(tuple(child, "小明", 8));
    }

    @Test
    void participantsOfAnotherAccountAreNotFound() {
        UUID child = child("小明", "2018-05");
        UUID self = self();

        assertRejected(Error.NOT_FOUND, () -> service.renameParticipant(OTHER_ACCOUNT, child, "小红"));
        assertRejected(Error.NOT_FOUND, () -> service.setPhotoUse(OTHER_ACCOUNT, child, true));
        assertRejected(Error.NOT_FOUND, () -> service.deleteChild(OTHER_ACCOUNT, child));
        assertRejected(Error.NOT_FOUND, () -> service.renameParticipant(OTHER_ACCOUNT, self, "小红"));
        assertRejected(Error.NOT_FOUND, () -> service.renameParticipant(ACCOUNT, UUID.randomUUID(), "小红"));

        assertThat(service.listParticipants(OTHER_ACCOUNT, "李先生", null).items())
                .extracting(ParticipantView::name)
                .containsExactly("李先生");
        assertThat(mapper.participant(child).name).isEqualTo("小明");
        assertThat(mapper.participant(child).status).isEqualTo("ACTIVE");
    }

    @Test
    void everyAccountOperationNeedsAnAccount() {
        UUID any = UUID.randomUUID();

        assertInvalid(() -> service.listParticipants(null, "王女士", null));
        assertInvalid(() -> service.renameParticipant(null, any, "小明"));
        assertInvalid(() -> service.setPhotoUse(null, any, true));
        assertInvalid(() -> service.deleteChild(null, any));
        assertInvalid(() -> service.readEnrollment(null, any));
        assertInvalid(() -> service.listEnrollments(null, null, 1, 20));
        assertInvalid(() -> service.cancel(null, any, null));
        assertThat(mapper.participants).isEmpty();
    }

    // ---- 报名 ----

    @Test
    void enrollIssuesOneTicketPerParticipantWithASnapshotAndTakesTheSeats() {
        UUID self = self();
        UUID child = child("小明", "2018-05");

        // 提交的顺序不影响结果：凭证按参与人 ID 排好再生成。
        EnrollmentResult result = service.enroll(ACCOUNT, KEY, command(child, self));

        assertThat(result.created()).isTrue();
        EnrollmentView view = result.enrollment();
        assertThat(view.status()).isEqualTo(EnrollmentStatus.UPCOMING);
        assertThat(view.createdAt()).isEqualTo(now());
        assertThat(view.cancellable()).isTrue();
        assertThat(view.cancellableUntil()).isEqualTo(STARTS_AT);
        assertThat(view.activity())
                .isEqualTo(new EnrollmentActivityView(
                        ACTIVITY_ID,
                        ActivityType.OPEN_DAY,
                        TITLE,
                        new ActivityMediaView(COVER, TITLE),
                        "370500",
                        "东营低空与机器人体验馆"));
        assertThat(view.session())
                .isEqualTo(new EnrollmentSessionView(SESSION_ID, "上午场", STARTS_AT, ENDS_AT, null));
        assertThat(view.location())
                .isEqualTo(new ActivityLocationView("体验馆一层大厅", "东营市东营区示例路 1 号", 37.43, 118.67));
        assertThat(view.tickets())
                .extracting(
                        TicketView::participantId,
                        TicketView::participantName,
                        TicketView::relation,
                        TicketView::age,
                        TicketView::ticketCode,
                        TicketView::status,
                        TicketView::checkedInAt,
                        TicketView::cancelledAt)
                .containsExactly(
                        tuple(self, "王女士", ParticipantRelation.SELF, null,
                                "ACT-TEST-0000-0001", TicketStatus.ACTIVE, null, null),
                        tuple(child, "小明", ParticipantRelation.CHILD, 8,
                                "ACT-TEST-0000-0002", TicketStatus.ACTIVE, null, null));

        assertThat(mapper.session.enrolledCount).isEqualTo(50);
        assertThat(mapper.sessionLocks).isEqualTo(1);
        assertThat(mapper.enrollments)
                .extracting(row -> row.accountId, row -> row.idempotencyKey, row -> row.noticeAgreedAt)
                .containsExactly(tuple(ACCOUNT, KEY, now()));
        assertThat(mapper.enrollments.get(0).requestHash).matches("[0-9a-f]{64}");
        assertThat(mapper.events)
                .containsExactly(new InMemoryMapper.Event(view.enrollmentId(), null, "ENROLLED", ACCOUNT, now()));
    }

    // List.of 这类不可变列表的 contains(null) 会抛空指针；判空不能依赖它。
    @Test
    void immutableListsOfIdsAreAccepted() {
        UUID self = self();
        UUID child = child("小明", "2018-05");

        EnrollmentView enrolled = service
                .enroll(ACCOUNT, KEY, new EnrollCommand(ACTIVITY_ID, SESSION_ID, List.of(self, child), true))
                .enrollment();
        EnrollmentView cancelled = service.cancel(
                ACCOUNT, enrolled.enrollmentId(), List.of(enrolled.tickets().get(0).ticketId()));

        assertThat(enrolled.tickets()).hasSize(2);
        assertThat(cancelled.tickets().get(0).status()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(cancelled.tickets().get(1).status()).isEqualTo(TicketStatus.ACTIVE);
    }

    @Test
    void sameKeyReplaysTheFirstResultAndTakesNoExtraSeat() {
        UUID self = self();
        UUID child = child("小明", "2018-05");
        EnrollmentResult first = service.enroll(ACCOUNT, KEY, command(self, child));

        EnrollmentResult again = service.enroll(ACCOUNT, KEY, command(child, self));
        // 报名截止、名额报满之后重放，仍然是第一次的那张报名单。
        mapper.session.enrolledCount = mapper.session.capacity;
        EnrollmentResult late = at(CLOSES_AT.plusHours(1).toInstant()).enroll(ACCOUNT, KEY, command(self, child));

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.enrollment()).isEqualTo(first.enrollment());
        assertThat(late.created()).isFalse();
        assertThat(late.enrollment().enrollmentId()).isEqualTo(first.enrollment().enrollmentId());
        assertThat(mapper.enrollments).hasSize(1);
        assertThat(mapper.tickets).hasSize(2);
        assertThat(mapper.events).hasSize(1);
        assertThat(mapper.enrolledDeltas).containsExactly(2);
        assertThat(issuedCodes).isEqualTo(2);
    }

    @Test
    void sameKeyWithADifferentRequestIsAConflict() {
        UUID self = self();
        UUID child = child("小明", "2018-05");
        service.enroll(ACCOUNT, KEY, command(self));

        assertRejected(Error.IDEMPOTENCY_CONFLICT, () -> service.enroll(ACCOUNT, KEY, command(child)));
        assertRejected(Error.IDEMPOTENCY_CONFLICT, () -> service.enroll(ACCOUNT, KEY, command(self, child)));

        assertThat(mapper.enrollments).hasSize(1);
        assertThat(mapper.tickets).hasSize(1);
        assertThat(mapper.session.enrolledCount).isEqualTo(49);
    }

    // 幂等键按账号隔离：别的账号碰巧用了同一个键，是它自己的一张新报名单。
    @Test
    void sameKeyUnderAnotherAccountIsItsOwnEnrollment() {
        EnrollmentResult mine = service.enroll(ACCOUNT, KEY, command(self()));
        UUID otherSelf = service.listParticipants(OTHER_ACCOUNT, "李先生", null).items().get(0).participantId();

        EnrollmentResult theirs = service.enroll(OTHER_ACCOUNT, KEY, command(otherSelf));

        assertThat(theirs.created()).isTrue();
        assertThat(theirs.enrollment().enrollmentId()).isNotEqualTo(mine.enrollment().enrollmentId());
        assertThat(theirs.enrollment().tickets()).extracting(TicketView::participantName).containsExactly("李先生");
        assertThat(mapper.session.enrolledCount).isEqualTo(50);
    }

    @Test
    void anActivityThatDoesNotOwnTheSessionAndAnUnknownSessionAreNotFound() {
        UUID self = self();

        assertRejected(Error.NOT_FOUND, () -> service.enroll(
                ACCOUNT, KEY, new EnrollCommand(UUID.randomUUID(), SESSION_ID, ids(self), true)));
        assertRejected(Error.NOT_FOUND, () -> service.enroll(
                ACCOUNT, KEY, new EnrollCommand(ACTIVITY_ID, UUID.randomUUID(), ids(self), true)));

        assertNothingEnrolled();
    }

    @Test
    void anUnpublishedActivityOrACancelledSessionIsNotFound() {
        UUID self = self();

        mapper.session.activityStatus = "OFFLINE";
        assertRejected(Error.NOT_FOUND, () -> service.enroll(ACCOUNT, KEY, command(self)));

        mapper.session.activityStatus = "PUBLISHED";
        mapper.session.sessionStatus = "CANCELLED";
        assertRejected(Error.NOT_FOUND, () -> service.enroll(ACCOUNT, KEY, command(self)));

        assertNothingEnrolled();
    }

    @Test
    void onlyFreeActivitiesCanBeEnrolledHere() {
        UUID self = self();

        mapper.session.priceMode = "PER_PERSON";
        assertRejected(Error.ENROLLMENT_UNSUPPORTED, () -> service.enroll(ACCOUNT, KEY, command(self)));
        mapper.session.priceMode = "INQUIRY";
        assertRejected(Error.ENROLLMENT_UNSUPPORTED, () -> service.enroll(ACCOUNT, KEY, command(self)));

        assertNothingEnrolled();
    }

    @Test
    void enrollmentStopsAtTheDeadlineAndStaysClosedAfterTheEnd() {
        UUID self = self();

        assertRejected(Error.ENROLLMENT_NOT_OPEN,
                () -> at(CLOSES_AT.toInstant()).enroll(ACCOUNT, KEY, command(self)));
        assertRejected(Error.ENROLLMENT_NOT_OPEN,
                () -> at(STARTS_AT.plusMinutes(30).toInstant()).enroll(ACCOUNT, KEY, command(self)));
        assertRejected(Error.ENROLLMENT_NOT_OPEN,
                () -> at(ENDS_AT.plusDays(1).toInstant()).enroll(ACCOUNT, KEY, command(self)));
        assertNothingEnrolled();

        // 截止前最后一秒还能报。
        assertThat(at(CLOSES_AT.minusSeconds(1).toInstant()).enroll(ACCOUNT, KEY, command(self)).created())
                .isTrue();
    }

    @Test
    void notEnoughSeatsIsSessionFullAndTheLastSeatsCanStillBeTaken() {
        UUID self = self();
        UUID child = child("小明", "2018-05");

        mapper.session.enrolledCount = 59;
        assertRejected(Error.SESSION_FULL, () -> service.enroll(ACCOUNT, KEY, command(self, child)));
        mapper.session.enrolledCount = 60;
        assertRejected(Error.SESSION_FULL, () -> service.enroll(ACCOUNT, KEY, command(self)));
        // 允许候补的活动满员时也不能在这里报：候补不在这一步。
        mapper.session.waitlistAllowed = true;
        assertRejected(Error.SESSION_FULL, () -> service.enroll(ACCOUNT, KEY, command(self)));
        assertNothingEnrolled();

        mapper.session.enrolledCount = 58;
        assertThat(service.enroll(ACCOUNT, KEY, command(self, child)).created()).isTrue();
        assertThat(mapper.session.enrolledCount).isEqualTo(60);
    }

    @Test
    void someoneElsesParticipantIsNotFoundAndNothingIsWritten() {
        UUID self = self();
        UUID theirChild = service.addChild(OTHER_ACCOUNT, "小红", "2018-05", true).participantId();

        assertRejected(Error.NOT_FOUND, () -> service.enroll(ACCOUNT, KEY, command(self, theirChild)));
        assertRejected(Error.NOT_FOUND, () -> service.enroll(ACCOUNT, KEY, command(UUID.randomUUID())));

        assertNothingEnrolled();
    }

    @Test
    void anIneligibleParticipantStopsTheWholeEnrollment() {
        mapper.session.minAge = 8;
        mapper.session.maxAge = 15;
        UUID self = self();
        UUID eligible = child("小明", "2018-05");
        UUID tooYoung = child("小红", "2020-01");
        UUID tooOld = child("小刚", "2010-01");

        assertRejected(Error.PARTICIPANT_NOT_ELIGIBLE, () -> service.enroll(ACCOUNT, KEY, command(eligible, tooYoung)));
        assertRejected(Error.PARTICIPANT_NOT_ELIGIBLE, () -> service.enroll(ACCOUNT, KEY, command(tooOld)));
        assertRejected(Error.PARTICIPANT_NOT_ELIGIBLE, () -> service.enroll(ACCOUNT, KEY, command(self)));
        assertNothingEnrolled();

        assertThat(service.enroll(ACCOUNT, KEY, command(eligible)).created()).isTrue();
    }

    @Test
    void aParticipantCannotHoldTwoTicketsForOneSessionUntilTheFirstIsCancelled() {
        UUID self = self();
        UUID child = child("小明", "2018-05");
        EnrollmentView first = enroll(KEY, self);

        assertRejected(
                Error.PARTICIPANT_ALREADY_ENROLLED, () -> service.enroll(ACCOUNT, SECOND_KEY, command(self, child)));
        assertThat(mapper.enrollments).hasSize(1);
        assertThat(mapper.session.enrolledCount).isEqualTo(49);

        service.cancel(ACCOUNT, first.enrollmentId(), null);
        EnrollmentResult again = service.enroll(ACCOUNT, THIRD_KEY, command(self, child));

        assertThat(again.created()).isTrue();
        assertThat(again.enrollment().enrollmentId()).isNotEqualTo(first.enrollmentId());
        assertThat(mapper.session.enrolledCount).isEqualTo(50);
    }

    @Test
    void invalidEnrollmentInputIsRejectedBeforeAnythingIsLocked() {
        UUID self = self();
        List<UUID> eleven = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            eleven.add(UUID.randomUUID());
        }

        assertInvalid(() -> service.enroll(null, KEY, command(self)));
        assertInvalid(() -> service.enroll(ACCOUNT, null, command(self)));
        assertInvalid(() -> service.enroll(ACCOUNT, KEY, null));
        assertInvalid(() -> service.enroll(ACCOUNT, KEY, new EnrollCommand(null, SESSION_ID, ids(self), true)));
        assertInvalid(() -> service.enroll(ACCOUNT, KEY, new EnrollCommand(ACTIVITY_ID, null, ids(self), true)));
        assertInvalid(() -> service.enroll(ACCOUNT, KEY, new EnrollCommand(ACTIVITY_ID, SESSION_ID, null, true)));
        assertInvalid(() -> service.enroll(ACCOUNT, KEY, new EnrollCommand(ACTIVITY_ID, SESSION_ID, ids(), true)));
        assertInvalid(() -> service.enroll(ACCOUNT, KEY, new EnrollCommand(ACTIVITY_ID, SESSION_ID, eleven, true)));
        assertInvalid(() -> service.enroll(
                ACCOUNT, KEY, new EnrollCommand(ACTIVITY_ID, SESSION_ID, Arrays.asList(self, null), true)));
        assertInvalid(() -> service.enroll(
                ACCOUNT, KEY, new EnrollCommand(ACTIVITY_ID, SESSION_ID, ids(self, self), true)));
        // 没勾“已阅读并同意报名须知”。
        assertInvalid(() -> service.enroll(
                ACCOUNT, KEY, new EnrollCommand(ACTIVITY_ID, SESSION_ID, ids(self), false)));

        assertThat(mapper.sessionLocks).isZero();
        assertNothingEnrolled();
    }

    @Test
    void tenParticipantsFitInOneEnrollment() {
        List<UUID> ten = new ArrayList<>(List.of(self()));
        for (int index = 1; index <= 9; index++) {
            ten.add(child("孩子" + index, "2018-05"));
        }

        EnrollmentResult result = service.enroll(
                ACCOUNT, KEY, new EnrollCommand(ACTIVITY_ID, SESSION_ID, ten, true));

        assertThat(result.enrollment().tickets()).hasSize(10);
        assertThat(result.enrollment().tickets()).extracting(TicketView::ticketCode).doesNotHaveDuplicates();
        assertThat(mapper.session.enrolledCount).isEqualTo(58);
    }

    // ---- 读报名单 ----

    @Test
    void statusAndTicketsFollowTheSessionTimes() {
        UUID self = self();
        UUID child = child("小明", "2018-05");
        EnrollmentView enrolled = enroll(KEY, self, child);
        UUID id = enrolled.enrollmentId();
        String childCode = enrolled.tickets().get(1).ticketCode();
        at(STARTS_AT.minusMinutes(30).toInstant()).checkIn(OPERATOR, ORGANIZER, childCode);

        EnrollmentView lastSecondBeforeStart = at(STARTS_AT.minusSeconds(1).toInstant()).readEnrollment(ACCOUNT, id);
        EnrollmentView started = at(STARTS_AT.toInstant()).readEnrollment(ACCOUNT, id);
        EnrollmentView lastSecond = at(ENDS_AT.minusSeconds(1).toInstant()).readEnrollment(ACCOUNT, id);
        EnrollmentView ended = at(ENDS_AT.toInstant()).readEnrollment(ACCOUNT, id);

        assertThat(lastSecondBeforeStart.status()).isEqualTo(EnrollmentStatus.UPCOMING);
        assertThat(lastSecondBeforeStart.cancellable()).isTrue();
        // 开始之后、结束之前还算“待参加”，但已经不能取消。
        assertThat(started.status()).isEqualTo(EnrollmentStatus.UPCOMING);
        assertThat(started.cancellable()).isFalse();
        assertThat(lastSecond.status()).isEqualTo(EnrollmentStatus.UPCOMING);
        assertThat(lastSecond.tickets())
                .extracting(TicketView::status)
                .containsExactly(TicketStatus.ACTIVE, TicketStatus.CHECKED_IN);
        // 结束之后：没签到的凭证读成过期，签到过的不变；库里存的还是 ACTIVE。
        assertThat(ended.status()).isEqualTo(EnrollmentStatus.FINISHED);
        assertThat(ended.cancellable()).isFalse();
        assertThat(ended.tickets())
                .extracting(TicketView::status)
                .containsExactly(TicketStatus.EXPIRED, TicketStatus.CHECKED_IN);
        assertThat(ended.tickets().get(1).checkedInAt()).isEqualTo(STARTS_AT.minusMinutes(30));
        assertThat(mapper.tickets).extracting(row -> row.status).containsExactly("ACTIVE", "CHECKED_IN");
    }

    @Test
    void aSessionWithoutAnEndFinishesAtItsStart() {
        mapper.session.endsAt = null;
        UUID id = enroll(KEY, self()).enrollmentId();

        EnrollmentView before = at(STARTS_AT.minusSeconds(1).toInstant()).readEnrollment(ACCOUNT, id);
        EnrollmentView after = at(STARTS_AT.toInstant()).readEnrollment(ACCOUNT, id);

        assertThat(before.status()).isEqualTo(EnrollmentStatus.UPCOMING);
        assertThat(before.session().endsAt()).isNull();
        assertThat(after.status()).isEqualTo(EnrollmentStatus.FINISHED);
        assertThat(after.tickets()).extracting(TicketView::status).containsExactly(TicketStatus.EXPIRED);
    }

    // 签到在开始前就开放：只剩已签到的凭证时，虽然还没开始，也没有可取消的了。
    @Test
    void cancellableNeedsAnActiveTicketBeforeTheStart() {
        EnrollmentView enrolled = enroll(KEY, self());
        Instant halfAnHourBefore = STARTS_AT.minusMinutes(30).toInstant();

        assertThat(at(halfAnHourBefore).readEnrollment(ACCOUNT, enrolled.enrollmentId()).cancellable()).isTrue();
        at(halfAnHourBefore).checkIn(OPERATOR, ORGANIZER, enrolled.tickets().get(0).ticketCode());

        EnrollmentView checkedIn = at(halfAnHourBefore).readEnrollment(ACCOUNT, enrolled.enrollmentId());
        assertThat(checkedIn.status()).isEqualTo(EnrollmentStatus.UPCOMING);
        assertThat(checkedIn.cancellable()).isFalse();
    }

    @Test
    void cancelledEnrollmentReadsCancelledWhateverTheTime() {
        UUID id = enroll(KEY, self()).enrollmentId();
        service.cancel(ACCOUNT, id, null);

        assertThat(service.readEnrollment(ACCOUNT, id).status()).isEqualTo(EnrollmentStatus.CANCELLED);
        EnrollmentView afterTheEnd = at(ENDS_AT.plusDays(1).toInstant()).readEnrollment(ACCOUNT, id);
        assertThat(afterTheEnd.status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(afterTheEnd.cancellable()).isFalse();
        assertThat(afterTheEnd.tickets()).extracting(TicketView::status).containsExactly(TicketStatus.CANCELLED);
    }

    @Test
    void anUnsafeCoverAndABlankLabelAreLeftOut() {
        mapper.session.coverUrl = "http://insecure.example/cover.jpg";
        mapper.session.label = "   ";
        mapper.session.checkInOpensAt = STARTS_AT.minusMinutes(45);
        mapper.session.latitude = null;
        mapper.session.longitude = null;

        EnrollmentView view = enroll(KEY, self());

        assertThat(view.activity().coverImage()).isNull();
        assertThat(view.session().label()).isNull();
        assertThat(view.session().checkInOpensAt()).isEqualTo(STARTS_AT.minusMinutes(45));
        assertThat(view.location().latitude()).isNull();
        assertThat(view.location().longitude()).isNull();
    }

    @Test
    void anEnrollmentOfAnotherAccountIsNotFound() {
        UUID id = enroll(KEY, self()).enrollmentId();

        assertRejected(Error.NOT_FOUND, () -> service.readEnrollment(OTHER_ACCOUNT, id));
        assertRejected(Error.NOT_FOUND, () -> service.readEnrollment(ACCOUNT, UUID.randomUUID()));
        assertInvalid(() -> service.readEnrollment(ACCOUNT, null));
        assertThat(service.listEnrollments(OTHER_ACCOUNT, null, 1, 20).items()).isEmpty();
        assertThat(service.listEnrollments(OTHER_ACCOUNT, "CANCELLED", 1, 20).total()).isZero();
    }

    @Test
    void listPassesTheBucketAndPagingToTheMapperWithOneClockReading() {
        service.listEnrollments(ACCOUNT, " FINISHED ", 3, 10);
        service.listEnrollments(ACCOUNT, null, 1, 50);
        service.listEnrollments(ACCOUNT, "  ", 2, 1);

        assertThat(mapper.countCalls).containsExactly(
                new InMemoryMapper.CountCall(ACCOUNT, "FINISHED", now()),
                new InMemoryMapper.CountCall(ACCOUNT, "UPCOMING", now()),
                new InMemoryMapper.CountCall(ACCOUNT, "UPCOMING", now()));
        assertThat(mapper.pageCalls).containsExactly(
                new InMemoryMapper.PageCall(ACCOUNT, "FINISHED", now(), 10, 20),
                new InMemoryMapper.PageCall(ACCOUNT, "UPCOMING", now(), 50, 0),
                new InMemoryMapper.PageCall(ACCOUNT, "UPCOMING", now(), 1, 1));
        // 这一页没有报名单时不去查凭证（IN 后面不能是空列表）。
        assertThat(mapper.ticketQueries).isEmpty();
    }

    @Test
    void listReportsPagesAndWhetherThereIsMore() {
        enroll(KEY, self());
        enroll(SECOND_KEY, child("小明", "2018-05"));
        enroll(THIRD_KEY, child("小红", "2018-06"));

        EnrollmentPageView first = service.listEnrollments(ACCOUNT, "UPCOMING", 1, 2);
        EnrollmentPageView second = service.listEnrollments(ACCOUNT, "UPCOMING", 2, 2);

        assertThat(first.page()).isEqualTo(1);
        assertThat(first.size()).isEqualTo(2);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.items()).hasSize(2);
        assertThat(second.page()).isEqualTo(2);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.items()).hasSize(1);
        // 三张报名单各带自己的那一张凭证，没有串。
        List<EnrollmentView> all = new ArrayList<>(first.items());
        all.addAll(second.items());
        assertThat(all).extracting(EnrollmentView::enrollmentId).doesNotHaveDuplicates();
        assertThat(all).allSatisfy(item -> assertThat(item.tickets()).hasSize(1));
        assertThat(all)
                .extracting(item -> item.tickets().get(0).participantName())
                .containsExactlyInAnyOrder("王女士", "小明", "小红");
    }

    // 三个页签和报名单自己的状态说的是同一件事：落在哪个页签里，读出来就是哪个状态。
    @Test
    void theThreeBucketsAgreeWithTheStatusOfEachEnrollment() {
        UUID kept = enroll(KEY, self()).enrollmentId();
        UUID cancelled = enroll(SECOND_KEY, child("小明", "2018-05")).enrollmentId();
        service.cancel(ACCOUNT, cancelled, null);
        ActivityEnrollmentService afterTheEnd = at(ENDS_AT.toInstant());

        assertThat(service.listEnrollments(ACCOUNT, "UPCOMING", 1, 20).items())
                .extracting(EnrollmentView::enrollmentId, EnrollmentView::status)
                .containsExactly(tuple(kept, EnrollmentStatus.UPCOMING));
        assertThat(service.listEnrollments(ACCOUNT, "FINISHED", 1, 20).items()).isEmpty();
        assertThat(service.listEnrollments(ACCOUNT, "CANCELLED", 1, 20).items())
                .extracting(EnrollmentView::enrollmentId, EnrollmentView::status)
                .containsExactly(tuple(cancelled, EnrollmentStatus.CANCELLED));

        assertThat(afterTheEnd.listEnrollments(ACCOUNT, "UPCOMING", 1, 20).items()).isEmpty();
        EnrollmentPageView finished = afterTheEnd.listEnrollments(ACCOUNT, "FINISHED", 1, 20);
        assertThat(finished.total()).isEqualTo(1);
        assertThat(finished.items())
                .extracting(EnrollmentView::enrollmentId, EnrollmentView::status)
                .containsExactly(tuple(kept, EnrollmentStatus.FINISHED));
        assertThat(finished.items().get(0).tickets())
                .extracting(TicketView::status)
                .containsExactly(TicketStatus.EXPIRED);
        assertThat(afterTheEnd.listEnrollments(ACCOUNT, "CANCELLED", 1, 20).items())
                .extracting(EnrollmentView::enrollmentId)
                .containsExactly(cancelled);
    }

    @Test
    void listRejectsInvalidArguments() {
        assertInvalid(() -> service.listEnrollments(ACCOUNT, null, 0, 20));
        assertInvalid(() -> service.listEnrollments(ACCOUNT, null, 1, 0));
        assertInvalid(() -> service.listEnrollments(ACCOUNT, null, 1, 51));
        assertInvalid(() -> service.listEnrollments(ACCOUNT, "EXPIRED", 1, 20));
        assertInvalid(() -> service.listEnrollments(ACCOUNT, "upcoming", 1, 20));

        assertThat(mapper.countCalls).isEmpty();
        assertThat(mapper.pageCalls).isEmpty();
    }

    // ---- 取消 ----

    @Test
    void cancelWithoutTicketIdsCancelsEveryTicketAndReleasesExactlyThoseSeats() {
        EnrollmentView enrolled = enroll(KEY, self(), child("小明", "2018-05"));
        UUID id = enrolled.enrollmentId();
        mapper.sessionLocks = 0;

        EnrollmentView cancelled = service.cancel(ACCOUNT, id, null);

        assertThat(cancelled.status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(cancelled.cancellable()).isFalse();
        assertThat(cancelled.tickets())
                .extracting(TicketView::status, TicketView::cancelledAt)
                .containsExactly(tuple(TicketStatus.CANCELLED, now()), tuple(TicketStatus.CANCELLED, now()));
        assertThat(mapper.session.enrolledCount).isEqualTo(48);
        assertThat(mapper.enrolledDeltas).containsExactly(2, -2);
        assertThat(mapper.sessionLocks).isEqualTo(1);
        assertThat(mapper.enrollments.get(0).status).isEqualTo("CANCELLED");
        assertThat(mapper.enrollments.get(0).cancelledAt).isEqualTo(now());
        assertThat(mapper.events).containsExactly(
                new InMemoryMapper.Event(id, null, "ENROLLED", ACCOUNT, now()),
                new InMemoryMapper.Event(id, enrolled.tickets().get(0).ticketId(), "TICKET_CANCELLED", ACCOUNT, now()),
                new InMemoryMapper.Event(id, enrolled.tickets().get(1).ticketId(), "TICKET_CANCELLED", ACCOUNT, now()),
                new InMemoryMapper.Event(id, null, "ENROLLMENT_CANCELLED", ACCOUNT, now()));
    }

    @Test
    void anEmptyTicketListAlsoMeansAllOfThem() {
        UUID id = enroll(KEY, self(), child("小明", "2018-05")).enrollmentId();

        EnrollmentView cancelled = service.cancel(ACCOUNT, id, ids());

        assertThat(cancelled.status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(mapper.session.enrolledCount).isEqualTo(48);
    }

    @Test
    void cancellingSomeTicketsKeepsTheEnrollmentUntilTheLastOneGoes() {
        EnrollmentView enrolled = enroll(KEY, self(), child("小明", "2018-05"));
        UUID id = enrolled.enrollmentId();
        UUID selfTicket = enrolled.tickets().get(0).ticketId();
        UUID childTicket = enrolled.tickets().get(1).ticketId();

        EnrollmentView partly = service.cancel(ACCOUNT, id, ids(childTicket));

        assertThat(partly.status()).isEqualTo(EnrollmentStatus.UPCOMING);
        assertThat(partly.cancellable()).isTrue();
        assertThat(partly.tickets())
                .extracting(TicketView::ticketId, TicketView::status)
                .containsExactly(tuple(selfTicket, TicketStatus.ACTIVE), tuple(childTicket, TicketStatus.CANCELLED));
        assertThat(mapper.session.enrolledCount).isEqualTo(49);
        assertThat(mapper.enrollments.get(0).status).isEqualTo("CONFIRMED");
        assertThat(mapper.events).extracting(event -> event.eventType()).containsExactly("ENROLLED", "TICKET_CANCELLED");

        // 同一张凭证在列表里写了两遍也只取消一次。
        EnrollmentView fully = service.cancel(ACCOUNT, id, ids(selfTicket, selfTicket));

        assertThat(fully.status()).isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(mapper.session.enrolledCount).isEqualTo(48);
        assertThat(mapper.enrolledDeltas).containsExactly(2, -1, -1);
        assertThat(mapper.events)
                .extracting(event -> event.eventType(), event -> event.ticketId())
                .containsExactly(
                        tuple("ENROLLED", null),
                        tuple("TICKET_CANCELLED", childTicket),
                        tuple("TICKET_CANCELLED", selfTicket),
                        tuple("ENROLLMENT_CANCELLED", null));
    }

    @Test
    void repeatingACancelChangesNothing() {
        EnrollmentView enrolled = enroll(KEY, self(), child("小明", "2018-05"));
        UUID id = enrolled.enrollmentId();
        UUID childTicket = enrolled.tickets().get(1).ticketId();
        EnrollmentView partly = service.cancel(ACCOUNT, id, ids(childTicket));

        assertThat(service.cancel(ACCOUNT, id, ids(childTicket))).isEqualTo(partly);
        assertThat(mapper.session.enrolledCount).isEqualTo(49);
        assertThat(mapper.events).hasSize(2);

        EnrollmentView cancelled = service.cancel(ACCOUNT, id, null);
        List<InMemoryMapper.Event> events = List.copyOf(mapper.events);

        assertThat(service.cancel(ACCOUNT, id, null)).isEqualTo(cancelled);
        assertThat(service.cancel(ACCOUNT, id, ids(childTicket))).isEqualTo(cancelled);
        // 场次开始之后重复同一个取消，也只是把已取消的结果再给一次，不报错。
        assertThat(at(STARTS_AT.plusMinutes(5).toInstant()).cancel(ACCOUNT, id, null).status())
                .isEqualTo(EnrollmentStatus.CANCELLED);
        assertThat(mapper.session.enrolledCount).isEqualTo(48);
        assertThat(mapper.enrolledDeltas).containsExactly(2, -1, -1);
        assertThat(mapper.events).isEqualTo(events);
    }

    @Test
    void cancelIsRefusedOnceTheSessionHasStarted() {
        EnrollmentView enrolled = enroll(KEY, self(), child("小明", "2018-05"));
        UUID id = enrolled.enrollmentId();
        UUID childTicket = enrolled.tickets().get(1).ticketId();

        assertRejected(Error.CANCEL_NOT_ALLOWED, () -> at(STARTS_AT.toInstant()).cancel(ACCOUNT, id, null));
        assertRejected(
                Error.CANCEL_NOT_ALLOWED,
                () -> at(STARTS_AT.plusMinutes(10).toInstant()).cancel(ACCOUNT, id, ids(childTicket)));
        assertRejected(Error.CANCEL_NOT_ALLOWED, () -> at(ENDS_AT.plusDays(1).toInstant()).cancel(ACCOUNT, id, null));
        assertThat(mapper.session.enrolledCount).isEqualTo(50);
        assertThat(mapper.tickets).extracting(row -> row.status).containsExactly("ACTIVE", "ACTIVE");
        assertThat(mapper.events).hasSize(1);

        // 开始前最后一秒还可以。
        assertThat(at(STARTS_AT.minusSeconds(1).toInstant()).cancel(ACCOUNT, id, null).status())
                .isEqualTo(EnrollmentStatus.CANCELLED);
    }

    @Test
    void aCheckedInTicketCannotBeCancelledAndCancellingAllLeavesIt() {
        EnrollmentView enrolled = enroll(KEY, self(), child("小明", "2018-05"));
        UUID id = enrolled.enrollmentId();
        UUID selfTicket = enrolled.tickets().get(0).ticketId();
        UUID childTicket = enrolled.tickets().get(1).ticketId();
        ActivityEnrollmentService beforeStart = at(STARTS_AT.minusMinutes(30).toInstant());
        beforeStart.checkIn(OPERATOR, ORGANIZER, enrolled.tickets().get(1).ticketCode());

        assertRejected(Error.CANCEL_NOT_ALLOWED, () -> beforeStart.cancel(ACCOUNT, id, ids(childTicket)));
        // 点名的几张里有一张已签到：整个请求不做，另一张也不动。
        assertRejected(
                Error.CANCEL_NOT_ALLOWED, () -> beforeStart.cancel(ACCOUNT, id, ids(selfTicket, childTicket)));
        assertThat(mapper.ticket(selfTicket).status).isEqualTo("ACTIVE");
        assertThat(mapper.session.enrolledCount).isEqualTo(50);

        // 不点名时只取消还没用的；已签到的留着，报名单也就不算取消。
        EnrollmentView rest = beforeStart.cancel(ACCOUNT, id, null);

        assertThat(rest.status()).isEqualTo(EnrollmentStatus.UPCOMING);
        assertThat(rest.cancellable()).isFalse();
        assertThat(rest.tickets())
                .extracting(TicketView::status)
                .containsExactly(TicketStatus.CANCELLED, TicketStatus.CHECKED_IN);
        assertThat(mapper.session.enrolledCount).isEqualTo(49);
        assertThat(mapper.enrollments.get(0).status).isEqualTo("CONFIRMED");
        assertThat(mapper.events)
                .extracting(event -> event.eventType())
                .containsExactly("ENROLLED", "TICKET_CHECKED_IN", "TICKET_CANCELLED");
    }

    @Test
    void cancelRejectsTicketsThatDoNotBelongToTheEnrollment() {
        EnrollmentView mine = enroll(KEY, self());
        EnrollmentView other = enroll(SECOND_KEY, child("小明", "2018-05"));
        UUID otherTicket = other.tickets().get(0).ticketId();
        UUID myTicket = mine.tickets().get(0).ticketId();

        assertInvalid(() -> service.cancel(ACCOUNT, mine.enrollmentId(), ids(otherTicket)));
        assertInvalid(() -> service.cancel(ACCOUNT, mine.enrollmentId(), ids(myTicket, otherTicket)));
        assertInvalid(() -> service.cancel(ACCOUNT, mine.enrollmentId(), ids(UUID.randomUUID())));
        assertInvalid(() -> service.cancel(ACCOUNT, mine.enrollmentId(), Arrays.asList(myTicket, null)));
        assertInvalid(() -> service.cancel(ACCOUNT, null, null));

        assertThat(mapper.tickets).extracting(row -> row.status).containsExactly("ACTIVE", "ACTIVE");
        assertThat(mapper.session.enrolledCount).isEqualTo(50);
        assertThat(mapper.events).hasSize(2);
    }

    @Test
    void cancellingAnEnrollmentOfAnotherAccountIsNotFound() {
        UUID id = enroll(KEY, self()).enrollmentId();
        mapper.sessionLocks = 0;

        assertRejected(Error.NOT_FOUND, () -> service.cancel(OTHER_ACCOUNT, id, null));
        assertRejected(Error.NOT_FOUND, () -> service.cancel(ACCOUNT, UUID.randomUUID(), null));

        assertThat(mapper.sessionLocks).isZero();
        assertThat(mapper.tickets).extracting(row -> row.status).containsExactly("ACTIVE");
        assertThat(mapper.session.enrolledCount).isEqualTo(49);
    }

    // ---- 核销 ----

    @Test
    void checkInNeedsAnOperatorThatBelongsToAnOrganization() {
        String code = enroll(KEY, self()).tickets().get(0).ticketCode();
        ActivityEnrollmentService atTheDoor = at(STARTS_AT.minusMinutes(30).toInstant());

        assertRejected(Error.FORBIDDEN, () -> atTheDoor.checkIn(OPERATOR, null, code));
        assertRejected(Error.FORBIDDEN, () -> atTheDoor.checkIn(null, ORGANIZER, code));

        assertThat(mapper.ticketLocks).isZero();
        assertThat(mapper.tickets.get(0).status).isEqualTo("ACTIVE");
    }

    @Test
    void aMalformedCodeIsRejectedWithoutLookingItUp() {
        ActivityEnrollmentService atTheDoor = at(STARTS_AT.minusMinutes(30).toInstant());

        assertInvalid(() -> atTheDoor.checkIn(OPERATOR, ORGANIZER, null));
        assertInvalid(() -> atTheDoor.checkIn(OPERATOR, ORGANIZER, "  "));
        assertInvalid(() -> atTheDoor.checkIn(OPERATOR, ORGANIZER, "ACT-TEST-0000"));
        assertInvalid(() -> atTheDoor.checkIn(OPERATOR, ORGANIZER, "ACT-TEST-0000-00011"));
        assertInvalid(() -> atTheDoor.checkIn(OPERATOR, ORGANIZER, "XYZ-TEST-0000-0001"));
        assertInvalid(() -> atTheDoor.checkIn(OPERATOR, ORGANIZER, "ACT-TEST-0000-000!"));
        assertInvalid(() -> atTheDoor.checkIn(OPERATOR, ORGANIZER, "ACT TEST 0000 0001"));

        assertThat(mapper.ticketLocks).isZero();
    }

    @Test
    void aLowerCaseCodeWithSpacesAroundItIsAccepted() {
        String code = enroll(KEY, self()).tickets().get(0).ticketCode();

        CheckinView view = at(STARTS_AT.minusMinutes(30).toInstant())
                .checkIn(OPERATOR, ORGANIZER, "  " + code.toLowerCase() + " ");

        assertThat(view.ticketCode()).isEqualTo(code);
        assertThat(view.repeated()).isFalse();
        assertThat(mapper.tickets.get(0).status).isEqualTo("CHECKED_IN");
    }

    @Test
    void onlyTheOrganizerAndTheCoOrganizerFindTheTicket() {
        EnrollmentView enrolled = enroll(KEY, self(), child("小明", "2018-05"));
        String selfCode = enrolled.tickets().get(0).ticketCode();
        String childCode = enrolled.tickets().get(1).ticketCode();
        ActivityEnrollmentService atTheDoor = at(STARTS_AT.minusMinutes(30).toInstant());

        // 别的机构拿到凭证号也查不到，回答和“没有这张凭证”一样。
        assertRejected(Error.NOT_FOUND, () -> atTheDoor.checkIn(OPERATOR, OTHER_ORGANIZATION, selfCode));
        assertRejected(Error.NOT_FOUND, () -> atTheDoor.checkIn(OPERATOR, ORGANIZER, "ACT-TEST-0000-9999"));
        assertThat(mapper.tickets).extracting(row -> row.status).containsExactly("ACTIVE", "ACTIVE");

        assertThat(atTheDoor.checkIn(OPERATOR, ORGANIZER, selfCode).participantName()).isEqualTo("王女士");
        assertThat(atTheDoor.checkIn(OPERATOR, CO_ORGANIZER, childCode).participantName()).isEqualTo("小明");
    }

    @Test
    void aCancelledTicketIsNotValid() {
        EnrollmentView enrolled = enroll(KEY, self());
        service.cancel(ACCOUNT, enrolled.enrollmentId(), null);
        int events = mapper.events.size();

        assertRejected(
                Error.TICKET_NOT_VALID,
                () -> at(STARTS_AT.minusMinutes(30).toInstant())
                        .checkIn(OPERATOR, ORGANIZER, enrolled.tickets().get(0).ticketCode()));

        assertThat(mapper.tickets.get(0).status).isEqualTo("CANCELLED");
        assertThat(mapper.events).hasSize(events);
    }

    // 没设置签到开放时间：开始前 1 小时起可以核销，到场次结束为止。
    @Test
    void theWindowDefaultsToOneHourBeforeTheStartAndClosesAtTheEnd() {
        EnrollmentView enrolled = enroll(KEY, self(), child("小明", "2018-05"));
        String selfCode = enrolled.tickets().get(0).ticketCode();
        String childCode = enrolled.tickets().get(1).ticketCode();

        assertRejected(Error.CHECKIN_NOT_OPEN, () -> service.checkIn(OPERATOR, ORGANIZER, selfCode));
        assertRejected(
                Error.CHECKIN_NOT_OPEN,
                () -> at(STARTS_AT.minusHours(1).minusSeconds(1).toInstant()).checkIn(OPERATOR, ORGANIZER, selfCode));
        assertRejected(
                Error.CHECKIN_NOT_OPEN, () -> at(ENDS_AT.toInstant()).checkIn(OPERATOR, ORGANIZER, selfCode));
        assertRejected(
                Error.CHECKIN_NOT_OPEN,
                () -> at(ENDS_AT.plusHours(2).toInstant()).checkIn(OPERATOR, ORGANIZER, selfCode));
        assertThat(mapper.tickets).extracting(row -> row.status).containsExactly("ACTIVE", "ACTIVE");
        assertThat(mapper.events).hasSize(1);

        assertThat(at(STARTS_AT.minusHours(1).toInstant()).checkIn(OPERATOR, ORGANIZER, selfCode).checkedInAt())
                .isEqualTo(STARTS_AT.minusHours(1));
        assertThat(at(ENDS_AT.minusSeconds(1).toInstant()).checkIn(OPERATOR, ORGANIZER, childCode).checkedInAt())
                .isEqualTo(ENDS_AT.minusSeconds(1));
    }

    @Test
    void anExplicitOpeningTimeReplacesTheDefault() {
        mapper.session.checkInOpensAt = STARTS_AT.minusMinutes(30);
        EnrollmentView enrolled = enroll(KEY, self(), child("小明", "2018-05"));
        String selfCode = enrolled.tickets().get(0).ticketCode();
        String childCode = enrolled.tickets().get(1).ticketCode();

        assertRejected(
                Error.CHECKIN_NOT_OPEN,
                () -> at(STARTS_AT.minusMinutes(45).toInstant()).checkIn(OPERATOR, ORGANIZER, selfCode));
        assertThat(at(STARTS_AT.minusMinutes(30).toInstant()).checkIn(OPERATOR, ORGANIZER, selfCode).repeated())
                .isFalse();

        // 开放时间也可以设得比默认更早。
        mapper.session.checkInOpensAt = STARTS_AT.minusHours(3);
        assertThat(at(STARTS_AT.minusHours(2).toInstant()).checkIn(OPERATOR, ORGANIZER, childCode).repeated())
                .isFalse();
    }

    @Test
    void checkInRecordsTheOperatorAndAnEvent() {
        mapper.session.label = " 上午场 ";
        EnrollmentView enrolled = enroll(KEY, self());
        TicketView ticket = enrolled.tickets().get(0);
        OffsetDateTime atTheDoor = STARTS_AT.minusMinutes(20);

        CheckinView view = at(atTheDoor.toInstant()).checkIn(OPERATOR, ORGANIZER, ticket.ticketCode());

        assertThat(view).isEqualTo(new CheckinView(
                ticket.ticketCode(), "王女士", TITLE, "上午场", STARTS_AT, atTheDoor, false));
        InMemoryMapper.Ticket stored = mapper.ticket(ticket.ticketId());
        assertThat(stored.status).isEqualTo("CHECKED_IN");
        assertThat(stored.checkedInAt).isEqualTo(atTheDoor);
        assertThat(stored.checkedInBy).isEqualTo(OPERATOR);
        assertThat(mapper.ticketLocks).isEqualTo(1);
        // 核销不动名额。
        assertThat(mapper.session.enrolledCount).isEqualTo(49);
        assertThat(mapper.events).containsExactly(
                new InMemoryMapper.Event(enrolled.enrollmentId(), null, "ENROLLED", ACCOUNT, now()),
                new InMemoryMapper.Event(
                        enrolled.enrollmentId(), ticket.ticketId(), "TICKET_CHECKED_IN", OPERATOR, atTheDoor));
    }

    @Test
    void aSecondCheckInReturnsTheFirstTimeAndChangesNothing() {
        mapper.session.label = "  ";
        EnrollmentView enrolled = enroll(KEY, self());
        TicketView ticket = enrolled.tickets().get(0);
        OffsetDateTime firstTime = STARTS_AT.minusMinutes(20);
        UUID anotherOperator = UUID.randomUUID();
        at(firstTime.toInstant()).checkIn(OPERATOR, ORGANIZER, ticket.ticketCode());

        CheckinView again = at(STARTS_AT.plusMinutes(5).toInstant())
                .checkIn(anotherOperator, CO_ORGANIZER, ticket.ticketCode());
        // 场次结束之后再扫，也还是第一次的结果。
        CheckinView afterTheEnd = at(ENDS_AT.plusHours(1).toInstant())
                .checkIn(OPERATOR, ORGANIZER, ticket.ticketCode());

        assertThat(again).isEqualTo(new CheckinView(
                ticket.ticketCode(), "王女士", TITLE, null, STARTS_AT, firstTime, true));
        assertThat(afterTheEnd).isEqualTo(again);
        InMemoryMapper.Ticket stored = mapper.ticket(ticket.ticketId());
        assertThat(stored.checkedInAt).isEqualTo(firstTime);
        assertThat(stored.checkedInBy).isEqualTo(OPERATOR);
        assertThat(mapper.events).extracting(event -> event.eventType()).containsExactly("ENROLLED", "TICKET_CHECKED_IN");
    }

    // ---- 辅助 ----

    private ActivityEnrollmentService at(Instant instant) {
        return new ActivityEnrollmentService(
                mapper,
                Clock.fixed(instant, ZoneOffset.UTC),
                () -> String.format("ACT-TEST-0000-%04d", ++issuedCodes));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }

    private UUID self() {
        return service.listParticipants(ACCOUNT, "王女士", null).items().get(0).participantId();
    }

    private UUID child(String name, String birthMonth) {
        return service.addChild(ACCOUNT, name, birthMonth, true).participantId();
    }

    private ParticipantEligibilityView selfEligibility() {
        return service.listParticipants(ACCOUNT, "王女士", SESSION_ID).items().get(0).eligibility();
    }

    private static EnrollCommand command(UUID... participantIds) {
        return new EnrollCommand(ACTIVITY_ID, SESSION_ID, ids(participantIds), true);
    }

    // 和控制器一样交给服务一个普通的可变列表（Jackson 反序列化出来的是 ArrayList）。
    private static List<UUID> ids(UUID... ids) {
        return new ArrayList<>(Arrays.asList(ids));
    }

    private EnrollmentView enroll(UUID key, UUID... participantIds) {
        return service.enroll(ACCOUNT, key, command(participantIds)).enrollment();
    }

    private void assertNothingEnrolled() {
        assertThat(mapper.enrollments).isEmpty();
        assertThat(mapper.tickets).isEmpty();
        assertThat(mapper.events).isEmpty();
        assertThat(mapper.enrolledDeltas).isEmpty();
    }

    private static void assertRejected(Error error, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(
                ActivityEnrollmentException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 内存里的 mapper：每个方法按对应 SQL 的条件写，V002 的唯一索引和 V001 的名额约束在这里用异常代替，
     * 服务一旦写出数据库不会接受的数据，测试就会失败。只有一个活动、一个场次。
     */
    private static final class InMemoryMapper implements ActivityEnrollmentMapper {

        private static final Set<String> EVENT_TYPES =
                Set.of("ENROLLED", "TICKET_CANCELLED", "ENROLLMENT_CANCELLED", "TICKET_CHECKED_IN");

        final Session session = new Session();
        final List<Participant> participants = new ArrayList<>();
        final List<Enrollment> enrollments = new ArrayList<>();
        final List<Ticket> tickets = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        final List<Integer> enrolledDeltas = new ArrayList<>();
        final List<CountCall> countCalls = new ArrayList<>();
        final List<PageCall> pageCalls = new ArrayList<>();
        final List<List<UUID>> ticketQueries = new ArrayList<>();
        int sessionLocks;
        int ticketLocks;
        private long sequence;

        Participant participant(UUID id) {
            return participants.stream().filter(row -> row.id.equals(id)).findFirst().orElseThrow();
        }

        Ticket ticket(UUID id) {
            return tickets.stream().filter(row -> row.id.equals(id)).findFirst().orElseThrow();
        }

        // 按生成顺序递增的 ID：排序结果和创建顺序一致，断言才稳定。
        private UUID nextId() {
            return new UUID(0x4000L, ++sequence);
        }

        // ---- 参与人 ----

        @Override
        public int ensureSelf(UUID ownerId, String name) {
            boolean exists = participants.stream().anyMatch(row ->
                    row.ownerId.equals(ownerId) && row.relation.equals("SELF") && row.status.equals("ACTIVE"));
            if (exists) {
                return 0;
            }
            participants.add(new Participant(nextId(), ownerId, "SELF", name, null, null));
            return 1;
        }

        @Override
        public List<ParticipantRow> findParticipants(UUID ownerId) {
            return participants.stream()
                    .filter(row -> row.ownerId.equals(ownerId) && row.status.equals("ACTIVE"))
                    .sorted(Comparator.comparing((Participant row) -> !row.relation.equals("SELF")))
                    .map(Participant::toRow)
                    .toList();
        }

        @Override
        public ParticipantRow findParticipant(UUID ownerId, UUID participantId) {
            return participants.stream()
                    .filter(row -> row.ownerId.equals(ownerId) && row.status.equals("ACTIVE"))
                    .filter(row -> row.id.equals(participantId))
                    .map(Participant::toRow)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int countChildren(UUID ownerId) {
            return (int) activeChildren(ownerId).size();
        }

        @Override
        public int countSameChild(UUID ownerId, String name, LocalDate birthMonth, UUID exceptId) {
            return (int) activeChildren(ownerId).stream()
                    .filter(row -> row.name.equals(name) && row.birthMonth.equals(birthMonth))
                    .filter(row -> !row.id.equals(exceptId))
                    .count();
        }

        @Override
        public UUID insertChild(UUID ownerId, String name, LocalDate birthMonth, OffsetDateTime at) {
            boolean duplicate = activeChildren(ownerId).stream()
                    .anyMatch(row -> row.name.equals(name) && row.birthMonth.equals(birthMonth));
            if (duplicate) {
                return null;
            }
            if (birthMonth.getDayOfMonth() != 1 || at == null) {
                throw new IllegalStateException("activity_participant check constraint");
            }
            Participant row = new Participant(nextId(), ownerId, "CHILD", name, birthMonth, at);
            participants.add(row);
            return row.id;
        }

        @Override
        public int renameParticipant(UUID ownerId, UUID participantId, String name, OffsetDateTime at) {
            Participant row = owned(ownerId, participantId);
            if (row == null) {
                return 0;
            }
            boolean duplicate = row.relation.equals("CHILD") && activeChildren(ownerId).stream()
                    .anyMatch(other -> other != row
                            && other.name.equals(name)
                            && other.birthMonth.equals(row.birthMonth));
            if (duplicate) {
                throw new IllegalStateException("uq_activity_participant_child");
            }
            row.name = name;
            return 1;
        }

        @Override
        public int setPhotoUse(UUID ownerId, UUID participantId, boolean authorized, OffsetDateTime at) {
            Participant row = owned(ownerId, participantId);
            if (row == null || !row.relation.equals("CHILD")) {
                return 0;
            }
            row.photoUseAuthorized = authorized;
            return 1;
        }

        @Override
        public int deleteChild(UUID ownerId, UUID participantId, OffsetDateTime at) {
            Participant row = owned(ownerId, participantId);
            if (row == null || !row.relation.equals("CHILD")) {
                return 0;
            }
            row.status = "DELETED";
            row.photoUseAuthorized = false;
            return 1;
        }

        private List<Participant> activeChildren(UUID ownerId) {
            return participants.stream()
                    .filter(row -> row.ownerId.equals(ownerId))
                    .filter(row -> row.relation.equals("CHILD") && row.status.equals("ACTIVE"))
                    .toList();
        }

        private Participant owned(UUID ownerId, UUID participantId) {
            return participants.stream()
                    .filter(row -> row.ownerId.equals(ownerId) && row.status.equals("ACTIVE"))
                    .filter(row -> row.id.equals(participantId))
                    .findFirst()
                    .orElse(null);
        }

        // ---- 场次 ----

        @Override
        public SessionRow lockSession(UUID sessionId) {
            sessionLocks++;
            return findSession(sessionId);
        }

        @Override
        public SessionRow findSession(UUID sessionId) {
            if (!SESSION_ID.equals(sessionId)) {
                return null;
            }
            return new SessionRow(
                    SESSION_ID,
                    ACTIVITY_ID,
                    session.activityStatus,
                    session.priceMode,
                    session.minAge,
                    session.maxAge,
                    session.waitlistAllowed,
                    session.sessionStatus,
                    session.startsAt,
                    session.endsAt,
                    CLOSES_AT,
                    session.capacity,
                    session.enrolledCount);
        }

        @Override
        public int addEnrolled(UUID sessionId, int delta, OffsetDateTime at) {
            if (!SESSION_ID.equals(sessionId)) {
                return 0;
            }
            int next = session.enrolledCount + delta;
            if (next < 0 || next > session.capacity) {
                throw new IllegalStateException("ck_activity_session_seats");
            }
            session.enrolledCount = next;
            enrolledDeltas.add(delta);
            return 1;
        }

        // ---- 报名单 ----

        @Override
        public EnrollmentKeyRow findEnrollmentByKey(UUID accountId, UUID idempotencyKey) {
            return enrollments.stream()
                    .filter(row -> row.accountId.equals(accountId) && row.idempotencyKey.equals(idempotencyKey))
                    .map(row -> new EnrollmentKeyRow(row.id, row.requestHash))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<UUID> findEnrolledParticipantIds(UUID sessionId, UUID ownerId) {
            return tickets.stream()
                    .filter(row -> row.sessionId.equals(sessionId) && !row.status.equals("CANCELLED"))
                    .filter(row -> participant(row.participantId).ownerId.equals(ownerId))
                    .map(row -> row.participantId)
                    .toList();
        }

        @Override
        public UUID insertEnrollment(
                UUID accountId,
                UUID activityId,
                UUID sessionId,
                UUID idempotencyKey,
                String requestHash,
                OffsetDateTime at
        ) {
            if (findEnrollmentByKey(accountId, idempotencyKey) != null) {
                throw new IllegalStateException("UNIQUE (account_id, idempotency_key)");
            }
            if (requestHash.length() != 64 || at == null) {
                throw new IllegalStateException("activity_enrollment column constraint");
            }
            Enrollment row = new Enrollment(nextId(), accountId, activityId, sessionId, idempotencyKey, requestHash, at);
            enrollments.add(row);
            return row.id;
        }

        @Override
        public UUID insertTicket(
                UUID enrollmentId,
                UUID sessionId,
                UUID participantId,
                String name,
                String relation,
                Integer age,
                String ticketCode,
                OffsetDateTime at
        ) {
            boolean sameSeat = tickets.stream().anyMatch(row -> row.sessionId.equals(sessionId)
                    && row.participantId.equals(participantId)
                    && !row.status.equals("CANCELLED"));
            if (sameSeat) {
                throw new IllegalStateException("uq_activity_ticket_session_participant");
            }
            if (tickets.stream().anyMatch(row -> row.ticketCode.equals(ticketCode))) {
                throw new IllegalStateException("ticket_code UNIQUE");
            }
            Ticket row = new Ticket(nextId(), enrollmentId, sessionId, participantId, name, relation, age, ticketCode);
            tickets.add(row);
            return row.id;
        }

        @Override
        public int insertEvent(UUID enrollmentId, UUID ticketId, String eventType, UUID actorId, OffsetDateTime at) {
            if (!EVENT_TYPES.contains(eventType) || enrollmentId == null || actorId == null || at == null) {
                throw new IllegalStateException("activity_enrollment_event constraint");
            }
            events.add(new Event(enrollmentId, ticketId, eventType, actorId, at));
            return 1;
        }

        @Override
        public EnrollmentRow findEnrollment(UUID accountId, UUID enrollmentId) {
            return enrollments.stream()
                    .filter(row -> row.accountId.equals(accountId) && row.id.equals(enrollmentId))
                    .map(this::toRow)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public long countEnrollments(UUID accountId, String bucket, OffsetDateTime now) {
            countCalls.add(new CountCall(accountId, bucket, now));
            return inBucket(accountId, bucket, now).size();
        }

        @Override
        public List<EnrollmentRow> findEnrollmentPage(
                UUID accountId,
                String bucket,
                OffsetDateTime now,
                int limit,
                int offset
        ) {
            pageCalls.add(new PageCall(accountId, bucket, now, limit, offset));
            return inBucket(accountId, bucket, now).stream()
                    .skip(offset)
                    .limit(limit)
                    .map(this::toRow)
                    .toList();
        }

        private List<Enrollment> inBucket(UUID accountId, String bucket, OffsetDateTime now) {
            OffsetDateTime end = session.endsAt == null ? session.startsAt : session.endsAt;
            return enrollments.stream()
                    .filter(row -> row.accountId.equals(accountId))
                    .filter(row -> switch (bucket) {
                        case "CANCELLED" -> row.status.equals("CANCELLED");
                        case "UPCOMING" -> row.status.equals("CONFIRMED") && end.isAfter(now);
                        case "FINISHED" -> row.status.equals("CONFIRMED") && !end.isAfter(now);
                        default -> false;
                    })
                    .toList();
        }

        private EnrollmentRow toRow(Enrollment row) {
            return new EnrollmentRow(
                    row.id,
                    row.status,
                    row.createdAt,
                    row.activityId,
                    "OPEN_DAY",
                    TITLE,
                    session.coverUrl,
                    "370500",
                    "东营低空与机器人体验馆",
                    "体验馆一层大厅",
                    "东营市东营区示例路 1 号",
                    session.latitude,
                    session.longitude,
                    row.sessionId,
                    session.label,
                    session.startsAt,
                    session.endsAt,
                    session.checkInOpensAt);
        }

        @Override
        public List<TicketRow> findTickets(List<UUID> enrollmentIds) {
            if (enrollmentIds.isEmpty()) {
                throw new IllegalStateException("IN () is not valid SQL");
            }
            ticketQueries.add(List.copyOf(enrollmentIds));
            return tickets.stream()
                    .filter(row -> enrollmentIds.contains(row.enrollmentId))
                    .map(row -> new TicketRow(
                            row.id,
                            row.enrollmentId,
                            row.participantId,
                            row.participantName,
                            row.participantRelation,
                            row.participantAge,
                            row.ticketCode,
                            row.status,
                            row.checkedInAt,
                            row.cancelledAt))
                    .toList();
        }

        @Override
        public int cancelTicket(UUID enrollmentId, UUID ticketId, OffsetDateTime at) {
            for (Ticket row : tickets) {
                if (row.id.equals(ticketId) && row.enrollmentId.equals(enrollmentId) && row.status.equals("ACTIVE")) {
                    row.status = "CANCELLED";
                    row.cancelledAt = at;
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int cancelEnrollmentIfEmpty(UUID enrollmentId, OffsetDateTime at) {
            boolean ticketLeft = tickets.stream()
                    .anyMatch(row -> row.enrollmentId.equals(enrollmentId) && !row.status.equals("CANCELLED"));
            for (Enrollment row : enrollments) {
                if (row.id.equals(enrollmentId) && row.status.equals("CONFIRMED") && !ticketLeft) {
                    row.status = "CANCELLED";
                    row.cancelledAt = at;
                    return 1;
                }
            }
            return 0;
        }

        // ---- 核销 ----

        @Override
        public CheckinRow lockTicketForOrganizer(String ticketCode, UUID organizationId) {
            ticketLocks++;
            if (!ORGANIZER.equals(organizationId) && !CO_ORGANIZER.equals(organizationId)) {
                return null;
            }
            return tickets.stream()
                    .filter(row -> row.ticketCode.equals(ticketCode))
                    .map(row -> new CheckinRow(
                            row.id,
                            row.enrollmentId,
                            row.ticketCode,
                            row.participantName,
                            row.status,
                            row.checkedInAt,
                            TITLE,
                            session.label,
                            session.startsAt,
                            session.endsAt,
                            session.checkInOpensAt))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int checkIn(UUID ticketId, UUID operatorId, OffsetDateTime at) {
            for (Ticket row : tickets) {
                if (row.id.equals(ticketId) && row.status.equals("ACTIVE")) {
                    row.status = "CHECKED_IN";
                    row.checkedInAt = at;
                    row.checkedInBy = operatorId;
                    return 1;
                }
            }
            return 0;
        }

        static final class Session {
            String activityStatus = "PUBLISHED";
            String sessionStatus = "ACTIVE";
            String priceMode = "FREE";
            Integer minAge;
            Integer maxAge;
            boolean waitlistAllowed;
            OffsetDateTime startsAt = STARTS_AT;
            OffsetDateTime endsAt = ENDS_AT;
            OffsetDateTime checkInOpensAt;
            int capacity = 60;
            int enrolledCount = 48;
            String label = "上午场";
            String coverUrl = COVER;
            Double latitude = 37.43;
            Double longitude = 118.67;
        }

        static final class Participant {
            final UUID id;
            final UUID ownerId;
            final String relation;
            String name;
            final LocalDate birthMonth;
            final OffsetDateTime guardianConfirmedAt;
            boolean photoUseAuthorized;
            String status = "ACTIVE";

            Participant(
                    UUID id,
                    UUID ownerId,
                    String relation,
                    String name,
                    LocalDate birthMonth,
                    OffsetDateTime guardianConfirmedAt
            ) {
                this.id = id;
                this.ownerId = ownerId;
                this.relation = relation;
                this.name = name;
                this.birthMonth = birthMonth;
                this.guardianConfirmedAt = guardianConfirmedAt;
            }

            ParticipantRow toRow() {
                return new ParticipantRow(id, relation, name, birthMonth, photoUseAuthorized);
            }
        }

        static final class Enrollment {
            final UUID id;
            final UUID accountId;
            final UUID activityId;
            final UUID sessionId;
            final UUID idempotencyKey;
            final String requestHash;
            final OffsetDateTime createdAt;
            final OffsetDateTime noticeAgreedAt;
            String status = "CONFIRMED";
            OffsetDateTime cancelledAt;

            Enrollment(
                    UUID id,
                    UUID accountId,
                    UUID activityId,
                    UUID sessionId,
                    UUID idempotencyKey,
                    String requestHash,
                    OffsetDateTime at
            ) {
                this.id = id;
                this.accountId = accountId;
                this.activityId = activityId;
                this.sessionId = sessionId;
                this.idempotencyKey = idempotencyKey;
                this.requestHash = requestHash;
                this.createdAt = at;
                this.noticeAgreedAt = at;
            }
        }

        static final class Ticket {
            final UUID id;
            final UUID enrollmentId;
            final UUID sessionId;
            final UUID participantId;
            final String participantName;
            final String participantRelation;
            final Integer participantAge;
            final String ticketCode;
            String status = "ACTIVE";
            OffsetDateTime checkedInAt;
            UUID checkedInBy;
            OffsetDateTime cancelledAt;

            Ticket(
                    UUID id,
                    UUID enrollmentId,
                    UUID sessionId,
                    UUID participantId,
                    String participantName,
                    String participantRelation,
                    Integer participantAge,
                    String ticketCode
            ) {
                this.id = id;
                this.enrollmentId = enrollmentId;
                this.sessionId = sessionId;
                this.participantId = participantId;
                this.participantName = participantName;
                this.participantRelation = participantRelation;
                this.participantAge = participantAge;
                this.ticketCode = ticketCode;
            }
        }

        record Event(UUID enrollmentId, UUID ticketId, String eventType, UUID actorId, OffsetDateTime at) {
        }

        record CountCall(UUID accountId, String bucket, OffsetDateTime now) {
        }

        record PageCall(UUID accountId, String bucket, OffsetDateTime now, int limit, int offset) {
        }
    }
}
