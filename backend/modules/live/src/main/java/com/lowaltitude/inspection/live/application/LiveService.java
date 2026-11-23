package com.lowaltitude.inspection.live.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.live.application.LiveException.Error;
import com.lowaltitude.inspection.live.application.LiveStreamProvider.StreamCredentials;
import com.lowaltitude.inspection.live.application.LiveStreamProvider.StreamEvent;
import com.lowaltitude.inspection.live.infrastructure.persistence.LiveMapper;
import com.lowaltitude.inspection.live.infrastructure.persistence.LiveMapper.CommentRow;
import com.lowaltitude.inspection.live.infrastructure.persistence.LiveMapper.ItemRow;
import com.lowaltitude.inspection.live.infrastructure.persistence.LiveMapper.RoomFields;
import com.lowaltitude.inspection.live.infrastructure.persistence.LiveMapper.RoomRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台内直播的观看端（M20，docs/redesign/07-视频与直播实现计划.md §3、§4，契约 contracts/openapi-live.yaml）。
 *
 * <ul>
 *   <li>状态只有预告、直播中、已结束；开播、断流、结束只认验签后的服务商回调（按事件 ID 去重，比上一条旧的不处理），
 *       平台客服可以强制结束。主办方不能自己改状态。</li>
 *   <li>开播时把还没通知过的提醒标上通知时间，给这些账号各记一条“直播提醒”（端口在提交后写），所以同一直播间每人只记一次。</li>
 *   <li>弹幕 1–50 字一行；同一账号两条之间至少 3 秒；过关键词过滤；作者名打码。序号按直播间递增，轮询按序号取增量。</li>
 *   <li>观看人数是直播中进过的不同设备数；点赞每人每直播间每 10 秒最多记 30 次。两者都累计不减。</li>
 *   <li>主办方只碰本机构的直播间，别的机构的一律当作不存在；推流地址只在主办方的视图里。</li>
 * </ul>
 */
public class LiveService {

    static final int DEFAULT_PAGE_SIZE = 10;
    static final int MAX_PAGE_SIZE = 20;
    static final int DEFAULT_COMMENT_PAGE = 20;
    static final int MAX_COMMENT_PAGE = 50;
    static final int MAX_COMMENT = 50;
    static final Duration COMMENT_INTERVAL = Duration.ofSeconds(3);
    static final int MAX_LIKES_PER_WINDOW = 30;
    static final int LIKE_WINDOW_SECONDS = 10;
    static final int HIDDEN_WINDOW = 100;
    static final int MAX_TITLE = 30;
    static final int MAX_SUBTITLE = 40;
    static final int MAX_SPEAKER_NAME = 20;
    static final int MAX_SPEAKER_TITLE = 30;
    static final int MAX_TOPICS = 5;
    static final int MAX_TOPIC = 40;
    static final int MAX_URL = 500;
    static final int MAX_PLATFORM_NAME = 20;
    static final int MAX_ITEMS = 10;
    static final int MAX_ITEM_TITLE = 40;
    static final int MAX_REPORT = 200;
    static final Duration MAX_AHEAD = Duration.ofDays(90);
    /** 预告的开播时间过了这么久还没开播，就不再出现在列表里。 */
    static final Duration STALE_SCHEDULE = Duration.ofDays(1);

    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");

    private final LiveMapper mapper;
    private final LiveStreamProvider streams;
    private final LivePorts.Organizations organizations;
    private final LivePorts.Profiles profiles;
    private final LivePorts.Reminders reminders;
    private final LivePorts.Reports reports;
    private final LivePorts.CommentFilter filter;
    private final Clock clock;

    public LiveService(
            LiveMapper mapper,
            LiveStreamProvider streams,
            LivePorts.Organizations organizations,
            LivePorts.Profiles profiles,
            LivePorts.Reminders reminders,
            LivePorts.Reports reports,
            LivePorts.CommentFilter filter
    ) {
        this(mapper, streams, organizations, profiles, reminders, reports, filter, Clock.systemUTC());
    }

