package com.lowaltitude.inspection.content.application;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.media.DevelopmentMediaPath;

import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper.ArticleRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper.BlockItemRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper.BlockRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper.TopicItemRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper.TopicRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发现页的图文内容和专题（H1，docs/redesign/03-App实现任务计划.md §27，契约 contracts/openapi-content.yaml）。公开只读。
 *
 * <ul>
 *   <li>按城市展示：这个城市的内容 + 不分城市的知识；只给已发布、发布时间已到的。</li>
 *   <li>推荐频道两类都有，新发布的在前；第一页另带这个城市今天正在进行的专题。</li>
 *   <li>“内容中提到”和专题挂的活动、场馆只给类型和稳定 ID（加录入时的名称），页面到各自的模块读真实数据；
 *       专题挂的图文在本模块里按城市可见性过滤后给出卡片。</li>
 * </ul>
 */
public class ContentReadService {

    static final int MAX_PAGE_SIZE = 50;
    /** R6-3 相关内容：不传 limit 时给 3 篇，最多 6 篇。 */
    static final int DEFAULT_RELATED_LIMIT = 3;
    static final int MAX_RELATED_LIMIT = 6;
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";

    private final ContentMapper mapper;
    private final Clock clock;

    public ContentReadService(ContentMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    ContentReadService(ContentMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ContentFeedView feed(String cityCode, String channelCode, int page, int size) {
        String city = requireCity(cityCode);
        ContentFeedChannel channel = channelCode == null || channelCode.isBlank()
                ? ContentFeedChannel.RECOMMEND
                : parse(ContentFeedChannel.class, channelCode);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        String kind = channel == ContentFeedChannel.RECOMMEND ? null : channel.name();
        OffsetDateTime now = now();
        long total = mapper.countFeed(city, kind, now);
        int offset = Math.multiplyExact(page - 1, size);
        // 1.2.0：视频频道按视频流的顺序（运营排序，再按发布时间），其余频道按发布时间。
        List<ArticleRow> rows = channel == ContentFeedChannel.VIDEO
                ? mapper.findVideoFeedPage(city, kind, now, size, offset)
                : mapper.findFeedPage(city, kind, now, size, offset);
        List<ContentArticleCardView> items = rows.stream()
                .map(ContentReadService::card)
                .toList();
        List<ContentTopicCardView> topics = channel == ContentFeedChannel.RECOMMEND && page == 1
                ? mapper.findCurrentTopics(city, today()).stream().map(ContentReadService::topicCard).toList()
                : List.of();
        return new ContentFeedView(channel, city, page, size, total, (long) page * size < total, topics, items);
    }

    // “没有这篇”是正常的回答，不让调用方的事务跟着回滚：收藏（S5-6）先按图文读、读不到再按视频读，
    // 都在收藏自己的事务里。
    @Transactional(readOnly = true, noRollbackFor = ContentNotFoundException.class)
    public ContentArticleView article(UUID articleId, String cityCode) {
        String city = requireCity(cityCode);
        if (articleId == null) {
            throw new IllegalArgumentException("article id is required");
        }
        ArticleRow row = mapper.findArticle(articleId, city, now());
        if (row == null) {
            throw new ContentNotFoundException();
        }
        Map<UUID, List<ContentBlockItemView>> items = new LinkedHashMap<>();
        for (BlockItemRow item : mapper.findBlockItems(articleId)) {
            items.computeIfAbsent(item.blockId(), id -> new ArrayList<>())
                    .add(new ContentBlockItemView(item.title(), blankToNull(item.text())));
        }
        List<ContentBlockView> blocks = new ArrayList<>();
        for (BlockRow block : mapper.findBlocks(articleId)) {
            ContentBlockType type = ContentBlockType.valueOf(block.blockType());
            // 1.3.0 配图：地址只给受控 HTTPS 的，没有时 url 为 null（页面决定显示不显示）。
            ContentBlockImageView image = type == ContentBlockType.IMAGE
                    ? new ContentBlockImageView(secureUrl(block.imageUrl()), block.imageAlt(),
                            blankToNull(block.imageCaption()))
                    : null;
            blocks.add(new ContentBlockView(type, block.text(), List.copyOf(items.getOrDefault(block.id(), List.of())),
                    image));
        }
        ContentArticleCardView card = card(row);
        return new ContentArticleView(card.articleId(), card.kind(), card.title(), card.author(), card.sponsored(),
                card.cityCode(), card.coverImage(), card.sample(), card.publishedAt(), blocks,
                mapper.findRefs(articleId).stream()
                        .map(ref -> new ContentRefView(ContentRefType.valueOf(ref.refType()), ref.refId(), ref.title()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public ContentTopicView topic(UUID topicId, String cityCode) {
        String city = requireCity(cityCode);
        if (topicId == null) {
            throw new IllegalArgumentException("topic id is required");
        }
        TopicRow row = mapper.findTopic(topicId, city);
        if (row == null) {
            throw new ContentNotFoundException();
        }
        List<UUID> activities = new ArrayList<>();
        List<UUID> venues = new ArrayList<>();
        List<ContentArticleCardView> articles = new ArrayList<>();
        List<ContentTopicMerchantItemView> merchantItems = new ArrayList<>();
        OffsetDateTime now = now();
        for (TopicItemRow item : mapper.findTopicItems(topicId)) {
            switch (item.itemType()) {
                case "ACTIVITY" -> activities.add(item.itemId());
                case "VENUE" -> venues.add(item.itemId());
                case "ARTICLE" -> {
                    // 挂的图文没发布、下线了或不在这个城市展示时不给出。
                    ArticleRow article = mapper.findArticle(item.itemId(), city, now);
                    if (article != null) {
                        articles.add(card(article));
                    }
                }
                // 1.3.0 商家内容：商品是否还在本城卖由页面到商城读，读不到就不显示。
                case "PRODUCT" -> merchantItems.add(new ContentTopicMerchantItemView(item.itemId(),
                        new ContentAuthorView(ContentAuthorKind.MERCHANT, item.merchantOrgId(), item.merchantName())));
                default -> throw new IllegalStateException("unknown topic item type " + item.itemType());
            }
        }
        ContentTopicCardView card = topicCard(row);
        return new ContentTopicView(card.topicId(), card.title(), card.subtitle(), card.publisher(), row.cityCode(),
                card.startsOn(), card.endsOn(), card.coverImage(), card.sample(), List.copyOf(activities),
                List.copyOf(venues), List.copyOf(articles), List.copyOf(merchantItems));
    }

    /**
     * R6-3 相关内容：“内容中提到”了这个对象（商品、活动、场馆、服务）的图文，只给这个城市能看到的、已发布的，
     * 新发布的在前。对象是否存在由它自己的模块判断：没人提到时 items 为空，不是 404。
     */
    @Transactional(readOnly = true)
    public ContentRelatedView related(String refTypeCode, UUID refId, String cityCode, Integer limit) {
        if (refTypeCode == null || refTypeCode.isBlank()) {
            throw new IllegalArgumentException("ref type is required");
        }
        ContentRefType refType = parse(ContentRefType.class, refTypeCode);
        if (refId == null) {
            throw new IllegalArgumentException("ref id is required");
        }
        String city = requireCity(cityCode);
        int size = limit == null ? DEFAULT_RELATED_LIMIT : limit;
        if (size < 1 || size > MAX_RELATED_LIMIT) {
            throw new IllegalArgumentException("limit is invalid");
        }
        List<ContentArticleCardView> items = mapper.findMentioning(refType.name(), refId, city, now(), size).stream()
                .map(ContentReadService::card)
                .toList();
        return new ContentRelatedView(refType, refId, items);
    }

    // ---- 内部 ----

    private static ContentArticleCardView card(ArticleRow row) {
        ContentArticleKind kind = ContentArticleKind.valueOf(row.kind());
        ContentVideoSummaryView video = kind == ContentArticleKind.VIDEO
                ? new ContentVideoSummaryView(row.videoDurationSeconds(),
                        ContentVideoOrientation.valueOf(row.videoOrientation()))
                : null;
        return new ContentArticleCardView(row.id(), kind, row.title(),
                new ContentAuthorView(ContentAuthorKind.valueOf(row.authorKind()), row.authorOrgId(), row.authorName()),
                video, row.sponsored(), row.cityCode(), secureMedia(row.coverUrl(), row.title()), row.sample(),
                row.publishedAt());
    }

    private static ContentTopicCardView topicCard(TopicRow row) {
        return new ContentTopicCardView(row.id(), row.title(), blankToNull(row.subtitle()),
                new ContentAuthorView(ContentAuthorKind.valueOf(row.publisherKind()), row.publisherOrgId(),
                        row.publisherName()),
                row.startsOn(), row.endsOn(), secureMedia(row.coverUrl(), row.title()), row.sample());
    }

    /** 只给带主机名的 HTTPS 图片地址，其余当没有封面。 */
    static ContentMediaView secureMedia(String url, String alt) {
        String secure = secureUrl(url);
        return secure == null ? null : new ContentMediaView(secure, alt);
    }

    /** 只认带主机名的 HTTPS 地址（图片和视频的播放地址），其余为 null。 */
    static String secureUrl(String url) {
        if (DevelopmentMediaPath.matches(url)) {
            return url;
        }
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return uri.toString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static String requireCity(String cityCode) {
        String city = cityCode == null ? "" : cityCode.trim();
        if (!city.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return city;
    }

    static <E extends Enum<E>> E parse(Class<E> type, String code) {
        try {
            return Enum.valueOf(type, code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("query value is invalid", unknown);
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BEIJING);
    }
}
