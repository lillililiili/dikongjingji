package com.lowaltitude.inspection.content.application;

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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lowaltitude.inspection.content.application.ContentException.Error;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper.CommentRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper.CommentTargetRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper.ReportRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper.VideoRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentVideoMapper.VideoTargetRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发现页视频（1.2.0，docs/redesign/07-视频与直播实现计划.md §2、§4，契约 contracts/openapi-content.yaml）。
 *
 * <ul>
 *   <li>读：视频详情、竖屏视频流（运营排序再按发布时间，游标翻页，可以从指定的一条开始；关注流只有关注的机构发布的）、
 *       评论分页。不登录也能读，登录了才有“我点过赞 / 我关注了 / 我发的”。</li>
 *   <li>写（要登录，任何账号）：点赞、关注发布机构（天然幂等）；发评论（≤ 200 字，幂等键，同一账号 60 秒最多 3 条，
 *       过关键词过滤，命中就拒绝，不先审后发）；举报评论（同一账号一条只记一次，记进客服反馈）。</li>
 *   <li>受权：平台客服隐藏任何评论；服务商、商家的操作员只能隐藏本机构视频下的评论。</li>
 * </ul>
 */
public class ContentVideoService {

    static final int DEFAULT_STREAM_SIZE = 5;
    static final int MAX_STREAM_SIZE = 10;
    static final int MAX_COMMENT_PAGE_SIZE = 50;
    static final int MAX_COMMENT_LENGTH = 200;
    /** 同一账号在 COMMENT_WINDOW 里最多发这么多条评论。 */
    static final int COMMENT_LIMIT = 3;
    static final Duration COMMENT_WINDOW = Duration.ofSeconds(60);
    static final int MAX_TAGS = 10;
    static final int MAX_TAG_LENGTH = 20;
    static final int MAX_CURSOR_LENGTH = 200;
    /** 没有显示名的账号在评论里叫这个。 */
    static final String ANONYMOUS_NAME = "用户";

    private final ContentMapper contentMapper;
    private final ContentVideoMapper mapper;
    private final ContentPorts.Profiles profiles;
    private final ContentPorts.Reports reports;
    private final ContentTextFilter filter;
    private final Clock clock;

    public ContentVideoService(
            ContentMapper contentMapper,
            ContentVideoMapper mapper,
            ContentPorts.Profiles profiles,
            ContentPorts.Reports reports,
            ContentTextFilter filter
    ) {
        this(contentMapper, mapper, profiles, reports, filter, Clock.systemUTC());
    }

    ContentVideoService(
            ContentMapper contentMapper,
            ContentVideoMapper mapper,
            ContentPorts.Profiles profiles,
            ContentPorts.Reports reports,
            ContentTextFilter filter,
            Clock clock
    ) {
        this.contentMapper = contentMapper;
        this.mapper = mapper;
        this.profiles = profiles;
        this.reports = reports;
        this.filter = filter;
        this.clock = clock;
    }

    // ---- 读 ----

    @Transactional(readOnly = true)
    public ContentVideoView video(UUID videoId, String cityCode, ContentActor viewer) {
        String city = ContentReadService.requireCity(cityCode);
        if (videoId == null) {
            throw new IllegalArgumentException("video id is required");
        }
        VideoRow row = mapper.findVideo(videoId, city, now(), viewerId(viewer));
        if (row == null) {
            throw new ContentNotFoundException();
        }
        return view(row);
    }