    LiveService(
            LiveMapper mapper,
            LiveStreamProvider streams,
            LivePorts.Organizations organizations,
            LivePorts.Profiles profiles,
            LivePorts.Reminders reminders,
            LivePorts.Reports reports,
            LivePorts.CommentFilter filter,
            Clock clock
    ) {
        this.mapper = mapper;
        this.streams = streams;
        this.organizations = organizations;
        this.profiles = profiles;
        this.reminders = reminders;
        this.reports = reports;
        this.filter = filter;
        this.clock = clock;
    }

    // ---- 公开读取 ----

    /** 当前城市的直播：直播中的在前，预告按开播时间，已结束的只列有回看的。 */
    @Transactional(readOnly = true)
    public LiveRoomPageView rooms(String cityCode, String statusCode, int page, int size) {
        String city = requireCity(cityCode);
        String status = statusCode == null || statusCode.isBlank()
                ? null
                : parse(LiveRoomStatus.class, statusCode, "status").name();
        requirePage(page, size);
        OffsetDateTime staleBefore = now().minus(STALE_SCHEDULE);
        long total = mapper.countListed(city, status, staleBefore);
        List<LiveRoomCardView> items = mapper.findListed(city, status, staleBefore, size, offset(page, size)).stream()
                .map(LiveService::cardView)
                .toList();
        return new LiveRoomPageView(page, size, total, (long) page * size < total, items);
    }

    /** 直播间详情：只要当前城市可见（城市为空的全国可见）。 */
    @Transactional(readOnly = true)
    public LiveRoomView room(UUID roomId, String cityCode) {
        String city = requireCity(cityCode);
        RoomRow row = existing(roomId);
        if (row.cityCode() != null && !row.cityCode().equals(city)) {
            throw new LiveException(Error.NOT_FOUND);
        }
        return roomView(row);
    }

    /** 弹幕增量：不带 since 取最近的；带 since 取比它新的，太多时只给最新的 size 条。 */
    @Transactional(readOnly = true)
    public LiveCommentPageView comments(UUID roomId, Long since, Integer size) {
        if (since != null && since < 0) {
            throw new IllegalArgumentException("since is invalid");
        }
        int limit = size == null ? DEFAULT_COMMENT_PAGE : size;
        if (limit < 1 || limit > MAX_COMMENT_PAGE) {
            throw new IllegalArgumentException("size is invalid");
        }
        RoomRow room = existing(roomId);
        List<CommentRow> rows = mapper.findComments(room.id(), since, limit);
        List<LiveCommentView> items = new ArrayList<>();
        for (CommentRow row : rows) {
            items.add(commentView(row));
        }
        long newest = rows.stream().mapToLong(CommentRow::seq).max().orElse(0);
        long cursor = Math.max(Math.max(room.commentCount(), newest), since == null ? 0 : since);
        return new LiveCommentPageView(items.reversed(), cursor,
                mapper.findHiddenSeqs(room.id(), Math.max(0, cursor - HIDDEN_WINDOW)));
    }

    /** 进直播间记一次观看：只在直播中记，同一设备在同一直播间只记一次。 */
    @Transactional
    public LiveViewCountView recordView(UUID roomId, UUID deviceId) {
        if (deviceId == null) {
            throw new IllegalArgumentException("device is required");
        }
        RoomRow room = existing(roomId);
        if (!LiveRoomStatus.LIVE.name().equals(room.status())) {
            return new LiveViewCountView(room.viewerCount(), false);
        }
        if (mapper.insertViewer(room.id(), deviceId, now()) == 0 || mapper.addViewer(room.id()) == 0) {
            return new LiveViewCountView(room.viewerCount(), false);
        }
        return new LiveViewCountView(room.viewerCount() + 1, true);
    }

    // ---- 观众（登录） ----

    @Transactional(readOnly = true)
    public LiveViewerStateView viewerState(UUID accountId, UUID roomId) {
        requireAccount(accountId);
        RoomRow room = existing(roomId);
        return state(accountId, room);
    }

    /** 设开播提醒：只有预告能设，设过的再设不变。 */
    @Transactional
    public LiveViewerStateView remind(UUID accountId, UUID roomId) {
        requireAccount(accountId);
        RoomRow room = existing(roomId);
        if (!LiveRoomStatus.SCHEDULED.name().equals(room.status())) {
            throw new LiveException(Error.NOT_SCHEDULED);
        }
        mapper.insertReminder(room.id(), accountId, now());
        return state(accountId, room);
    }

