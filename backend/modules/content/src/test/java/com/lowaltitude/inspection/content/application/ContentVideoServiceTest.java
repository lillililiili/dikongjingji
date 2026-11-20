package com.lowaltitude.inspection.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.content.application.ContentException.Error;
import com.lowaltitude.inspection.content.infrastructure.ContentWordListFilter;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper.CommentRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper.CommentTargetRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper.ReportRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper.VideoRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper.VideoTargetRow;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 发现页视频（1.2.0，计划 07 §2、§4）的规则：视频流的起点和游标、关注流要登录、评论的长度 / 过滤 / 限频 / 幂等 /
 * 作者标记和打码、举报只记一次并记进客服反馈、隐藏评论的权限。mapper 用桩，SQL 文本由 ContentVideoMapperContractTest 检查。
 */
class ContentVideoServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T16:30:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String DONGYING = "370500";
    private static final UUID ROBOT_VIDEO = UUID.fromString("f3200000-0000-4000-8000-000000000001");
    private static final UUID DOG_VIDEO = UUID.fromString("f3200000-0000-4000-8000-000000000002");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ORG = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OPERATOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID KIT = UUID.fromString("c2000000-0000-4000-8000-000000000101");
    private static final UUID COMMENT = UUID.fromString("f3300000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final ContentMapper contentMapper = mock(ContentMapper.class);
    private final ContentVideoMapper mapper = mock(ContentVideoMapper.class);
    private final ContentPorts.Profiles profiles = mock(ContentPorts.Profiles.class);
    private final ContentPorts.Reports reports = mock(ContentPorts.Reports.class);
    private final ContentVideoService service = new ContentVideoService(contentMapper, mapper, profiles, reports,
            new ContentWordListFilter(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final ContentActor customer = ContentActor.customer(CUSTOMER);

    @BeforeEach
    void setUp() {
        when(contentMapper.findRefs(ROBOT_VIDEO)).thenReturn(List.of(
                new ContentMapper.RefRow("PRODUCT", KIT, "编程机器人探索套装")));
        when(mapper.findPublishedVideo(eq(ROBOT_VIDEO), any())).thenReturn(
                new VideoTargetRow(ROBOT_VIDEO, "3 分钟看懂编程机器人", MERCHANT, "示例机器人商家"));
        when(profiles.displayName(CUSTOMER)).thenReturn(Optional.of("王女士"));
    }

    // ---- 读 ----

    @Test
    void aVideoCarriesItsPlayerFieldsEntryAndTheViewersOwnMarks() {
        when(mapper.findVideo(ROBOT_VIDEO, DONGYING, NOW_UTC, CUSTOMER)).thenReturn(robot("https://media.example.com/r.mp4",
                "#编程机器人  少儿编程 #避障 编程机器人", true, true));

        ContentVideoView view = service.video(ROBOT_VIDEO, DONGYING, customer);

        assertThat(view.videoId()).isEqualTo(ROBOT_VIDEO);
        assertThat(view.author()).isEqualTo(new ContentAuthorView(ContentAuthorKind.MERCHANT, MERCHANT, "示例机器人商家"));
        assertThat(view.sponsored()).isTrue();
        assertThat(view.durationSeconds()).isEqualTo(192);
        assertThat(view.orientation()).isEqualTo(ContentVideoOrientation.LANDSCAPE);
        assertThat(view.playUrl()).isEqualTo("https://media.example.com/r.mp4");
        assertThat(view.tags()).containsExactly("编程机器人", "少儿编程", "避障");
        assertThat(view.refs()).containsExactly(new ContentRefView(ContentRefType.PRODUCT, KIT, "编程机器人探索套装"));
        assertThat(view.likeCount()).isEqualTo(12);
        assertThat(view.commentCount()).isEqualTo(3);
        assertThat(view.liked()).isTrue();
        assertThat(view.followingAuthor()).isTrue();
    }

    @Test
    void onlyHttpsPlayAddressesAreGivenAndAnonymousViewersAreAskedWithoutAnAccount() {
        when(mapper.findVideo(ROBOT_VIDEO, DONGYING, NOW_UTC, null)).thenReturn(robot("http://media.example.com/r.mp4",
                null, false, false));

        ContentVideoView view = service.video(ROBOT_VIDEO, DONGYING, null);

        assertThat(view.playUrl()).isNull();
        assertThat(view.tags()).isEmpty();
        assertThat(view.caption()).isEqualTo("它是怎么“看见”障碍的？");
        assertNotFound(() -> service.video(DOG_VIDEO, DONGYING, null));
        assertInvalid(() -> service.video(null, DONGYING, null));
        assertInvalid(() -> service.video(ROBOT_VIDEO, "dongying", null));
    }

    @Test
    void theStreamStartsAtTheTappedVideoAndContinuesByCursor() {
        VideoRow start = robot(null, null, false, false);
        VideoRow dog = row(DOG_VIDEO, 50, NOW_UTC.minusDays(23));
        VideoRow third = row(UUID.randomUUID(), 40, NOW_UTC.minusDays(21));
        when(mapper.findVideo(ROBOT_VIDEO, DONGYING, NOW_UTC, null)).thenReturn(start);
        when(mapper.findStream(DONGYING, NOW_UTC, null, null, 60, start.publishedAt(), ROBOT_VIDEO, true, 3))
                .thenReturn(List.of(start, dog, third));

        ContentVideoPageView first = service.stream(DONGYING, null, ROBOT_VIDEO, null, 2, null);

        assertThat(first.feed()).isEqualTo(ContentVideoFeed.RECOMMEND);
        assertThat(first.items()).extracting(ContentVideoView::videoId).containsExactly(ROBOT_VIDEO, DOG_VIDEO);
        assertThat(first.nextCursor()).isNotBlank();

        // 游标优先于起点：接着机器狗往下，不含它。
        when(mapper.findStream(DONGYING, NOW_UTC, null, null, 50, dog.publishedAt(), DOG_VIDEO, false, 3))
                .thenReturn(List.of(third));
        ContentVideoPageView next = service.stream(DONGYING, "RECOMMEND", ROBOT_VIDEO, first.nextCursor(), 2, null);
        assertThat(next.items()).hasSize(1);
        assertThat(next.nextCursor()).isNull();

        // 从头开始：没有位置。
        when(mapper.findStream(DONGYING, NOW_UTC, null, null, null, null, null, false, 6)).thenReturn(List.of(start));
        assertThat(service.stream(DONGYING, null, null, null, null, null).items()).hasSize(1);
    }

    @Test
    void theStreamRejectsBadInputsAndAStartOutsideTheStream() {
        assertInvalid(() -> service.stream(DONGYING, "LIVE", null, null, null, null));
        assertInvalid(() -> service.stream(DONGYING, null, null, null, 0, null));
        assertInvalid(() -> service.stream(DONGYING, null, null, null, ContentVideoService.MAX_STREAM_SIZE + 1, null));
        assertInvalid(() -> service.stream(DONGYING, null, null, "not-a-cursor", null, null));
        assertInvalid(() -> service.stream(DONGYING, null, null, "x".repeat(201), null, null));
        assertInvalid(() -> service.stream("", null, null, null, null, null));
        assertNotFound(() -> service.stream(DONGYING, null, DOG_VIDEO, null, null, null));
    }

    @Test
    void theFollowingStreamNeedsALoginAndOnlyStartsAtAFollowedAuthor() {
        assertThatThrownBy(() -> service.stream(DONGYING, "FOLLOWING", null, null, null, null))
                .isInstanceOf(ContentException.class).extracting("error").isEqualTo(Error.UNAUTHORIZED);

        when(mapper.findVideo(ROBOT_VIDEO, DONGYING, NOW_UTC, CUSTOMER)).thenReturn(robot(null, null, false, false));
        assertNotFound(() -> service.stream(DONGYING, "FOLLOWING", ROBOT_VIDEO, null, null, customer));

        when(mapper.findStream(DONGYING, NOW_UTC, CUSTOMER, CUSTOMER, null, null, null, false, 6)).thenReturn(List.of());
        ContentVideoPageView following = service.stream(DONGYING, "following", null, null, null, customer);
        assertThat(following.feed()).isEqualTo(ContentVideoFeed.FOLLOWING);
        assertThat(following.items()).isEmpty();
        assertThat(following.nextCursor()).isNull();
    }

    // ---- 点赞、关注 ----

    @Test
    void likesAndFollowsNeedALoginAndAPublishedTarget() {
        when(mapper.countLikes(ROBOT_VIDEO)).thenReturn(13L);

        assertThat(service.like(ROBOT_VIDEO, customer)).isEqualTo(new ContentVideoLikeView(ROBOT_VIDEO, true, 13));
        assertThat(service.unlike(ROBOT_VIDEO, customer)).isEqualTo(new ContentVideoLikeView(ROBOT_VIDEO, false, 13));
        verify(mapper).insertLike(ROBOT_VIDEO, CUSTOMER, NOW_UTC);
        verify(mapper).deleteLike(ROBOT_VIDEO, CUSTOMER);
        assertNotFound(() -> service.like(DOG_VIDEO, customer));
        assertUnauthorized(() -> service.like(ROBOT_VIDEO, null));

        when(mapper.isPublishingOrganization(MERCHANT, NOW_UTC)).thenReturn(true);
        assertThat(service.follow(MERCHANT, customer)).isEqualTo(new ContentFollowView(MERCHANT, true));
        verify(mapper).insertFollow(CUSTOMER, MERCHANT, NOW_UTC);
        assertNotFound(() -> service.follow(OTHER_ORG, customer));
        // 取消关注不看机构是否发过内容。
        assertThat(service.unfollow(OTHER_ORG, customer)).isEqualTo(new ContentFollowView(OTHER_ORG, false));
        assertUnauthorized(() -> service.follow(MERCHANT, new ContentActor(null, null)));
    }

    // ---- 评论 ----

    @Test
    void aCommentIsTrimmedMaskedAndPostedOnce() {
        when(mapper.insertComment(eq(ROBOT_VIDEO), eq(CUSTOMER), eq("王**"), eq(false), eq("看完就想带孩子去试试"),
                eq(KEY), anyString(), eq(NOW_UTC))).thenAnswer(call -> comment(call.getArgument(2), false,
                call.getArgument(4), call.getArgument(6)));

        ContentCommentPosted posted = service.comment(ROBOT_VIDEO, customer, KEY, "  看完就想带孩子去试试 ");

        assertThat(posted.created()).isTrue();
        assertThat(posted.comment().authorName()).isEqualTo("王**");
        assertThat(posted.comment().byAuthor()).isFalse();
        assertThat(posted.comment().mine()).isTrue();
        verify(mapper).lockCommenter(CUSTOMER);
        verify(mapper).countCommentsSince(CUSTOMER, NOW_UTC.minusSeconds(60));
    }

    @Test
    void theSameKeyReplaysAndADifferentTextWithItConflicts() {
        // 同样的内容：返回第一次那条，不再过滤、不计限频。
        when(mapper.findCommentByKey(CUSTOMER, KEY)).thenReturn(new CommentRow(COMMENT, ROBOT_VIDEO, CUSTOMER, "王**",
                false, "看完就想带孩子去试试", "VISIBLE", hashOf(ROBOT_VIDEO + "|看完就想带孩子去试试"), NOW_UTC));
        ContentCommentPosted replay = service.comment(ROBOT_VIDEO, customer, KEY, "看完就想带孩子去试试");
        assertThat(replay.created()).isFalse();
        assertThat(replay.comment().commentId()).isEqualTo(COMMENT);
        verify(mapper, never()).countCommentsSince(any(), any());

        assertThatThrownBy(() -> service.comment(ROBOT_VIDEO, customer, KEY, "换了一句"))
                .isInstanceOf(ContentException.class).extracting("error").isEqualTo(Error.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void blockedWordsPhoneNumbersAndBurstsAreRefused() {
        assertThatThrownBy(() -> service.comment(ROBOT_VIDEO, customer, KEY, "想要的加 微 信详聊"))
                .isInstanceOf(ContentException.class).extracting("error").isEqualTo(Error.COMMENT_BLOCKED);
        assertThatThrownBy(() -> service.comment(ROBOT_VIDEO, customer, KEY, "电话 138-0000-1234"))
                .isInstanceOf(ContentException.class).extracting("error").isEqualTo(Error.COMMENT_BLOCKED);

        when(mapper.countCommentsSince(CUSTOMER, NOW_UTC.minusSeconds(60))).thenReturn(3L);
        assertThatThrownBy(() -> service.comment(ROBOT_VIDEO, customer, KEY, "画面比我想象的清楚多了"))
                .isInstanceOf(ContentException.class).extracting("error").isEqualTo(Error.COMMENT_TOO_FREQUENT);
        verify(mapper, never()).insertComment(any(), any(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void emptyLongOrMultilineCommentsAreInvalidAndTheVideoMustBePublished() {
        assertInvalid(() -> service.comment(ROBOT_VIDEO, customer, KEY, "   "));
        assertInvalid(() -> service.comment(ROBOT_VIDEO, customer, KEY, "好".repeat(201)));
        assertInvalid(() -> service.comment(ROBOT_VIDEO, customer, KEY, "第一行\n第二行"));
        assertInvalid(() -> service.comment(ROBOT_VIDEO, customer, null, "好"));
        assertNotFound(() -> service.comment(DOG_VIDEO, customer, KEY, "好"));
        assertUnauthorized(() -> service.comment(ROBOT_VIDEO, null, KEY, "好"));
        // 200 个字（按字符算，不按字节）可以。
        when(mapper.insertComment(any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenAnswer(call -> comment(call.getArgument(2), false, call.getArgument(4), call.getArgument(6)));
        assertThat(service.comment(ROBOT_VIDEO, customer, KEY, "好".repeat(200)).created()).isTrue();
    }

    @Test
    void theAuthorsOperatorCommentsUnderTheOrganizationName() {
        when(mapper.insertComment(any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenAnswer(call -> comment(call.getArgument(2), call.getArgument(3), call.getArgument(4),
                        call.getArgument(6)));

        ContentCommentView byAuthor = service.comment(ROBOT_VIDEO, new ContentActor(OPERATOR, MERCHANT), KEY,
                "有的，点视频下方的入口可以直接看详情").comment();
        assertThat(byAuthor.authorName()).isEqualTo("示例机器人商家");
        assertThat(byAuthor.byAuthor()).isTrue();

        // 别的机构的操作员只是普通评论；没有显示名的叫“用户”。
        when(profiles.displayName(OPERATOR)).thenReturn(Optional.empty());
        ContentCommentView other = service.comment(ROBOT_VIDEO, new ContentActor(OPERATOR, OTHER_ORG), KEY,
                "企业用的话怎么报价？").comment();
        assertThat(other.authorName()).isEqualTo("用户");
        assertThat(other.byAuthor()).isFalse();
    }

    @Test
    void namesAreMaskedToTheirFirstCharacter() {
        assertThat(ContentVideoService.maskedName("王女士")).isEqualTo("王**");
        assertThat(ContentVideoService.maskedName("𠀀先生")).isEqualTo("𠀀**");
        assertThat(ContentVideoService.maskedName(" ")).isEqualTo("用户");
        assertThat(ContentVideoService.maskedName(null)).isEqualTo("用户");
    }

    @Test
    void commentsAreListedNewestFirstWithTheViewersOwnMarked() {
        when(mapper.countVisibleComments(ROBOT_VIDEO)).thenReturn(21L);
        when(mapper.findVisibleComments(ROBOT_VIDEO, 20, 0)).thenReturn(List.of(comment("王**", false, "第一条", null),
                new CommentRow(UUID.randomUUID(), ROBOT_VIDEO, OPERATOR, "示例机器人商家", true, "回复", "VISIBLE",
                        "x", NOW_UTC)));

        ContentCommentPageView page = service.comments(ROBOT_VIDEO, 1, 20, customer);

        assertThat(page.total()).isEqualTo(21);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).extracting(ContentCommentView::mine).containsExactly(true, false);
        assertThat(page.items().get(1).byAuthor()).isTrue();
        assertThat(service.comments(ROBOT_VIDEO, 1, 20, null).items()).extracting(ContentCommentView::mine)
                .containsOnly(false);
        assertInvalid(() -> service.comments(ROBOT_VIDEO, 0, 20, null));
        assertInvalid(() -> service.comments(ROBOT_VIDEO, 1, 51, null));
        assertNotFound(() -> service.comments(DOG_VIDEO, 1, 20, null));
    }

    // ---- 举报、隐藏 ----

    @Test
    void aReportGoesToSupportFeedbackOncePerAccount() {
        when(mapper.findCommentTarget(COMMENT, NOW_UTC)).thenReturn(target("VISIBLE", true));
        UUID feedback = UUID.randomUUID();
        when(reports.record(eq(CUSTOMER), eq(ContentVideoService.reportKey(COMMENT)), anyString())).thenReturn(feedback);
        when(mapper.insertReport(COMMENT, CUSTOMER, "AD_SPAM", feedback, NOW_UTC))
                .thenReturn(new ReportRow(COMMENT, "AD_SPAM", NOW_UTC));

        ContentCommentReported first = service.report(COMMENT, customer, "ad_spam");

        assertThat(first.created()).isTrue();
        assertThat(first.report()).isEqualTo(new ContentCommentReportView(COMMENT, ContentCommentReportReason.AD_SPAM,
                NOW_UTC));
        verify(reports).record(CUSTOMER, ContentVideoService.reportKey(COMMENT),
                "举报视频评论（广告引流）：视频《3 分钟看懂编程机器人》，评论 " + COMMENT + "。");

        // 再举报：返回第一次那条，原因以第一次为准，不再记反馈。
        when(mapper.findReport(COMMENT, CUSTOMER)).thenReturn(new ReportRow(COMMENT, "AD_SPAM", NOW_UTC));
        ContentCommentReported again = service.report(COMMENT, customer, "ABUSE");
        assertThat(again.created()).isFalse();
        assertThat(again.report().reason()).isEqualTo(ContentCommentReportReason.AD_SPAM);
        verify(reports).record(any(), any(), anyString());
    }

    @Test
    void hiddenOrMissingCommentsCannotBeReportedAndReasonsAreChecked() {
        when(mapper.findCommentTarget(COMMENT, NOW_UTC)).thenReturn(target("HIDDEN", true));
        assertNotFound(() -> service.report(COMMENT, customer, "ABUSE"));
        when(mapper.findCommentTarget(COMMENT, NOW_UTC)).thenReturn(target("VISIBLE", false));
        assertNotFound(() -> service.report(COMMENT, customer, "ABUSE"));
        assertNotFound(() -> service.report(UUID.randomUUID(), customer, "ABUSE"));
        assertInvalid(() -> service.report(COMMENT, customer, "RUDE"));
        assertInvalid(() -> service.report(COMMENT, customer, null));
        assertUnauthorized(() -> service.report(COMMENT, null, "ABUSE"));
        verifyNoInteractions(reports);
    }

    @Test
    void platformSupportHidesAnyCommentAndOperatorsOnlyTheirOwnVideos() {
        when(mapper.findCommentTarget(eq(COMMENT), any())).thenReturn(target("VISIBLE", true));
        when(mapper.hideComment(COMMENT, AGENT, NOW_UTC)).thenReturn(NOW_UTC);
        when(mapper.hideComment(COMMENT, OPERATOR, NOW_UTC)).thenReturn(NOW_UTC);

        assertThat(service.hide(COMMENT, new ContentModerator(AGENT, true, null)))
                .isEqualTo(new ContentCommentHiddenView(COMMENT, true, NOW_UTC));
        assertThat(service.hide(COMMENT, new ContentModerator(OPERATOR, false, MERCHANT)).hidden()).isTrue();
        assertNotFound(() -> service.hide(COMMENT, new ContentModerator(OPERATOR, false, OTHER_ORG)));
        assertThatThrownBy(() -> service.hide(COMMENT, null))
                .isInstanceOf(ContentException.class).extracting("error").isEqualTo(Error.FORBIDDEN);

        // 已经隐藏的：返回原来的时间，不再写。
        OffsetDateTime earlier = NOW_UTC.minusHours(1);
        when(mapper.findCommentTarget(eq(COMMENT), any())).thenReturn(new CommentTargetRow(COMMENT, "HIDDEN", earlier,
                ROBOT_VIDEO, "3 分钟看懂编程机器人", MERCHANT, true));
        assertThat(service.hide(COMMENT, new ContentModerator(AGENT, true, null)).hiddenAt()).isEqualTo(earlier);
        verify(mapper, never()).hideComment(eq(COMMENT), eq(AGENT), eq(earlier));
    }

    @Test
    void streamCursorsRoundTripAndTagsAreCleaned() {
        ContentVideoService.StreamKey key = new ContentVideoService.StreamKey(60, NOW_UTC, ROBOT_VIDEO);
        assertThat(ContentVideoService.StreamKey.decode(key.encode())).isEqualTo(key);
        assertThat(ContentVideoService.tags("#a #a  ## " + "长".repeat(21))).containsExactly("a");
        assertThat(ContentVideoService.reportKey(COMMENT)).isEqualTo(ContentVideoService.reportKey(COMMENT))
                .isNotEqualTo(ContentVideoService.reportKey(UUID.randomUUID()));
    }

    // ---- 辅助 ----

    private static VideoRow robot(String url, String tags, boolean liked, boolean following) {
        return new VideoRow(ROBOT_VIDEO, "3 分钟看懂编程机器人", "MERCHANT", MERCHANT, "示例机器人商家", true, DONGYING, null,
                true, NOW_UTC.minusDays(16), 192, "LANDSCAPE", url, "它是怎么“看见”障碍的？", tags, 60, 12, 3, liked,
                following);
    }

    private static VideoRow row(UUID id, int rank, OffsetDateTime publishedAt) {
        return new VideoRow(id, "视频", "PROVIDER", OTHER_ORG, "鹰眼智能巡检", false, DONGYING, null, true, publishedAt,
                108, "PORTRAIT", null, null, null, rank, 0, 0, false, false);
    }

    private static CommentRow comment(String name, boolean byAuthor, String body, String hash) {
        return new CommentRow(COMMENT, ROBOT_VIDEO, CUSTOMER, name, byAuthor, body, "VISIBLE",
                hash == null ? hashOf(ROBOT_VIDEO + "|" + body) : hash, NOW_UTC);
    }

    private static CommentTargetRow target(String status, boolean published) {
        return new CommentTargetRow(COMMENT, status, null, ROBOT_VIDEO, "3 分钟看懂编程机器人", MERCHANT, published);
    }

    private static String hashOf(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ContentNotFoundException.class);
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertUnauthorized(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ContentException.class).extracting("error").isEqualTo(Error.UNAUTHORIZED);
    }
}