    /**
     * 视频流的一段。cursor 优先于 startVideoId；都没有时从头开始。startVideoId 不在这个流里（下线了、不是这个城市、
     * 关注流里没关注它的机构）时是 404：页面说清楚，而不是悄悄从头放。
     */
    @Transactional(readOnly = true)
    public ContentVideoPageView stream(String cityCode, String feedCode, UUID startVideoId, String cursor, Integer size,
                                       ContentActor viewer) {
        String city = ContentReadService.requireCity(cityCode);
        ContentVideoFeed feed = feedCode == null || feedCode.isBlank()
                ? ContentVideoFeed.RECOMMEND
                : ContentReadService.parse(ContentVideoFeed.class, feedCode);
        int limit = size == null ? DEFAULT_STREAM_SIZE : size;
        if (limit < 1 || limit > MAX_STREAM_SIZE) {
            throw new IllegalArgumentException("size is invalid");
        }
        UUID viewerId = viewerId(viewer);
        if (feed == ContentVideoFeed.FOLLOWING && viewerId == null) {
            throw new ContentException(Error.UNAUTHORIZED);
        }
        UUID followerId = feed == ContentVideoFeed.FOLLOWING ? viewerId : null;
        OffsetDateTime now = now();

        StreamKey after = null;
        boolean inclusive = false;
        if (cursor != null && !cursor.isBlank()) {
            after = StreamKey.decode(cursor);
        } else if (startVideoId != null) {
            VideoRow start = mapper.findVideo(startVideoId, city, now, viewerId);
            if (start == null || (followerId != null && !start.followingAuthor())) {
                throw new ContentNotFoundException();
            }
            after = new StreamKey(start.videoRank(), start.publishedAt(), start.id());
            inclusive = true;
        }

        List<VideoRow> rows = mapper.findStream(city, now, viewerId, followerId,
                after == null ? null : after.rank(),
                after == null ? null : after.publishedAt(),
                after == null ? null : after.id(),
                inclusive, limit + 1);
        boolean more = rows.size() > limit;
        List<VideoRow> page = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            VideoRow last = page.get(page.size() - 1);
            next = new StreamKey(last.videoRank(), last.publishedAt(), last.id()).encode();
        }
        return new ContentVideoPageView(city, feed, page.stream().map(this::view).toList(), next);
    }

    @Transactional(readOnly = true)
    public ContentCommentPageView comments(UUID videoId, int page, int size, ContentActor viewer) {
        if (page < 1 || size < 1 || size > MAX_COMMENT_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        requirePublished(videoId);
        long total = mapper.countVisibleComments(videoId);
        UUID viewerId = viewerId(viewer);
        List<ContentCommentView> items = mapper.findVisibleComments(videoId, size, Math.multiplyExact(page - 1, size))
                .stream()
                .map(row -> commentView(row, viewerId))
                .toList();
        return new ContentCommentPageView(page, size, total, (long) page * size < total, items);
    }

    // ---- 点赞、关注 ----

    @Transactional
    public ContentVideoLikeView like(UUID videoId, ContentActor actor) {
        UUID accountId = requireAccount(actor);
        requirePublished(videoId);
        mapper.insertLike(videoId, accountId, now());
        return new ContentVideoLikeView(videoId, true, mapper.countLikes(videoId));
    }

    @Transactional
    public ContentVideoLikeView unlike(UUID videoId, ContentActor actor) {
        UUID accountId = requireAccount(actor);
        requirePublished(videoId);
        mapper.deleteLike(videoId, accountId);
        return new ContentVideoLikeView(videoId, false, mapper.countLikes(videoId));
    }

    @Transactional
    public ContentFollowView follow(UUID organizationId, ContentActor actor) {
        UUID accountId = requireAccount(actor);
        if (organizationId == null) {
            throw new IllegalArgumentException("organization id is required");
        }
        OffsetDateTime now = now();
        if (!mapper.isPublishingOrganization(organizationId, now)) {
            throw new ContentNotFoundException();
        }
        mapper.insertFollow(accountId, organizationId, now);
        return new ContentFollowView(organizationId, true);
    }

    @Transactional
    public ContentFollowView unfollow(UUID organizationId, ContentActor actor) {
        UUID accountId = requireAccount(actor);
        if (organizationId == null) {
            throw new IllegalArgumentException("organization id is required");
        }
        mapper.deleteFollow(accountId, organizationId);
        return new ContentFollowView(organizationId, false);
    }

    // ---- 评论 ----

    /**
     * 发一条评论。同一个幂等键带同样的内容重放返回第一次那条（不算进限频，也不再过滤）；内容不同是 409。
     * 发布机构的操作员发的显示机构名并标“作者”，其余显示打码后的显示名。
     */
    @Transactional
    public ContentCommentPosted comment(UUID videoId, ContentActor actor, UUID idempotencyKey, String text) {
        UUID accountId = requireAccount(actor);
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        String body = commentText(text);
        VideoTargetRow video = requirePublished(videoId);
        String hash = hash(videoId + "|" + body);

        mapper.lockCommenter(accountId);
        CommentRow existing = mapper.findCommentByKey(accountId, idempotencyKey);
        if (existing != null) {
            if (!hash.equals(existing.requestHash().trim())) {
                throw new ContentException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new ContentCommentPosted(commentView(existing, accountId), false);
        }
        if (filter.blocks(body)) {
            throw new ContentException(Error.COMMENT_BLOCKED);
        }
        OffsetDateTime now = now();
        if (mapper.countCommentsSince(accountId, now.minus(COMMENT_WINDOW)) >= COMMENT_LIMIT) {
            throw new ContentException(Error.COMMENT_TOO_FREQUENT);
        }
        boolean byAuthor = actor.providerOrganizationId() != null
                && actor.providerOrganizationId().equals(video.authorOrgId());
        String name = byAuthor ? video.authorName() : maskedName(profiles.displayName(accountId).orElse(null));
        CommentRow row = mapper.insertComment(videoId, accountId, name, byAuthor, body, idempotencyKey, hash, now);
        return new ContentCommentPosted(commentView(row, accountId), true);
    }

    /**
     * 举报一条还在显示的评论。同一账号对同一条只记一次：第一次记进客服反馈（类型“内容有误”）并留一条举报记录；
     * 再举报返回第一次那条。
     */
    @Transactional
    public ContentCommentReported report(UUID commentId, ContentActor actor, String reasonCode) {
        UUID accountId = requireAccount(actor);
        if (commentId == null) {
            throw new IllegalArgumentException("comment id is required");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        ContentCommentReportReason reason = ContentReadService.parse(ContentCommentReportReason.class, reasonCode);
        ReportRow existing = mapper.findReport(commentId, accountId);
        if (existing != null) {
            return new ContentCommentReported(reportView(existing), false);
        }
        OffsetDateTime now = now();
        CommentTargetRow target = mapper.findCommentTarget(commentId, now);
        if (target == null || !target.videoPublished() || !"VISIBLE".equals(target.status())) {
            throw new ContentNotFoundException();
        }
        UUID feedbackId = reports.record(accountId, reportKey(commentId),
                "举报视频评论（" + reason.label() + "）：视频《" + target.videoTitle() + "》，评论 " + commentId + "。");
        ReportRow inserted = mapper.insertReport(commentId, accountId, reason.name(), feedbackId, now);
        if (inserted == null) {
            // 同一账号同时点了两次，另一次先写了。
            return new ContentCommentReported(reportView(mapper.findReport(commentId, accountId)), false);
        }
        return new ContentCommentReported(reportView(inserted), true);
    }

    /** 受权接口：隐藏一条评论。不是本机构视频下的评论当作不存在；已经隐藏的返回原来的时间。 */
    @Transactional
    public ContentCommentHiddenView hide(UUID commentId, ContentModerator moderator) {
        if (moderator == null || moderator.accountId() == null) {
            throw new ContentException(Error.FORBIDDEN);
        }
        if (commentId == null) {
            throw new IllegalArgumentException("comment id is required");
        }
        CommentTargetRow target = mapper.findCommentTarget(commentId, now());
        if (target == null) {
            throw new ContentNotFoundException();
        }
        boolean allowed = moderator.platformSupport()
                || (moderator.providerOrganizationId() != null
                    && moderator.providerOrganizationId().equals(target.videoAuthorOrgId()));
        if (!allowed) {
            throw new ContentNotFoundException();
        }
        if ("HIDDEN".equals(target.status())) {
            return new ContentCommentHiddenView(commentId, true, target.hiddenAt());
        }
        OffsetDateTime hiddenAt = mapper.hideComment(commentId, moderator.accountId(), now());
        if (hiddenAt == null) {
            // 另一个人同时隐藏了。
            hiddenAt = Objects.requireNonNull(mapper.findCommentTarget(commentId, now())).hiddenAt();
        }
        return new ContentCommentHiddenView(commentId, true, hiddenAt);
    }

    // ---- 内部 ----

    private ContentVideoView view(VideoRow row) {
        return new ContentVideoView(row.id(), row.title(),
                new ContentAuthorView(ContentAuthorKind.valueOf(row.authorKind()), row.authorOrgId(), row.authorName()),
                row.sponsored(), row.cityCode(), ContentReadService.secureMedia(row.coverUrl(), row.title()),
                row.sample(), row.publishedAt(), row.videoDurationSeconds(),
                ContentVideoOrientation.valueOf(row.videoOrientation()), ContentReadService.secureUrl(row.videoUrl()),
                ContentReadService.blankToNull(row.videoCaption()), tags(row.videoTags()),
                contentMapper.findRefs(row.id()).stream()
                        .map(ref -> new ContentRefView(ContentRefType.valueOf(ref.refType()), ref.refId(), ref.title()))
                        .toList(),
                row.likeCount(), row.commentCount(), row.liked(), row.followingAuthor());
    }

    /** 话题按空白分开，去掉开头的 #，空的不要，每个最多 20 字，最多 10 个。 */
    static List<String> tags(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String part : stored.trim().split("\\s+")) {
            String tag = part.replaceFirst("^#+", "").trim();
            if (!tag.isEmpty() && tag.codePointCount(0, tag.length()) <= MAX_TAG_LENGTH && !tags.contains(tag)) {
                tags.add(tag);
            }
            if (tags.size() == MAX_TAGS) {
                break;
            }
        }
        return List.copyOf(tags);
    }

    /** 显示名只留第一个字，后面两个星号；没有显示名的叫“用户”。 */
    static String maskedName(String displayName) {
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty()) {
            return ANONYMOUS_NAME;
        }
        return name.substring(0, name.offsetByCodePoints(0, 1)) + "**";
    }

    private static ContentCommentView commentView(CommentRow row, UUID viewerId) {
        return new ContentCommentView(row.id(), row.videoId(), row.displayName(), row.byAuthor(),
                viewerId != null && viewerId.equals(row.accountId()), row.body(), row.createdAt());
    }

    private static ContentCommentReportView reportView(ReportRow row) {
        return new ContentCommentReportView(row.commentId(), ContentCommentReportReason.valueOf(row.reason()),
                row.createdAt());
    }

    /** 同一账号举报同一条评论，记进客服反馈时用同一个幂等键。 */
    static UUID reportKey(UUID commentId) {
        return UUID.nameUUIDFromBytes(("content-comment-report:" + commentId).getBytes(StandardCharsets.UTF_8));
    }

    private static String commentText(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_COMMENT_LENGTH
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("comment is invalid");
        }
        return trimmed;
    }

    private VideoTargetRow requirePublished(UUID videoId) {
        if (videoId == null) {
            throw new IllegalArgumentException("video id is required");
        }
        VideoTargetRow video = mapper.findPublishedVideo(videoId, now());
        if (video == null) {
            throw new ContentNotFoundException();
        }
        return video;
    }

    private static UUID requireAccount(ContentActor actor) {
        if (actor == null || actor.accountId() == null) {
            throw new ContentException(Error.UNAUTHORIZED);
        }
        return actor.accountId();
    }

    private static UUID viewerId(ContentActor viewer) {
        return viewer == null ? null : viewer.accountId();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** 视频流的位置：运营排序、发布时间、ID。游标是它的 Base64URL 文本，页面原样传回。 */
    record StreamKey(int rank, OffsetDateTime publishedAt, UUID id) {

        String encode() {
            String plain = rank + "|" + publishedAt.toInstant() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        }

        static StreamKey decode(String cursor) {
            if (cursor.length() > MAX_CURSOR_LENGTH) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            try {
                String plain = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
                List<String> parts = Arrays.asList(plain.split("\\|", -1));
                if (parts.size() != 3) {
                    throw new IllegalArgumentException("cursor is invalid");
                }
                return new StreamKey(Integer.parseInt(parts.get(0)),
                        OffsetDateTime.ofInstant(Instant.parse(parts.get(1)), ZoneOffset.UTC),
                        UUID.fromString(parts.get(2)));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("cursor is invalid", invalid);
            }
        }
    }
}