    @Transactional
    public LiveViewerStateView cancelReminder(UUID accountId, UUID roomId) {
        requireAccount(accountId);
        RoomRow room = existing(roomId);
        mapper.deleteReminder(room.id(), accountId);
        return state(accountId, room);
    }

    /** 观众发一条弹幕。同一个幂等键带同样的内容重复提交，返回第一次那条（created 为 false）。 */
    @Transactional
    public LiveCommentSent comment(UUID accountId, UUID roomId, UUID idempotencyKey, String text) {
        requireAccount(accountId);
        requireId(roomId);
        requireKey(idempotencyKey);
        String body = commentText(text);
        String hash = hash(roomId + "|" + body);

        mapper.lockActor(accountId);
        LiveCommentSent replayed = replay(accountId, idempotencyKey, hash);
        if (replayed != null) {
            return replayed;
        }
        RoomRow room = existing(roomId);
        if (!LiveRoomStatus.LIVE.name().equals(room.status())) {
            throw new LiveException(Error.NOT_LIVE);
        }
        if (filter.blocks(body)) {
            throw new LiveException(Error.COMMENT_BLOCKED);
        }
        OffsetDateTime at = now();
        OffsetDateTime last = mapper.lastViewerCommentAt(accountId);
        if (last != null && last.plus(COMMENT_INTERVAL).isAfter(at)) {
            throw new LiveException(Error.COMMENT_TOO_FAST);
        }
        String name = maskedName(profiles.displayName(accountId)
                .filter(found -> !found.isBlank())
                .orElseThrow(() -> new LiveException(Error.FORBIDDEN)));
        return new LiveCommentSent(insertComment(room.id(), LiveCommentSide.VIEWER, accountId, name, body,
                idempotencyKey, hash, at), true);
    }

    /** 点赞：count 是 App 合并的次数（1–30）；同一账号在同一直播间每 10 秒最多记 30 次，多出的不记。 */
    @Transactional
    public LiveLikeView like(UUID accountId, UUID roomId, int count) {
        requireAccount(accountId);
        if (count < 1 || count > MAX_LIKES_PER_WINDOW) {
            throw new IllegalArgumentException("count is invalid");
        }
        RoomRow room = existing(roomId);
        if (!LiveRoomStatus.LIVE.name().equals(room.status())) {
            throw new LiveException(Error.NOT_LIVE);
        }
        Instant instant = clock.instant();
        OffsetDateTime window = OffsetDateTime.ofInstant(Instant.ofEpochSecond(
                Math.floorDiv(instant.getEpochSecond(), LIKE_WINDOW_SECONDS) * LIKE_WINDOW_SECONDS), ZoneOffset.UTC);
        mapper.insertLikeWindow(room.id(), accountId, window);
        int used = mapper.lockLikeWindow(room.id(), accountId, window);
        int accepted = Math.max(0, Math.min(count, MAX_LIKES_PER_WINDOW - used));
        if (accepted == 0) {
            return new LiveLikeView(0, room.likeCount());
        }
        mapper.setLikeWindow(room.id(), accountId, window, used + accepted);
        return new LiveLikeView(accepted, mapper.addLikes(room.id(), accepted));
    }

    /** 关注主办机构：只能关注办过直播的机构；关注过的再关注不变。 */
    @Transactional
    public LiveFollowView follow(UUID accountId, UUID organizationId) {
        requireAccount(accountId);
        requireHostKnown(organizationId);
        mapper.insertFollow(accountId, organizationId, now());
        return new LiveFollowView(organizationId, true);
    }

    @Transactional
    public LiveFollowView unfollow(UUID accountId, UUID organizationId) {
        requireAccount(accountId);
        requireHostKnown(organizationId);
        mapper.deleteFollow(accountId, organizationId);
        return new LiveFollowView(organizationId, false);
    }

