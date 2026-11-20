package com.lowaltitude.inspection.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.live.application.LiveException.Error;
import com.lowaltitude.inspection.live.application.LiveStreamProvider.StreamEvent;
import com.lowaltitude.inspection.live.application.LiveStreamProvider.StreamEventType;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 直播的规则测试（M20）。mapper 换成内存实现，直播服务、机构、显示名、站内信、客服反馈、关键词过滤换成替身；时钟可以往前拨。
 */
class LiveServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T11:00:00Z");
    private static final UUID EAGLE = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID STOPPED = UUID.fromString("c1000000-0000-4000-8000-0000000000ff");
    private static final UUID OPERATOR = UUID.fromString("f2000000-0000-4000-8000-0000000000a1");
    private static final UUID VIEWER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_VIEWER = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID NAMELESS = UUID.fromString("11111111-1111-1111-1111-333333333333");
    private static final UUID SERVICE = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000401");
    private static final String CITY = "370500";

    private final InMemoryLiveMapper mapper = new InMemoryLiveMapper();
    private final List<String> notices = new ArrayList<>();
    private final List<String> reports = new ArrayList<>();
    private final List<String> stopped = new ArrayList<>();
    private final Map<String, StreamEvent> signed = new HashMap<>();
    private boolean streamsUp = true;
    private Instant now = NOW;

    private final LiveStreamProvider streams = new LiveStreamProvider() {
        @Override
        public StreamCredentials createStream(UUID roomId) {
            if (!streamsUp) {
                throw new LiveException(Error.STREAM_UNAVAILABLE);
            }
            return new StreamCredentials("s-" + roomId, "rtmp://push/" + roomId, "https://play/" + roomId + ".m3u8");
        }

        @Override
        public StreamEvent verifyCallback(Map<String, String> headers, String body) {
            StreamEvent event = signed.get(headers.get("x-sig"));
            if (event == null) {
                throw new LiveException(Error.CALLBACK_REJECTED);
            }
            return event;
        }

        @Override
        public void stopStream(String streamId) {
            stopped.add(streamId);
        }
    };

    private final LiveService service = new LiveService(
            mapper,
            streams,
            id -> id.equals(EAGLE) ? Optional.of("鹰眼智能巡检") : id.equals(ROBOT) ? Optional.of("示例机器人商家")
                    : Optional.empty(),
            id -> id.equals(VIEWER) ? Optional.of("王女士（示例）") : id.equals(OTHER_VIEWER) ? Optional.of("张工")
                    : Optional.empty(),
            (account, room, title, host, at) -> notices.add(account + " " + room + " " + title + " " + host + " " + at),
            (account, key, text) -> reports.add(account + " " + key + " " + text),
            new KeywordCommentFilter(List.of("加微信")),
            new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now;
                }
            });

    // ---- 主办方建直播间、改预告 ----

    @Test
    void aHostCreatesAScheduledRoomOnceAndOnlyTheHostSeesThePushUrl() {
        UUID key = UUID.randomUUID();
        LiveHostRoomCreated created = service.createRoom(EAGLE, key, draft("机器狗园区夜巡演示", hours(2)));
        LiveHostRoomCreated again = service.createRoom(EAGLE, key, draft("机器狗园区夜巡演示", hours(2)));

        assertThat(created.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.room().room().roomId()).isEqualTo(created.room().room().roomId());
        assertThat(mapper.rooms).hasSize(1);
        LiveRoomView room = created.room().room();
        assertThat(room.status()).isEqualTo(LiveRoomStatus.SCHEDULED);
        assertThat(room.host()).isEqualTo(new LiveHostView(EAGLE, "鹰眼智能巡检"));
        assertThat(room.topics()).containsExactly("夜间路线怎么定", "到点位后拍什么");
        assertThat(room.playUrl()).isNull();
        assertThat(created.room().pushUrl()).startsWith("rtmp://push/");
        assertThat(mapper.lockedActors).contains(EAGLE);

        assertRejected(() -> service.createRoom(EAGLE, key, draft("换了标题", hours(2))), Error.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void aRoomNeedsAnActiveHostAndAWorkingStreamService() {
        assertRejected(() -> service.createRoom(STOPPED, UUID.randomUUID(), draft("直播", hours(1))), Error.FORBIDDEN);
        streamsUp = false;
        assertRejected(() -> service.createRoom(EAGLE, UUID.randomUUID(), draft("直播", hours(1))),
                Error.STREAM_UNAVAILABLE);
        assertThat(mapper.rooms).isEmpty();
    }

    @Test
    void theDraftIsCheckedBeforeAnythingIsWritten() {
        for (LiveRoomDraft bad : List.of(
                draft("一".repeat(31), hours(1)),
                draft(" ", hours(1)),
                draft("换行\n标题", hours(1)),
                draft("过去", hours(-1)),
                draft("太远", Duration.ofDays(91)),
                new LiveRoomDraft("封面", null, "http://cover", null, at(hours(1)), null, null, null),
                new LiveRoomDraft("城市", null, null, "3705", at(hours(1)), null, null, null),
                new LiveRoomDraft("要点", null, null, null, at(hours(1)), null, null,
                        List.of("1", "2", "3", "4", "5", "6")),
                new LiveRoomDraft("要点", null, null, null, at(hours(1)), null, null, List.of("一".repeat(41))))) {
            assertThatThrownBy(() -> service.createRoom(EAGLE, UUID.randomUUID(), bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(mapper.rooms).isEmpty();
    }

    @Test
    void onlyAScheduledRoomOfTheSameHostCanBeEdited() {
        UUID roomId = room(EAGLE, "机器狗园区夜巡演示");

        LiveHostRoomView edited = service.updateRoom(EAGLE, roomId, draft("园区夜巡方案怎么定", hours(5)));
        assertThat(edited.room().title()).isEqualTo("园区夜巡方案怎么定");
        assertRejected(() -> service.updateRoom(ROBOT, roomId, draft("别人的", hours(5))), Error.NOT_FOUND);
        assertRejected(() -> service.hostRoom(ROBOT, roomId), Error.NOT_FOUND);

        callback(roomId, StreamEventType.STARTED, 0);
        assertRejected(() -> service.updateRoom(EAGLE, roomId, draft("开播后", hours(5))), Error.NOT_SCHEDULED);
    }

    @Test
    void theReplayLinkIsHttpsAndComesWithItsPlatformName() {
        UUID roomId = room(EAGLE, "光伏电站热斑巡检现场连线");

        assertThatThrownBy(() -> service.setReplay(EAGLE, roomId, "http://replay", "示例直播平台"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setReplay(EAGLE, roomId, "https://replay", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.setReplay(EAGLE, roomId, "https://replay/1", "示例直播平台").room())
                .extracting(LiveRoomView::replayUrl, LiveRoomView::replayPlatformName)
                .containsExactly("https://replay/1", "示例直播平台");
        assertThat(service.setReplay(EAGLE, roomId, " ", null).room().replayUrl()).isNull();
    }

    @Test
    void theExplainerListHasAtMostTenDistinctItemsAndACurrentOneFromIt() {
        UUID roomId = room(EAGLE, "机器狗园区夜巡演示");
        List<LiveItemDraft> two = List.of(new LiveItemDraft("inspection_service", SERVICE, "园区安防巡检"),
                new LiveItemDraft("PRODUCT", PRODUCT, "巡检机器狗"));

        LiveHostRoomView set = service.setItems(EAGLE, roomId, two, 1);
        assertThat(set.room().items()).extracting(LiveItemView::seq, LiveItemView::type, LiveItemView::title)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, LiveItemType.INSPECTION_SERVICE, "园区安防巡检"),
                        org.assertj.core.groups.Tuple.tuple(2, LiveItemType.PRODUCT, "巡检机器狗"));
        assertThat(set.room().currentItemSeq()).isEqualTo(1);
        assertThat(service.rooms(CITY, null, 1, 10).items().getFirst().currentItem())
                .isEqualTo(new LiveItemView(1, LiveItemType.INSPECTION_SERVICE, SERVICE, "园区安防巡检"));
        assertThat(service.setCurrentItem(EAGLE, roomId, 2).room().currentItemSeq()).isEqualTo(2);
        assertThat(service.setCurrentItem(EAGLE, roomId, null).room().currentItemSeq()).isNull();

        assertThatThrownBy(() -> service.setCurrentItem(EAGLE, roomId, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setItems(EAGLE, roomId, two, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setItems(EAGLE, roomId, List.of(two.get(0), two.get(0)), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setItems(EAGLE, roomId, List.of(new LiveItemDraft("ARTICLE", SERVICE, "图文")),
                null)).isInstanceOf(IllegalArgumentException.class);
        List<LiveItemDraft> eleven = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            eleven.add(new LiveItemDraft("PRODUCT", UUID.randomUUID(), "商品" + index));
        }
        assertThatThrownBy(() -> service.setItems(EAGLE, roomId, eleven, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.setItems(EAGLE, roomId, List.of(), null).room().items()).isEmpty();

        callback(roomId, StreamEventType.ENDED, 0);
        assertRejected(() -> service.setItems(EAGLE, roomId, two, null), Error.ENDED);
        assertRejected(() -> service.setCurrentItem(EAGLE, roomId, null), Error.ENDED);
    }

    // ---- 公开读取 ----

    @Test
    void theListPutsLiveRoomsFirstThenPreviewsByTimeThenReplays() {
        UUID later = room(EAGLE, "五小时后", hours(5), CITY);
        UUID sooner = room(EAGLE, "两小时后", hours(2), null);
        UUID live = room(EAGLE, "直播中", hours(1), CITY);
        UUID ended = room(ROBOT, "已结束有回看", hours(1), CITY);
        UUID endedWithoutReplay = room(ROBOT, "已结束没回看", hours(1), CITY);
        UUID elsewhere = room(ROBOT, "济南的", hours(1), "370100");
        UUID liveLater = room(EAGLE, "后开播的", hours(1), CITY);
        callback(live, StreamEventType.STARTED, 0);
        callback(liveLater, StreamEventType.STARTED, 5);
        callback(ended, StreamEventType.STARTED, 0);
        callback(ended, StreamEventType.ENDED, 1);
        service.setReplay(ROBOT, ended, "https://replay", "示例直播平台");
        callback(endedWithoutReplay, StreamEventType.ENDED, 0);

        LiveRoomPageView page = service.rooms(CITY, null, 1, 10);
        // 直播中的开播早的在前（不按人气排）。
        assertThat(page.items()).extracting(LiveRoomCardView::roomId)
                .containsExactly(live, liveLater, sooner, later, ended);
        assertThat(page.items().getLast().hasReplay()).isTrue();
        assertThat(page.total()).isEqualTo(5);
        assertThat(service.rooms(CITY, "live", 1, 10).items()).extracting(LiveRoomCardView::roomId)
                .containsExactly(live, liveLater);
        assertThat(service.rooms(CITY, null, 2, 3).items()).extracting(LiveRoomCardView::roomId)
                .containsExactly(later, ended);
        assertThat(service.rooms(CITY, null, 1, 3).hasMore()).isTrue();
        assertThat(service.rooms("370100", null, 1, 10).items()).extracting(LiveRoomCardView::roomId)
                .contains(elsewhere, sooner).doesNotContain(later);

        // 开播时间过了一天还没开播的预告不再列。
        now = NOW.plus(Duration.ofHours(30));
        assertThat(service.rooms(CITY, "SCHEDULED", 1, 10).items()).isEmpty();

        assertThatThrownBy(() -> service.rooms("3705", null, 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rooms(CITY, "PAUSED", 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rooms(CITY, null, 1, 21)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rooms(CITY, null, 0, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDetailIsVisibleInItsCityOrEverywhereAndPlaysOnlyWhileLive() {
        UUID local = room(EAGLE, "东营的", hours(1), CITY);
        UUID national = room(EAGLE, "全国的", hours(1), null);

        assertThat(service.room(national, "370100").title()).isEqualTo("全国的");
        assertRejected(() -> service.room(local, "370100"), Error.NOT_FOUND);
        assertRejected(() -> service.room(UUID.randomUUID(), CITY), Error.NOT_FOUND);
        assertThat(service.room(local, CITY).playUrl()).isNull();

        callback(local, StreamEventType.STARTED, 0);
        assertThat(service.room(local, CITY).playUrl()).isEqualTo("https://play/" + local + ".m3u8");
        callback(local, StreamEventType.ENDED, 1);
        assertThat(service.room(local, CITY).playUrl()).isNull();
    }

    // ---- 服务商回调 ----

    @Test
    void goingLiveNotifiesEachReminderOnceAndLaterEventsFollowTheStream() {
        UUID roomId = room(EAGLE, "机器狗园区夜巡演示");
        service.remind(VIEWER, roomId);
        service.remind(OTHER_VIEWER, roomId);
        service.cancelReminder(OTHER_VIEWER, roomId);

        String started = callback(roomId, StreamEventType.STARTED, 0);
        assertThat(service.room(roomId, CITY).status()).isEqualTo(LiveRoomStatus.LIVE);
        assertThat(service.room(roomId, CITY).startedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(notices).containsExactly(VIEWER + " " + roomId + " 机器狗园区夜巡演示 鹰眼智能巡检 " + NOW);

        // 同一个事件再来一次不处理；断流以后恢复也不再通知。
        service.receiveStreamCallback(Map.of("x-sig", started), "{}");
        callback(roomId, StreamEventType.INTERRUPTED, 1);
        assertThat(service.room(roomId, CITY).streamInterrupted()).isTrue();
        callback(roomId, StreamEventType.STARTED, 2);
        assertThat(service.room(roomId, CITY).streamInterrupted()).isFalse();
        assertThat(notices).hasSize(1);
        // 开播以后不能再设提醒。
        assertRejected(() -> service.remind(OTHER_VIEWER, roomId), Error.NOT_SCHEDULED);

        // 比上一条旧的事件不处理。
        callback(roomId, StreamEventType.ENDED, -10);
        assertThat(service.room(roomId, CITY).status()).isEqualTo(LiveRoomStatus.LIVE);
        callback(roomId, StreamEventType.ENDED, 3);
        LiveRoomView ended = service.room(roomId, CITY);
        assertThat(ended.status()).isEqualTo(LiveRoomStatus.ENDED);
        assertThat(ended.endedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW.plusSeconds(3), ZoneOffset.UTC));
        assertThat(mapper.room(roomId).endReason).isEqualTo("STREAM");
        // 结束以后再来开播不改。
        callback(roomId, StreamEventType.STARTED, 4);
        assertThat(service.room(roomId, CITY).status()).isEqualTo(LiveRoomStatus.ENDED);
    }

    @Test
    void anUnsignedCallbackIsRejectedAndAnUnknownStreamIsIgnored() {
        assertRejected(() -> service.receiveStreamCallback(Map.of("x-sig", "nope"), "{}"), Error.CALLBACK_REJECTED);

        signed.put("unknown", new StreamEvent("e-unknown", "s-unknown", StreamEventType.STARTED, NOW));
        service.receiveStreamCallback(Map.of("x-sig", "unknown"), "{}");
        assertThat(mapper.events).isEmpty();
    }

    @Test
    void thePlatformSupportEndsARoomAndCutsTheStream() {
        UUID roomId = room(EAGLE, "机器狗园区夜巡演示");
        callback(roomId, StreamEventType.STARTED, 0);
        now = NOW.plusSeconds(60);

        LiveRoomView ended = service.platformEnd(roomId);
        assertThat(ended.status()).isEqualTo(LiveRoomStatus.ENDED);
        assertThat(mapper.room(roomId).endReason).isEqualTo("PLATFORM");
        assertThat(stopped).containsExactly("s-" + roomId);
        assertThat(service.platformEnd(roomId).status()).isEqualTo(LiveRoomStatus.ENDED);
        assertThat(stopped).hasSize(1);
        assertRejected(() -> service.platformEnd(UUID.randomUUID()), Error.NOT_FOUND);

        // 预告也可以结束。
        UUID scheduled = room(EAGLE, "园区夜巡方案怎么定");
        assertThat(service.platformEnd(scheduled).status()).isEqualTo(LiveRoomStatus.ENDED);
    }

    // ---- 观众 ----

    @Test
    void viewersAreCountedOncePerDeviceWhileLive() {
        UUID roomId = room(EAGLE, "机器狗园区夜巡演示");
        UUID device = UUID.randomUUID();

        assertThat(service.recordView(roomId, device)).isEqualTo(new LiveViewCountView(0, false));
        callback(roomId, StreamEventType.STARTED, 0);
        assertThat(service.recordView(roomId, device)).isEqualTo(new LiveViewCountView(1, true));
        assertThat(service.recordView(roomId, device)).isEqualTo(new LiveViewCountView(1, false));
        assertThat(service.recordView(roomId, UUID.randomUUID())).isEqualTo(new LiveViewCountView(2, true));
        assertThatThrownBy(() -> service.recordView(roomId, null)).isInstanceOf(IllegalArgumentException.class);
        assertRejected(() -> service.recordView(UUID.randomUUID(), device), Error.NOT_FOUND);
    }

    @Test
    void commentsAreOneShortLineFromALiveRoomWithAMaskedNameAndAPause() {
        UUID roomId = room(EAGLE, "机器狗园区夜巡演示");
        UUID key = UUID.randomUUID();
        assertRejected(() -> service.comment(VIEWER, roomId, key, "机器狗一晚上能巡几圈？"), Error.NOT_LIVE);
        callback(roomId, StreamEventType.STARTED, 0);

        LiveCommentSent first = service.comment(VIEWER, roomId, key, "  机器狗一晚上能巡几圈？ ");
        assertThat(first.created()).isTrue();
        assertThat(first.comment()).extracting(LiveCommentView::seq, LiveCommentView::side,
                        LiveCommentView::authorName, LiveCommentView::text)
                .containsExactly(1L, LiveCommentSide.VIEWER, "王**", "机器狗一晚上能巡几圈？");
        // 同一个幂等键重放返回第一次那条；内容不同就是冲突。
        LiveCommentSent replay = service.comment(VIEWER, roomId, key, "机器狗一晚上能巡几圈？");
        assertThat(replay.created()).isFalse();
        assertThat(replay.comment().commentId()).isEqualTo(first.comment().commentId());
        assertRejected(() -> service.comment(VIEWER, roomId, key, "别的话"), Error.IDEMPOTENCY_CONFLICT);

        // 两条之间至少 3 秒。
        assertRejected(() -> service.comment(VIEWER, roomId, UUID.randomUUID(), "热成像能看清人吗"),
                Error.COMMENT_TOO_FAST);
        now = NOW.plusSeconds(3);
        assertThat(service.comment(VIEWER, roomId, UUID.randomUUID(), "热成像能看清人吗").comment().seq())
                .isEqualTo(2);

        assertRejected(() -> service.comment(OTHER_VIEWER, roomId, UUID.randomUUID(), "加 微 信 聊"),
                Error.COMMENT_BLOCKED);
        assertRejected(() -> service.comment(NAMELESS, roomId, UUID.randomUUID(), "你好"), Error.FORBIDDEN);
        for (String bad : List.of("", "  ", "一".repeat(51), "两\n行")) {
            assertThatThrownBy(() -> service.comment(OTHER_VIEWER, roomId, UUID.randomUUID(), bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.comment(OTHER_VIEWER, roomId, UUID.randomUUID(), "一".repeat(50)).comment().authorName())
                .isEqualTo("张**");
    }

    @Test
    void commentsArePolledBySeqAndHiddenOnesDropOut() {
        UUID roomId = room(EAGLE, "机器狗园区夜巡演示");
        callback(roomId, StreamEventType.STARTED, 0);
        List<LiveCommentView> sent = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            now = NOW.plusSeconds(index * 5L);
            sent.add(service.comment(VIEWER, roomId, UUID.randomUUID(), "第" + index + "条").comment());
        }
        LiveCommentSent host = service.hostComment(EAGLE, OPERATOR, roomId, UUID.randomUUID(), "一圈 40 分钟");
        assertThat(host.comment()).extracting(LiveCommentView::side, LiveCommentView::authorName)
                .containsExactly(LiveCommentSide.HOST, "鹰眼智能巡检");

        LiveCommentPageView latest = service.comments(roomId, null, 3);
        assertThat(latest.items()).extracting(LiveCommentView::seq).containsExactly(4L, 5L, 6L);
        assertThat(latest.cursor()).isEqualTo(6);
        assertThat(service.comments(roomId, 6L, null).items()).isEmpty();
        assertThat(service.comments(roomId, 6L, null).cursor()).isEqualTo(6);
        // 太多时只给最新的。
        assertThat(service.comments(roomId, 1L, 2).items()).extracting(LiveCommentView::seq).containsExactly(5L, 6L);

        LiveModerationView hidden = service.hostHide(EAGLE, roomId, sent.get(1).commentId());
        assertThat(hidden.seq()).isEqualTo(2);
        assertThat(service.hostHide(EAGLE, roomId, sent.get(1).commentId()).hiddenAt()).isEqualTo(hidden.hiddenAt());
        service.platformHide(roomId, sent.get(3).commentId());
        LiveCommentPageView after = service.comments(roomId, null, null);
        assertThat(after.items()).extracting(LiveCommentView::seq).containsExactly(1L, 3L, 5L, 6L);
        assertThat(after.hiddenSeqs()).containsExactly(2L, 4L);
        assertRejected(() -> service.hostHide(ROBOT, roomId, sent.get(0).commentId()), Error.NOT_FOUND);
        assertRejected(() -> service.platformHide(roomId, UUID.randomUUID()), Error.NOT_FOUND);
        assertThatThrownBy(() -> service.comments(roomId, -1L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.comments(roomId, null, 51)).isInstanceOf(IllegalArgumentException.class);
        assertRejected(() -> service.hostComment(ROBOT, OPERATOR, roomId, UUID.randomUUID(), "别人的"), Error.NOT_FOUND);
    }

    @Test
    void likesAreMergedAndCappedPerTenSecondWindow() {
        UUID roomId = room(EAGLE, "机器狗园区夜巡演示");
        assertRejected(() -> service.like(VIEWER, roomId, 1), Error.NOT_LIVE);
        callback(roomId, StreamEventType.STARTED, 0);

        assertThat(service.like(VIEWER, roomId, 20)).isEqualTo(new LiveLikeView(20, 20));
        assertThat(service.like(VIEWER, roomId, 20)).isEqualTo(new LiveLikeView(10, 30));
        assertThat(service.like(VIEWER, roomId, 5)).isEqualTo(new LiveLikeView(0, 30));
        assertThat(service.like(OTHER_VIEWER, roomId, 3)).isEqualTo(new LiveLikeView(3, 33));
        now = NOW.plusSeconds(10);
        assertThat(service.like(VIEWER, roomId, 5)).isEqualTo(new LiveLikeView(5, 38));
        for (int bad : List.of(0, 31, -1)) {
            assertThatThrownBy(() -> service.like(VIEWER, roomId, bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void remindersAndFollowsAreTheViewersOwnState() {
        UUID roomId = room(EAGLE, "园区夜巡方案怎么定");

        assertThat(service.viewerState(VIEWER, roomId)).isEqualTo(new LiveViewerStateView(roomId, false, false));
        assertThat(service.remind(VIEWER, roomId)).isEqualTo(new LiveViewerStateView(roomId, true, false));
        assertThat(service.remind(VIEWER, roomId).reminded()).isTrue();
        assertThat(service.follow(VIEWER, EAGLE)).isEqualTo(new LiveFollowView(EAGLE, true));
        assertThat(service.viewerState(VIEWER, roomId)).isEqualTo(new LiveViewerStateView(roomId, true, true));
        assertThat(service.viewerState(OTHER_VIEWER, roomId)).isEqualTo(new LiveViewerStateView(roomId, false, false));
        assertThat(service.unfollow(VIEWER, EAGLE)).isEqualTo(new LiveFollowView(EAGLE, false));
        assertThat(service.cancelReminder(VIEWER, roomId).reminded()).isFalse();
        assertThat(service.cancelReminder(VIEWER, roomId).reminded()).isFalse();

        // 只能关注办过直播的机构。
        assertRejected(() -> service.follow(VIEWER, ROBOT), Error.NOT_FOUND);
        assertRejected(() -> service.remind(VIEWER, UUID.randomUUID()), Error.NOT_FOUND);
    }

    @Test
    void aReportGoesToTheSupportFeedbackOncePerComment() {
        UUID roomId = room(EAGLE, "机器狗园区夜巡演示");
        callback(roomId, StreamEventType.STARTED, 0);
        LiveCommentView comment = service.comment(OTHER_VIEWER, roomId, UUID.randomUUID(), "热成像能看清人吗").comment();

        assertThat(service.report(VIEWER, roomId, comment.commentId()))
                .isEqualTo(new LiveReportView(comment.commentId(), true));
        service.report(VIEWER, roomId, comment.commentId());
        assertThat(reports).hasSize(2).allMatch(line -> line.equals(reports.getFirst()));
        assertThat(reports.getFirst()).startsWith(VIEWER + " ")
                .endsWith("举报直播弹幕：《机器狗园区夜巡演示》第 1 条「热成像能看清人吗」");
        assertRejected(() -> service.report(VIEWER, roomId, UUID.randomUUID()), Error.NOT_FOUND);
        UUID otherRoom = room(EAGLE, "别的直播间");
        assertRejected(() -> service.report(VIEWER, otherRoom, comment.commentId()), Error.NOT_FOUND);
    }

    @Test
    void theHostListOnlyHasTheHostsRooms() {
        room(EAGLE, "一");
        now = NOW.plusSeconds(1);
        UUID second = room(EAGLE, "二");
        room(ROBOT, "别人的");

        LiveHostRoomPageView page = service.hostRooms(EAGLE, 1, 10);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items().getFirst().room().roomId()).isEqualTo(second);
        assertThat(service.streamTarget(second)).isEqualTo(new LiveStreamTarget(second, "s-" + second, EAGLE));
    }

    @Test
    void theAuthorNameKeepsOnlyTheFirstCharacter() {
        assertThat(LiveService.maskedName("王女士（示例）")).isEqualTo("王**");
        assertThat(LiveService.maskedName("𠀋先生")).isEqualTo("𠀋**");
    }

    // ---- 小工具 ----

    private UUID room(UUID host, String title) {
        return room(host, title, hours(2), CITY);
    }

    private UUID room(UUID host, String title, Duration ahead, String city) {
        return service.createRoom(host, UUID.randomUUID(), new LiveRoomDraft(title, null, null, city, at(ahead), null,
                null, null)).room().room().roomId();
    }

    private LiveRoomDraft draft(String title, Duration ahead) {
        return new LiveRoomDraft(title, "看一台机器狗怎样走完园区夜间路线", "https://cover/1.jpg", CITY, at(ahead), "王工",
                "项目负责人", List.of("夜间路线怎么定", " 到点位后拍什么 "));
    }

    private OffsetDateTime at(Duration ahead) {
        return OffsetDateTime.ofInstant(now.plus(ahead), ZoneOffset.ofHours(8));
    }

    private static Duration hours(int hours) {
        return Duration.ofHours(hours);
    }

    /** 让服务商发来一条签过名的回调，发生在 NOW 之后 seconds 秒。返回签名（再发一次就是重复的同一事件）。 */
    private String callback(UUID roomId, StreamEventType type, int seconds) {
        String signature = UUID.randomUUID().toString();
        signed.put(signature, new StreamEvent("e-" + signature, "s-" + roomId, type, NOW.plusSeconds(seconds)));
        service.receiveStreamCallback(Map.of("x-sig", signature), "{}");
        return signature;
    }

    private static void assertRejected(ThrowingCallable call, Error error) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LiveException.class,
                rejected -> assertThat(rejected.error()).isEqualTo(error));
    }
}