    /** 举报一条弹幕：记到客服反馈（类型 CONTENT）。同一账号同一条只记一次（幂等键由弹幕 ID 算出）。 */
    @Transactional
    public LiveReportView report(UUID accountId, UUID roomId, UUID commentId) {
        requireAccount(accountId);
        requireId(commentId);
        RoomRow room = existing(roomId);
        CommentRow comment = mapper.findRoomComment(room.id(), commentId);
        if (comment == null) {
            throw new LiveException(Error.NOT_FOUND);
        }
        String text = clip("举报直播弹幕：《" + room.title() + "》第 " + comment.seq() + " 条「" + comment.body()
                + "」", MAX_REPORT);
        UUID key = UUID.nameUUIDFromBytes(("live-report:" + comment.id()).getBytes(StandardCharsets.UTF_8));
        reports.report(accountId, key, text, room.id(), comment.id());
        return new LiveReportView(comment.id(), true);
    }

    // ---- 主办方 ----

    @Transactional(readOnly = true)
    public LiveHostRoomPageView hostRooms(UUID hostOrgId, int page, int size) {
        requireOrganization(hostOrgId);
        requirePage(page, size);
        long total = mapper.countHostRooms(hostOrgId);
        List<LiveHostRoomView> items = mapper.findHostRooms(hostOrgId, size, offset(page, size)).stream()
                .map(this::hostView)
                .toList();
        return new LiveHostRoomPageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public LiveHostRoomView hostRoom(UUID hostOrgId, UUID roomId) {
        requireOrganization(hostOrgId);
        requireId(roomId);
        RoomRow row = mapper.findRoom(roomId);
        if (row == null || !row.hostOrgId().equals(hostOrgId)) {
            throw new LiveException(Error.NOT_FOUND);
        }
        return hostView(row);
    }

    /** 建直播间（预告）并向服务商建流。同一个幂等键带同样的内容重复提交，返回第一次那个（created 为 false）。 */
    @Transactional
    public LiveHostRoomCreated createRoom(UUID hostOrgId, UUID idempotencyKey, LiveRoomDraft draft) {
        requireOrganization(hostOrgId);
        requireKey(idempotencyKey);
        RoomFields fields = roomFields(draft);
        String hash = hash(fingerprint(fields));

        mapper.lockActor(hostOrgId);
        RoomRow existing = mapper.findRoomByKey(hostOrgId, idempotencyKey);
        if (existing != null) {
            if (!hash.equals(existing.requestHash().trim())) {
                throw new LiveException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new LiveHostRoomCreated(hostView(existing), false);
        }
        String hostName = organizations.activeName(hostOrgId)
                .filter(name -> !name.isBlank())
                .orElseThrow(() -> new LiveException(Error.FORBIDDEN));
        UUID roomId = UUID.randomUUID();
        StreamCredentials stream = streams.createStream(roomId);
        if (stream == null || blank(stream.streamId()) || blank(stream.pushUrl()) || blank(stream.playUrl())) {
            throw new LiveException(Error.STREAM_UNAVAILABLE);
        }
        mapper.insertRoom(roomId, hostOrgId, clip(hostName, 80), fields, stream.streamId(), stream.pushUrl(),
                stream.playUrl(), idempotencyKey, hash, now());
        return new LiveHostRoomCreated(hostView(mapper.findRoom(roomId)), true);
    }

    /** 改预告：只有预告能改。 */
    @Transactional
    public LiveHostRoomView updateRoom(UUID hostOrgId, UUID roomId, LiveRoomDraft draft) {
        requireOrganization(hostOrgId);
        RoomFields fields = roomFields(draft);
        RoomRow room = lockHostRoom(hostOrgId, roomId);
        if (!LiveRoomStatus.SCHEDULED.name().equals(room.status())
                || mapper.updatePreview(room.id(), fields, now()) == 0) {
            throw new LiveException(Error.NOT_SCHEDULED);
        }
        return hostView(mapper.findRoom(room.id()));
    }

    /** 设回看链接：两项都给或都不给（都不给就是去掉回看）。 */
    @Transactional
    public LiveHostRoomView setReplay(UUID hostOrgId, UUID roomId, String replayUrl, String platformName) {
        requireOrganization(hostOrgId);
        String url = optional(replayUrl, MAX_URL, "replay url");
        String name = optional(platformName, MAX_PLATFORM_NAME, "replay platform");
        if ((url == null) != (name == null)) {
            throw new IllegalArgumentException("replay url and platform go together");
        }
        if (url != null) {
            requireHttps(url, "replay url");
        }
        RoomRow room = lockHostRoom(hostOrgId, roomId);
        mapper.updateReplay(room.id(), url, name, now());
        return hostView(mapper.findRoom(room.id()));
    }

    /** 设讲解清单（0–10 项，同一对象只出现一次）和当前讲解项。已结束的不能改。 */
    @Transactional
    public LiveHostRoomView setItems(UUID hostOrgId, UUID roomId, List<LiveItemDraft> drafts, Integer currentSeq) {
        requireOrganization(hostOrgId);
        List<LiveItemDraft> items = items(drafts);
        if (currentSeq != null && (currentSeq < 1 || currentSeq > items.size())) {
            throw new IllegalArgumentException("current item is not in the list");
        }
        RoomRow room = lockHostRoom(hostOrgId, roomId);
        if (LiveRoomStatus.ENDED.name().equals(room.status())) {
            throw new LiveException(Error.ENDED);
        }
        OffsetDateTime at = now();
        mapper.deleteItems(room.id());
        for (int index = 0; index < items.size(); index++) {
            LiveItemDraft item = items.get(index);
            mapper.insertItem(room.id(), index + 1, item.type(), item.refId(), item.title());
        }
        mapper.updateCurrentItem(room.id(), currentSeq, at);
        return hostView(mapper.findRoom(room.id()));
    }

    /** 换当前讲解项；为空表示不讲解任何一项。 */
    @Transactional
    public LiveHostRoomView setCurrentItem(UUID hostOrgId, UUID roomId, Integer seq) {
        requireOrganization(hostOrgId);
        RoomRow room = lockHostRoom(hostOrgId, roomId);
        if (LiveRoomStatus.ENDED.name().equals(room.status())) {
            throw new LiveException(Error.ENDED);
        }
        if (seq != null && mapper.findItems(room.id()).stream().noneMatch(item -> item.seq() == seq)) {
            throw new IllegalArgumentException("current item is not in the list");
        }
        mapper.updateCurrentItem(room.id(), seq, now());
        return hostView(mapper.findRoom(room.id()));
    }

    /** 以主播身份发弹幕：显示机构名，不限频、不过关键词过滤。 */
    @Transactional
    public LiveCommentSent hostComment(UUID hostOrgId, UUID operatorId, UUID roomId, UUID idempotencyKey,
                                       String text) {
        requireOrganization(hostOrgId);
        requireAccount(operatorId);
        requireId(roomId);
        requireKey(idempotencyKey);
        String body = commentText(text);
        String hash = hash(roomId + "|" + body);

        mapper.lockActor(operatorId);
        LiveCommentSent replayed = replay(operatorId, idempotencyKey, hash);
        if (replayed != null) {
            return replayed;
        }
        RoomRow room = mapper.findRoom(roomId);
        if (room == null || !room.hostOrgId().equals(hostOrgId)) {
            throw new LiveException(Error.NOT_FOUND);
        }
        if (!LiveRoomStatus.LIVE.name().equals(room.status())) {
            throw new LiveException(Error.NOT_LIVE);
        }
        return new LiveCommentSent(insertComment(room.id(), LiveCommentSide.HOST, operatorId, room.hostName(), body,
                idempotencyKey, hash, now()), true);
    }

    @Transactional
    public LiveModerationView hostHide(UUID hostOrgId, UUID roomId, UUID commentId) {
        requireOrganization(hostOrgId);
        RoomRow room = mapper.findRoom(requireId(roomId));
        if (room == null || !room.hostOrgId().equals(hostOrgId)) {
            throw new LiveException(Error.NOT_FOUND);
        }
        return hide(room.id(), commentId, "HOST");
    }

    // ---- 平台客服 ----

    @Transactional
    public LiveModerationView platformHide(UUID roomId, UUID commentId) {
        return hide(existing(roomId).id(), commentId, "PLATFORM");
    }

    /** 强制结束（预告也可以结束），并通知服务商断流。已经结束的不变。 */
    @Transactional
    public LiveRoomView platformEnd(UUID roomId) {
        RoomRow room = mapper.lockRoom(requireId(roomId));
        if (room == null) {
            throw new LiveException(Error.NOT_FOUND);
        }
        if (LiveRoomStatus.ENDED.name().equals(room.status())) {
            return roomView(room);
        }
        mapper.updateStatus(room.id(), LiveRoomStatus.ENDED.name(), room.startedAt(), now(), "PLATFORM", false,
                room.lastEventAt(), now());
        try {
            streams.stopStream(room.streamId());
        } catch (RuntimeException ignored) {
            // 断流失败不影响结束：直播间已经不给播放地址，服务商那边的流会在超时后自己断。
        }
        return roomView(mapper.findRoom(room.id()));
    }

    // ---- 服务商回调 ----

    /**
     * 服务商回调：验签后按事件改状态。不认识的流、重复的事件、比上一条旧的事件都不处理（不算错）。
     * 开播（预告 → 直播中）时给设了提醒的账号各记一条站内信。
     */
    @Transactional
    public void receiveStreamCallback(Map<String, String> headers, String body) {
        StreamEvent event = streams.verifyCallback(headers == null ? Map.of() : headers, body == null ? "" : body);
        if (event == null || blank(event.eventId()) || blank(event.streamId()) || event.type() == null
                || event.occurredAt() == null || event.eventId().length() > 80) {
            throw new LiveException(Error.CALLBACK_REJECTED);
        }
        RoomRow room = mapper.lockRoomByStream(event.streamId());
        if (room == null) {
            return;
        }
        OffsetDateTime occurred = OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC);
        OffsetDateTime at = now();
        if (mapper.insertStreamEvent(event.eventId(), room.id(), event.type().name(), occurred, at) == 0) {
            return;
        }
        if (room.lastEventAt() != null && occurred.isBefore(room.lastEventAt())) {
            return;
        }
        LiveRoomStatus status = LiveRoomStatus.valueOf(room.status());
        switch (event.type()) {
            case STARTED -> {
                if (status == LiveRoomStatus.SCHEDULED) {
                    mapper.updateStatus(room.id(), LiveRoomStatus.LIVE.name(), occurred, null, null, false, occurred,
                            at);
                    for (UUID accountId : mapper.claimReminders(room.id(), at)) {
                        reminders.liveStarted(accountId, room.id(), room.title(), room.hostName(),
                                event.occurredAt());
                    }
                } else if (status == LiveRoomStatus.LIVE) {
                    mapper.updateStatus(room.id(), status.name(), room.startedAt(), null, null, false, occurred, at);
                }
            }
            case INTERRUPTED -> {
                if (status == LiveRoomStatus.LIVE) {
                    mapper.updateStatus(room.id(), status.name(), room.startedAt(), null, null, true, occurred, at);
                }
            }
            case ENDED -> {
                if (status != LiveRoomStatus.ENDED) {
                    mapper.updateStatus(room.id(), LiveRoomStatus.ENDED.name(), room.startedAt(), occurred, "STREAM",
                            false, occurred, at);
                }
            }
            default -> throw new IllegalStateException("unknown stream event " + event.type());
        }
    }

    /** 开发环境的模拟回调用：直播间的流和主办机构。 */
    @Transactional(readOnly = true)
    public LiveStreamTarget streamTarget(UUID roomId) {
        RoomRow room = existing(roomId);
        return new LiveStreamTarget(room.id(), room.streamId(), room.hostOrgId());
    }

    // ---- 内部 ----

    private LiveViewerStateView state(UUID accountId, RoomRow room) {
        return new LiveViewerStateView(room.id(), mapper.reminderExists(room.id(), accountId),
                mapper.followExists(accountId, room.hostOrgId()));
    }

    private LiveCommentSent replay(UUID authorId, UUID idempotencyKey, String hash) {
        CommentRow existing = mapper.findCommentByKey(authorId, idempotencyKey);
        if (existing == null) {
            return null;
        }
        if (existing.requestHash() == null || !hash.equals(existing.requestHash().trim())) {
            throw new LiveException(Error.IDEMPOTENCY_CONFLICT);
        }
        return new LiveCommentSent(commentView(existing), false);
    }

    private LiveCommentView insertComment(UUID roomId, LiveCommentSide side, UUID authorId, String authorName,
                                          String body, UUID idempotencyKey, String hash, OffsetDateTime at) {
        long seq = mapper.nextCommentSeq(roomId);
        UUID commentId = mapper.insertComment(roomId, seq, side.name(), authorId, authorName, body, idempotencyKey,
                hash, at);
        return commentView(mapper.findRoomComment(roomId, commentId));
    }

    private LiveModerationView hide(UUID roomId, UUID commentId, String by) {
        requireId(commentId);
        if (mapper.findRoomComment(roomId, commentId) == null) {
            throw new LiveException(Error.NOT_FOUND);
        }
        mapper.hideComment(roomId, commentId, by, now());
        CommentRow hidden = mapper.findRoomComment(roomId, commentId);
        return new LiveModerationView(hidden.id(), hidden.seq(), hidden.hiddenAt());
    }

    private RoomRow existing(UUID roomId) {
        RoomRow row = mapper.findRoom(requireId(roomId));
        if (row == null) {
            throw new LiveException(Error.NOT_FOUND);
        }
        return row;
    }

    private RoomRow lockHostRoom(UUID hostOrgId, UUID roomId) {
        RoomRow row = mapper.lockRoom(requireId(roomId));
        if (row == null || !row.hostOrgId().equals(hostOrgId)) {
            throw new LiveException(Error.NOT_FOUND);
        }
        return row;
    }

    private void requireHostKnown(UUID organizationId) {
        requireOrganization(organizationId);
        if (!mapper.hostExists(organizationId)) {
            throw new LiveException(Error.NOT_FOUND);
        }
    }

    private RoomFields roomFields(LiveRoomDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("room is required");
        }
        String title = line(draft.title(), MAX_TITLE, "title");
        String subtitle = optional(draft.subtitle(), MAX_SUBTITLE, "subtitle");
        String cover = optional(draft.coverUrl(), MAX_URL, "cover");
        if (cover != null) {
            requireHttps(cover, "cover");
        }
        String city = draft.cityCode() == null || draft.cityCode().isBlank() ? null : requireCity(draft.cityCode());
        OffsetDateTime scheduledAt = draft.scheduledAt();
        OffsetDateTime at = now();
        if (scheduledAt == null || !scheduledAt.isAfter(at) || scheduledAt.isAfter(at.plus(MAX_AHEAD))) {
            throw new IllegalArgumentException("scheduled time is invalid");
        }
        List<String> topics = draft.topics() == null ? List.of() : draft.topics();
        if (topics.size() > MAX_TOPICS) {
            throw new IllegalArgumentException("too many topics");
        }
        List<String> cleanTopics = new ArrayList<>();
        for (String topic : topics) {
            cleanTopics.add(line(topic, MAX_TOPIC, "topic"));
        }
        return new RoomFields(title, subtitle, cover, city, scheduledAt.withOffsetSameInstant(ZoneOffset.UTC),
                optional(draft.speakerName(), MAX_SPEAKER_NAME, "speaker"),
                optional(draft.speakerTitle(), MAX_SPEAKER_TITLE, "speaker title"),
                cleanTopics.isEmpty() ? null : String.join("\n", cleanTopics));
    }

    private static String fingerprint(RoomFields fields) {
        return String.join("|", Arrays.asList(fields.title(), fields.subtitle(), fields.coverUrl(), fields.cityCode(),
                fields.scheduledAt().toInstant().toString(), fields.speakerName(), fields.speakerTitle(),
                fields.topics()));
    }

    private static List<LiveItemDraft> items(List<LiveItemDraft> drafts) {
        List<LiveItemDraft> source = drafts == null ? List.of() : drafts;
        if (source.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("too many items");
        }
        Set<String> seen = new HashSet<>();
        List<LiveItemDraft> clean = new ArrayList<>();
        for (LiveItemDraft draft : source) {
            if (draft == null || draft.refId() == null) {
                throw new IllegalArgumentException("item is invalid");
            }
            LiveItemType type = parse(LiveItemType.class, draft.type(), "item type");
            if (!seen.add(type.name() + ":" + draft.refId())) {
                throw new IllegalArgumentException("an item appears twice");
            }
            clean.add(new LiveItemDraft(type.name(), draft.refId(), line(draft.title(), MAX_ITEM_TITLE, "item title")));
        }
        return clean;
    }

    private static String commentText(String text) {
        return line(text, MAX_COMMENT, "comment");
    }

    /** 一行字：去掉首尾空白后 1–max 个字，不能有换行和控制字符。 */
    private static String line(String value, int max, String what) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > max
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return trimmed;
    }

    private static String optional(String value, int max, String what) {
        return value == null || value.isBlank() ? null : line(value, max, what);
    }

    private static void requireHttps(String url, String what) {
        if (!url.startsWith("https://") || url.length() <= "https://".length() || url.chars().anyMatch(
                Character::isWhitespace)) {
            throw new IllegalArgumentException(what + " must be https");
        }
    }

    /** 观众的作者名：显示名的第一个字加“**”。 */
    static String maskedName(String displayName) {
        String trimmed = displayName.strip();
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)) + "**";
    }

    private static String clip(String value, int max) {
        return value.codePointCount(0, value.length()) <= max
                ? value
                : value.substring(0, value.offsetByCodePoints(0, max));
    }

    private LiveHostRoomView hostView(RoomRow row) {
        return new LiveHostRoomView(roomView(row), row.pushUrl());
    }

    private LiveRoomView roomView(RoomRow row) {
        LiveRoomStatus status = LiveRoomStatus.valueOf(row.status());
        List<LiveItemView> items = mapper.findItems(row.id()).stream().map(LiveService::itemView).toList();
        return new LiveRoomView(row.id(), row.title(), row.subtitle(), row.coverUrl(),
                new LiveHostView(row.hostOrgId(), row.hostName()), row.cityCode(), status, row.scheduledAt(),
                row.startedAt(), row.endedAt(), row.viewerCount(), row.likeCount(), row.speakerName(),
                row.speakerTitle(), topics(row.topics()), items, row.currentItemSeq(), row.replayUrl(),
                row.replayPlatformName(), status == LiveRoomStatus.LIVE ? row.playUrl() : null,
                status == LiveRoomStatus.LIVE && row.streamInterrupted());
    }

    private static LiveRoomCardView cardView(RoomRow row) {
        LiveItemView current = row.currentItemType() == null
                ? null
                : new LiveItemView(row.currentItemSeq(), LiveItemType.valueOf(row.currentItemType()),
                        row.currentItemRefId(), row.currentItemTitle());
        return new LiveRoomCardView(row.id(), row.title(), row.subtitle(), row.coverUrl(),
                new LiveHostView(row.hostOrgId(), row.hostName()), row.cityCode(),
                LiveRoomStatus.valueOf(row.status()), row.scheduledAt(), row.startedAt(), row.endedAt(),
                row.viewerCount(), row.likeCount(), current, row.replayUrl() != null);
    }

    private static LiveItemView itemView(ItemRow row) {
        return new LiveItemView(row.seq(), LiveItemType.valueOf(row.itemType()), row.refId(), row.title());
    }

    private static LiveCommentView commentView(CommentRow row) {
        return new LiveCommentView(row.id(), row.seq(), LiveCommentSide.valueOf(row.side()), row.authorName(),
                row.body(), row.createdAt());
    }

    private static List<String> topics(String topics) {
        return topics == null || topics.isBlank() ? List.of() : List.of(topics.split("\n"));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String code, String what) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        try {
            return Enum.valueOf(type, code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(what + " is invalid", unknown);
        }
    }

    private static String requireCity(String cityCode) {
        if (cityCode == null || !CITY_CODE.matcher(cityCode.trim()).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return cityCode.trim();
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
    }

    private static int offset(int page, int size) {
        return Math.multiplyExact(page - 1, size);
    }

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
    }

    private static void requireOrganization(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organization is required");
        }
    }

    private static UUID requireId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return id;
    }

    private static void requireKey(UUID idempotencyKey) {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
