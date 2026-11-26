package com.lowaltitude.inspection.content.application.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsArticleKind;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsAuthorKind;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsBlockType;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsContentAction;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsContentStatus;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsContentSubject;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsRefType;
import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes.OpsVideoOrientation;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.ArticleDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.AuthorDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.BlockDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.BlockItemDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.MerchantItemDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.Operator;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsArticleView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsBlockImageView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsBlockItemView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsBlockView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsContentAuthorView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsContentPageView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsContentSummaryView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsRefView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsTopicMerchantItemView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsTopicPageView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsTopicSummaryView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsTopicView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsVideoView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.RefDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.TopicDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.VideoDraft;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper.BlockItemRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper.BlockRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentMapper.TopicItemRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentOpsMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentOpsMapper.ArticleWrite;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentOpsMapper.OpsArticleRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentOpsMapper.OpsTopicRow;
import com.lowaltitude.inspection.content.infrastructure.persistence.ContentOpsMapper.TopicWrite;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“发现内容”（S3-1，契约 contracts/openapi-ops-content.yaml 1.0.0）：图文（案例、知识）、视频、专题的录入、
 * 上线和下线。只给平台运营（角色由控制器检查）。
 *
 * <ul>
 *   <li>新建一律是草稿；改内容、上线、下线都带页面读到的版本号，不一致 409。</li>
 *   <li>上线前校验必填（缺了 422 OPS_PUBLISH_INCOMPLETE，details.missing 是缺的字段键）；已上线的改完也要齐。</li>
 *   <li>上线时定发布时间（不传为现在，可以定在以后：到了时间 App 才看得到）；下线保留发布时间。</li>
 *   <li>作者、发布方、发布商家经端口确认是在用的服务商类机构；城市取平台城市目录；专题的商家内容只挂本城在卖的商品。
 *       “内容中提到”和专题挂的活动、场馆只校验 ID 格式（页面按 ID 到各自的模块读，读不到的不显示）。</li>
 *   <li>每次新建、修改、上线、下线记一条改动记录（操作人显示名快照）。</li>
 * </ul>
 */
public class ContentOpsService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_KEYWORD = 20;
    public static final int CHANGE_LIMIT = 50;
    public static final int MAX_TITLE = 60;
    public static final int MAX_TOPIC_TITLE = 40;
    public static final int MAX_SUBTITLE = 60;
    public static final int MAX_AUTHOR_NAME = 160;
    public static final int MAX_BLOCKS = 40;
    public static final int MAX_BLOCK_ITEMS = 20;
    public static final int MAX_BLOCK_TEXT = 500;
    public static final int MAX_HEADING = 60;
    public static final int MAX_ITEM_TITLE = 60;
    public static final int MAX_ITEM_TEXT = 200;
    public static final int MAX_IMAGE_ALT = 60;
    public static final int MAX_IMAGE_CAPTION = 60;
    public static final int MAX_REFS = 10;
    public static final int MAX_REF_TITLE = 60;
    public static final int MAX_VIDEO_SECONDS = 3600;
    public static final int MAX_VIDEO_CAPTION = 200;
    public static final int MAX_TAGS = 5;
    public static final int MAX_TAG = 20;
    public static final int MAX_RANK = 9999;
    public static final int MAX_TOPIC_ACTIVITIES = 20;
    public static final int MAX_TOPIC_VENUES = 10;
    public static final int MAX_TOPIC_ARTICLES = 20;
    public static final int MAX_TOPIC_MERCHANT_ITEMS = 6;
    /** 定时发布最远一年；早于现在 5 分钟以上的发布时间不收（各机器的钟有一点偏差）。 */
    static final Duration MAX_SCHEDULE = Duration.ofDays(365);
    static final Duration PAST_TOLERANCE = Duration.ofMinutes(5);

    // 上线前必填项的字段键（契约 OpsContentMissingField）。
    public static final String MISSING_COVER = "coverUrl";
    public static final String MISSING_BLOCKS = "blocks";
    public static final String MISSING_IMAGE = "imageUrl";
    public static final String MISSING_AUTHOR = "author";
    public static final String MISSING_PLAY_URL = "playUrl";
    public static final String MISSING_ITEMS = "items";
    public static final String MISSING_PUBLISHER = "publisher";

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SCHEDULE = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String PROVIDER_TYPE = "PROVIDER";

    private final ContentOpsMapper mapper;
    private final ContentMapper reads;
    private final ContentOpsPorts.Organizations organizations;
    private final ContentOpsPorts.Cities cities;
    private final ContentOpsPorts.Products products;
    private final Clock clock;

    public ContentOpsService(ContentOpsMapper mapper, ContentMapper reads, ContentOpsPorts.Organizations organizations,
                             ContentOpsPorts.Cities cities, ContentOpsPorts.Products products) {
        this(mapper, reads, organizations, cities, products, Clock.systemUTC());
    }

    ContentOpsService(ContentOpsMapper mapper, ContentMapper reads, ContentOpsPorts.Organizations organizations,
                      ContentOpsPorts.Cities cities, ContentOpsPorts.Products products, Clock clock) {
        this.mapper = mapper;
        this.reads = reads;
        this.organizations = organizations;
        this.cities = cities;
        this.products = products;
        this.clock = clock;
    }

    // ==== 图文 ====

    @Transactional(readOnly = true)
    public OpsContentPageView articles(String keyword, String kind, String cityCode, String status, int page,
                                       int size) {
        OpsArticleKind parsed = OpsText.optionalEnum(OpsArticleKind.class, kind, "kind");
        return contentPage(false, parsed == null ? null : parsed.name(), keyword, cityCode, status, page, size);
    }

    @Transactional(readOnly = true)
    public OpsArticleView article(UUID articleId) {
        return articleView(requireArticle(mapper.findArticle(required(articleId)), false));
    }

    @Transactional
    public OpsArticleView createArticle(ArticleDraft draft, Operator operator) {
        Operator who = operator(operator);
        ValidArticle valid = validArticle(draft, null);
        UUID id = UUID.randomUUID();
        OffsetDateTime at = now();
        mapper.insertArticle(valid.write(id), at);
        writeBlocks(id, valid.blocks());
        writeRefs(id, valid.refs());
        record(OpsContentSubject.ARTICLE, id, OpsContentAction.CREATE, "新建草稿：" + valid.title(), who, at);
        return article(id);
    }

    @Transactional
    public OpsArticleView updateArticle(UUID articleId, ArticleDraft draft, Integer version, Operator operator) {
        Operator who = operator(operator);
        int expected = version(version);
        OpsArticleRow current = requireArticle(mapper.lockArticle(required(articleId)), false);
        checkVersion(current.version(), expected);
        ValidArticle valid = validArticle(draft, current);
        if (OpsContentStatus.valueOf(current.status()) == OpsContentStatus.PUBLISHED) {
            requireComplete(articleMissing(valid.coverUrl(), valid.blocks(), valid.author()));
        }
        OffsetDateTime at = now();
        if (mapper.updateArticle(valid.write(current.id()), expected, at) != 1) {
            throw new OpsException(ContentOpsError.VERSION_CONFLICT);
        }
        mapper.deleteBlocks(current.id());
        writeBlocks(current.id(), valid.blocks());
        mapper.deleteRefs(current.id());
        writeRefs(current.id(), valid.refs());
        record(OpsContentSubject.ARTICLE, current.id(), OpsContentAction.UPDATE, "修改：" + valid.title(), who, at);
        return article(current.id());
    }

    @Transactional
    public OpsArticleView publishArticle(UUID articleId, Integer version, OffsetDateTime publishAt, Operator operator) {
        Operator who = operator(operator);
        int expected = version(version);
        OpsArticleRow current = requireArticle(mapper.lockArticle(required(articleId)), false);
        checkVersion(current.version(), expected);
        requireUnpublished(current.status());
        requireComplete(articleView(current).publishMissing());
        publish(OpsContentSubject.ARTICLE, current, expected, publishAt, who);
        return article(current.id());
    }

    @Transactional
    public OpsArticleView offlineArticle(UUID articleId, Integer version, Operator operator) {
        Operator who = operator(operator);
        int expected = version(version);
        OpsArticleRow current = requireArticle(mapper.lockArticle(required(articleId)), false);
        offline(OpsContentSubject.ARTICLE, current, expected, who);
        return article(current.id());
    }

    @Transactional(readOnly = true)
    public List<OpsChangeView> articleChanges(UUID articleId) {
        OpsArticleRow row = requireArticle(mapper.findArticle(required(articleId)), false);
        return changes(OpsContentSubject.ARTICLE, row.id());
    }

    // ==== 视频 ====

    @Transactional(readOnly = true)
    public OpsContentPageView videos(String keyword, String cityCode, String status, int page, int size) {
        return contentPage(true, null, keyword, cityCode, status, page, size);
    }

    @Transactional(readOnly = true)
    public OpsVideoView video(UUID videoId) {
        return videoView(requireArticle(mapper.findArticle(required(videoId)), true));
    }

    @Transactional
    public OpsVideoView createVideo(VideoDraft draft, Operator operator) {
        Operator who = operator(operator);
        ValidVideo valid = validVideo(draft, null);
        UUID id = UUID.randomUUID();
        OffsetDateTime at = now();
        mapper.insertArticle(valid.write(id), at);
        writeRefs(id, valid.refs());
        record(OpsContentSubject.VIDEO, id, OpsContentAction.CREATE, "新建草稿：" + valid.title(), who, at);
        return video(id);
    }

    @Transactional
    public OpsVideoView updateVideo(UUID videoId, VideoDraft draft, Integer version, Operator operator) {
        Operator who = operator(operator);
        int expected = version(version);
        OpsArticleRow current = requireArticle(mapper.lockArticle(required(videoId)), true);
        checkVersion(current.version(), expected);
        ValidVideo valid = validVideo(draft, current);
        if (OpsContentStatus.valueOf(current.status()) == OpsContentStatus.PUBLISHED) {
            requireComplete(videoMissing(valid.coverUrl(), valid.playUrl(), valid.author()));
        }
        OffsetDateTime at = now();
        if (mapper.updateArticle(valid.write(current.id()), expected, at) != 1) {
            throw new OpsException(ContentOpsError.VERSION_CONFLICT);
        }
        mapper.deleteRefs(current.id());
        writeRefs(current.id(), valid.refs());
        record(OpsContentSubject.VIDEO, current.id(), OpsContentAction.UPDATE, "修改：" + valid.title(), who, at);
        return video(current.id());
    }

    @Transactional
    public OpsVideoView publishVideo(UUID videoId, Integer version, OffsetDateTime publishAt, Operator operator) {
        Operator who = operator(operator);
        int expected = version(version);
        OpsArticleRow current = requireArticle(mapper.lockArticle(required(videoId)), true);
        checkVersion(current.version(), expected);
        requireUnpublished(current.status());
        requireComplete(videoView(current).publishMissing());
        publish(OpsContentSubject.VIDEO, current, expected, publishAt, who);
        return video(current.id());
    }

    @Transactional
    public OpsVideoView offlineVideo(UUID videoId, Integer version, Operator operator) {
        Operator who = operator(operator);
        int expected = version(version);
        OpsArticleRow current = requireArticle(mapper.lockArticle(required(videoId)), true);
        offline(OpsContentSubject.VIDEO, current, expected, who);
        return video(current.id());
    }

    @Transactional(readOnly = true)
    public List<OpsChangeView> videoChanges(UUID videoId) {
        OpsArticleRow row = requireArticle(mapper.findArticle(required(videoId)), true);
        return changes(OpsContentSubject.VIDEO, row.id());
    }

    // ==== 专题 ====

    @Transactional(readOnly = true)
    public OpsTopicPageView topics(String keyword, String cityCode, String status, int page, int size) {
        OpsText.page(page, size, MAX_PAGE_SIZE);
        String key = OpsText.keyword(keyword, MAX_KEYWORD);
        String city = optionalCityFilter(cityCode);
        String state = statusFilter(status);
        long total = mapper.countTopics(city, state, key);
        List<OpsTopicSummaryView> items = mapper.findTopics(city, state, key, size, Math.multiplyExact(page - 1, size))
                .stream()
                .map(row -> new OpsTopicSummaryView(row.id(), row.title(), row.publisherName(), row.cityCode(),
                        row.startsOn().toString(), row.endsOn().toString(), row.sample(),
                        OpsContentStatus.valueOf(row.status()), row.version(), row.updatedAt()))
                .toList();
        return new OpsTopicPageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public OpsTopicView topic(UUID topicId) {
        return topicView(requireTopic(mapper.findTopic(required(topicId))));
    }

    @Transactional
    public OpsTopicView createTopic(TopicDraft draft, Operator operator) {
        Operator who = operator(operator);
        ValidTopic valid = validTopic(draft, null);
        UUID id = UUID.randomUUID();
        OffsetDateTime at = now();
        mapper.insertTopic(valid.write(id), at);
        writeTopicItems(id, valid);
        record(OpsContentSubject.TOPIC, id, OpsContentAction.CREATE, "新建草稿：" + valid.title(), who, at);
        return topic(id);
    }

    @Transactional
    public OpsTopicView updateTopic(UUID topicId, TopicDraft draft, Integer version, Operator operator) {
        Operator who = operator(operator);
        int expected = version(version);
        OpsTopicRow current = requireTopic(mapper.lockTopic(required(topicId)));
        checkVersion(current.version(), expected);
        ValidTopic valid = validTopic(draft, current);
        if (OpsContentStatus.valueOf(current.status()) == OpsContentStatus.PUBLISHED) {
            requireComplete(topicMissing(valid.coverUrl(), valid.itemCount(), valid.publisher()));
        }
        OffsetDateTime at = now();
        if (mapper.updateTopic(valid.write(current.id()), expected, at) != 1) {
            throw new OpsException(ContentOpsError.VERSION_CONFLICT);
        }
        mapper.deleteTopicItems(current.id());
        writeTopicItems(current.id(), valid);
        record(OpsContentSubject.TOPIC, current.id(), OpsContentAction.UPDATE, "修改：" + valid.title(), who, at);
        return topic(current.id());
    }

    @Transactional
    public OpsTopicView publishTopic(UUID topicId, Integer version, Operator operator) {
        Operator who = operator(operator);
        int expected = version(version);
        OpsTopicRow current = requireTopic(mapper.lockTopic(required(topicId)));
        checkVersion(current.version(), expected);
        requireUnpublished(current.status());
        requireComplete(topicView(current).publishMissing());
        OffsetDateTime at = now();
        if (mapper.updateTopicStatus(current.id(), OpsContentStatus.PUBLISHED.name(), expected, at) != 1) {
            throw new OpsException(ContentOpsError.VERSION_CONFLICT);
        }
        record(OpsContentSubject.TOPIC, current.id(), OpsContentAction.PUBLISH,
                "上线，" + current.startsOn() + " 至 " + current.endsOn() + " 在本城推荐频道展示", who, at);
        return topic(current.id());
    }

    @Transactional
    public OpsTopicView offlineTopic(UUID topicId, Integer version, Operator operator) {
        Operator who = operator(operator);
        int expected = version(version);
        OpsTopicRow current = requireTopic(mapper.lockTopic(required(topicId)));
        checkVersion(current.version(), expected);
        if (OpsContentStatus.valueOf(current.status()) != OpsContentStatus.PUBLISHED) {
            throw new OpsException(ContentOpsError.STATUS_CONFLICT);
        }
        OffsetDateTime at = now();
        if (mapper.updateTopicStatus(current.id(), OpsContentStatus.OFFLINE.name(), expected, at) != 1) {
            throw new OpsException(ContentOpsError.VERSION_CONFLICT);
        }
        record(OpsContentSubject.TOPIC, current.id(), OpsContentAction.OFFLINE, "下线", who, at);
        return topic(current.id());
    }

    @Transactional(readOnly = true)
    public List<OpsChangeView> topicChanges(UUID topicId) {
        OpsTopicRow row = requireTopic(mapper.findTopic(required(topicId)));
        return changes(OpsContentSubject.TOPIC, row.id());
    }

    // ==== 读出来的形状 ====

    private OpsContentPageView contentPage(boolean video, String kind, String keyword, String cityCode, String status,
                                           int page, int size) {
        OpsText.page(page, size, MAX_PAGE_SIZE);
        String key = OpsText.keyword(keyword, MAX_KEYWORD);
        String city = optionalCityFilter(cityCode);
        String state = statusFilter(status);
        long total = mapper.countArticles(video, kind, city, state, key);
        List<OpsContentSummaryView> items = mapper.findArticles(video, kind, city, state, key, size,
                        Math.multiplyExact(page - 1, size)).stream()
                .map(row -> new OpsContentSummaryView(row.id(), row.kind(), row.title(), row.authorName(),
                        row.sponsored(), row.cityCode(), row.coverUrl(), row.sample(),
                        OpsContentStatus.valueOf(row.status()), row.publishedAt(), row.version(), row.updatedAt()))
                .toList();
        return new OpsContentPageView(page, size, total, (long) page * size < total, items);
    }

    private OpsArticleView articleView(OpsArticleRow row) {
        Map<UUID, List<OpsBlockItemView>> items = new LinkedHashMap<>();
        for (BlockItemRow item : reads.findBlockItems(row.id())) {
            items.computeIfAbsent(item.blockId(), id -> new ArrayList<>())
                    .add(new OpsBlockItemView(item.title(), item.text()));
        }
        List<OpsBlockView> blocks = new ArrayList<>();
        for (BlockRow block : reads.findBlocks(row.id())) {
            OpsBlockType type = OpsBlockType.valueOf(block.blockType());
            blocks.add(new OpsBlockView(type, block.text(), List.copyOf(items.getOrDefault(block.id(), List.of())),
                    type == OpsBlockType.IMAGE
                            ? new OpsBlockImageView(block.imageUrl(), block.imageAlt(), block.imageCaption())
                            : null));
        }
        OpsContentAuthorView author = author(row.authorKind(), row.authorOrgId(), row.authorName());
        return new OpsArticleView(row.id(), OpsArticleKind.valueOf(row.kind()), row.title(), author, row.sponsored(),
                row.cityCode(), row.coverUrl(), row.sample(), OpsContentStatus.valueOf(row.status()),
                row.publishedAt(), List.copyOf(blocks), refs(row.id()),
                articleMissing(row.coverUrl(), blocks, author), row.version(), row.updatedAt());
    }

    private OpsVideoView videoView(OpsArticleRow row) {
        OpsContentAuthorView author = author(row.authorKind(), row.authorOrgId(), row.authorName());
        List<String> tags = row.videoTags() == null || row.videoTags().isBlank()
                ? List.of()
                : Arrays.stream(row.videoTags().trim().split("\\s+")).toList();
        return new OpsVideoView(row.id(), row.title(), author, row.sponsored(), row.cityCode(), row.coverUrl(),
                row.sample(), OpsContentStatus.valueOf(row.status()), row.publishedAt(), row.videoDurationSeconds(),
                OpsVideoOrientation.valueOf(row.videoOrientation()), row.videoUrl(), row.videoCaption(), tags,
                row.videoRank(), refs(row.id()), videoMissing(row.coverUrl(), row.videoUrl(), author), row.version(),
                row.updatedAt());
    }

    private OpsTopicView topicView(OpsTopicRow row) {
        List<UUID> activities = new ArrayList<>();
        List<UUID> venues = new ArrayList<>();
        List<UUID> articles = new ArrayList<>();
        List<OpsTopicMerchantItemView> merchantItems = new ArrayList<>();
        for (TopicItemRow item : reads.findTopicItems(row.id())) {
            switch (item.itemType()) {
                case "ACTIVITY" -> activities.add(item.itemId());
                case "VENUE" -> venues.add(item.itemId());
                case "ARTICLE" -> articles.add(item.itemId());
                case "PRODUCT" -> merchantItems.add(new OpsTopicMerchantItemView(item.itemId(),
                        new OpsContentAuthorView(OpsAuthorKind.MERCHANT, item.merchantOrgId(), item.merchantName())));
                default -> throw new IllegalStateException("unknown topic item type " + item.itemType());
            }
        }
        OpsContentAuthorView publisher = author(row.publisherKind(), row.publisherOrgId(), row.publisherName());
        int count = activities.size() + venues.size() + articles.size() + merchantItems.size();
        return new OpsTopicView(row.id(), row.title(), row.subtitle(), publisher, row.cityCode(),
                row.startsOn().toString(), row.endsOn().toString(), row.coverUrl(), row.sample(),
                OpsContentStatus.valueOf(row.status()), List.copyOf(activities), List.copyOf(venues),
                List.copyOf(articles), List.copyOf(merchantItems), topicMissing(row.coverUrl(), count, publisher),
                row.version(), row.updatedAt());
    }

    private List<OpsRefView> refs(UUID articleId) {
        return reads.findRefs(articleId).stream()
                .map(ref -> new OpsRefView(OpsRefType.valueOf(ref.refType()), ref.refId(), ref.title()))
                .toList();
    }

    private static OpsContentAuthorView author(String kind, UUID organizationId, String name) {
        return new OpsContentAuthorView(OpsAuthorKind.valueOf(kind), organizationId, name);
    }

    // ==== 上线前必填 ====

    private List<String> articleMissing(String coverUrl, List<OpsBlockView> blocks, OpsContentAuthorView author) {
        List<String> missing = new ArrayList<>();
        if (!authorActive(author)) {
            missing.add(MISSING_AUTHOR);
        }
        if (coverUrl == null) {
            missing.add(MISSING_COVER);
        }
        if (blocks.isEmpty()) {
            missing.add(MISSING_BLOCKS);
        }
        if (blocks.stream().anyMatch(block -> block.type() == OpsBlockType.IMAGE && block.image().url() == null)) {
            missing.add(MISSING_IMAGE);
        }
        return List.copyOf(missing);
    }

    private List<String> videoMissing(String coverUrl, String playUrl, OpsContentAuthorView author) {
        List<String> missing = new ArrayList<>();
        if (!authorActive(author)) {
            missing.add(MISSING_AUTHOR);
        }
        if (coverUrl == null) {
            missing.add(MISSING_COVER);
        }
        if (playUrl == null) {
            missing.add(MISSING_PLAY_URL);
        }
        return List.copyOf(missing);
    }

    private List<String> topicMissing(String coverUrl, int itemCount, OpsContentAuthorView publisher) {
        List<String> missing = new ArrayList<>();
        if (!authorActive(publisher)) {
            missing.add(MISSING_PUBLISHER);
        }
        if (coverUrl == null) {
            missing.add(MISSING_COVER);
        }
        if (itemCount == 0) {
            missing.add(MISSING_ITEMS);
        }
        return List.copyOf(missing);
    }

    /** 平台署名不用查；机构要还在用、还是服务商类（录入后被停用了，上线前要换作者）。 */
    private boolean authorActive(OpsContentAuthorView author) {
        if (author.kind() == OpsAuthorKind.PLATFORM) {
            return true;
        }
        return author.organizationId() != null && organizations.find(author.organizationId())
                .filter(found -> PROVIDER_TYPE.equals(found.type()))
                .isPresent();
    }

    private static void requireComplete(List<String> missing) {
        if (!missing.isEmpty()) {
            throw OpsException.incomplete(ContentOpsError.PUBLISH_INCOMPLETE, missing);
        }
    }

    // ==== 上线、下线 ====

    /** 已上线的不能再上线（要改发布时间先下线）。 */
    private static void requireUnpublished(String status) {
        if (OpsContentStatus.valueOf(status) == OpsContentStatus.PUBLISHED) {
            throw new OpsException(ContentOpsError.STATUS_CONFLICT);
        }
    }

    private void publish(OpsContentSubject subject, OpsArticleRow current, int expected, OffsetDateTime publishAt,
                         Operator who) {
        OffsetDateTime at = now();
        OffsetDateTime when = publishAt == null ? at : publishAt.withOffsetSameInstant(ZoneOffset.UTC);
        if (when.isBefore(at.minus(PAST_TOLERANCE)) || when.isAfter(at.plus(MAX_SCHEDULE))) {
            throw new IllegalArgumentException("publish time is invalid");
        }
        if (mapper.updateArticleStatus(current.id(), OpsContentStatus.PUBLISHED.name(), when, expected, at) != 1) {
            throw new OpsException(ContentOpsError.VERSION_CONFLICT);
        }
        String summary = when.isAfter(at)
                ? "上线，定时发布 " + SCHEDULE.format(when.atZoneSameInstant(BEIJING)) + "（北京时间）"
                : "上线，立即发布";
        record(subject, current.id(), OpsContentAction.PUBLISH, summary, who, at);
    }

    private void offline(OpsContentSubject subject, OpsArticleRow current, int expected, Operator who) {
        checkVersion(current.version(), expected);
        if (OpsContentStatus.valueOf(current.status()) != OpsContentStatus.PUBLISHED) {
            throw new OpsException(ContentOpsError.STATUS_CONFLICT);
        }
        OffsetDateTime at = now();
        if (mapper.updateArticleStatus(current.id(), OpsContentStatus.OFFLINE.name(), current.publishedAt(), expected,
                at) != 1) {
            throw new OpsException(ContentOpsError.VERSION_CONFLICT);
        }
        record(subject, current.id(), OpsContentAction.OFFLINE, "下线", who, at);
    }

    // ==== 校验录入 ====

    record ValidRef(OpsRefType type, UUID refId, String title) {
    }

    record ValidArticle(OpsArticleKind kind, String title, OpsContentAuthorView author, String cityCode,
                        String coverUrl, boolean sample, List<OpsBlockView> blocks, List<ValidRef> refs) {

        ArticleWrite write(UUID id) {
            return new ArticleWrite(id, kind.name(), title, author.kind().name(), author.organizationId(),
                    author.name(), author.kind() == OpsAuthorKind.MERCHANT, cityCode, coverUrl, sample, null, null,
                    null, null, null, 0);
        }
    }

    record ValidVideo(String title, OpsContentAuthorView author, String cityCode, String coverUrl, boolean sample,
                      int durationSeconds, OpsVideoOrientation orientation, String playUrl, String caption,
                      String tags, int rank, List<ValidRef> refs) {

        ArticleWrite write(UUID id) {
            return new ArticleWrite(id, "VIDEO", title, author.kind().name(), author.organizationId(), author.name(),
                    author.kind() == OpsAuthorKind.MERCHANT, cityCode, coverUrl, sample, durationSeconds,
                    orientation.name(), playUrl, caption, tags, rank);
        }
    }

    record ValidMerchantItem(UUID productId, OpsContentAuthorView merchant) {
    }

    record ValidTopic(String title, String subtitle, OpsContentAuthorView publisher, String cityCode,
                      LocalDate startsOn, LocalDate endsOn, String coverUrl, boolean sample, List<UUID> activityIds,
                      List<UUID> venueIds, List<UUID> articleIds, List<ValidMerchantItem> merchantItems) {

        TopicWrite write(UUID id) {
            return new TopicWrite(id, title, subtitle, publisher.kind().name(), publisher.organizationId(),
                    publisher.name(), cityCode, startsOn, endsOn, coverUrl, sample);
        }

        int itemCount() {
            return activityIds.size() + venueIds.size() + articleIds.size() + merchantItems.size();
        }
    }

    private ValidArticle validArticle(ArticleDraft draft, OpsArticleRow current) {
        if (draft == null) {
            throw new IllegalArgumentException("article is required");
        }
        OpsArticleKind kind = OpsText.enumValue(OpsArticleKind.class, draft.kind(), "kind");
        String title = OpsText.required(draft.title(), MAX_TITLE, "title");
        OpsContentAuthorView author = author(draft.author(), kind == OpsArticleKind.CASE);
        // 案例要有城市（城市里的服务商做的事）；知识可以不分城市。
        String city = kind == OpsArticleKind.CASE ? requiredCity(draft.cityCode()) : optionalCity(draft.cityCode());
        String cover = OpsText.mediaUrl(draft.coverUrl(), current == null ? null : current.coverUrl(), "coverUrl");
        List<String> existingImages = current == null ? List.of() : reads.findBlocks(current.id()).stream()
                .map(BlockRow::imageUrl).filter(java.util.Objects::nonNull).toList();
        List<OpsBlockView> blocks = blocks(draft.blocks(), existingImages);
        return new ValidArticle(kind, title, author, city, cover, Boolean.TRUE.equals(draft.sample()), blocks,
                refs(draft.refs()));
    }

    private ValidVideo validVideo(VideoDraft draft, OpsArticleRow current) {
        if (draft == null) {
            throw new IllegalArgumentException("video is required");
        }
        String title = OpsText.required(draft.title(), MAX_TITLE, "title");
        OpsContentAuthorView author = author(draft.author(), false);
        String city = requiredCity(draft.cityCode());
        String cover = OpsText.mediaUrl(draft.coverUrl(), current == null ? null : current.coverUrl(), "coverUrl");
        Integer seconds = draft.durationSeconds();
        if (seconds == null || seconds < 1 || seconds > MAX_VIDEO_SECONDS) {
            throw new IllegalArgumentException("duration is invalid");
        }
        OpsVideoOrientation orientation = OpsText.enumValue(OpsVideoOrientation.class, draft.orientation(),
                "orientation");
        String playUrl = OpsText.mediaUrl(draft.playUrl(), current == null ? null : current.videoUrl(), "playUrl");
        String caption = OpsText.optional(draft.caption(), MAX_VIDEO_CAPTION, "caption");
        int rank = draft.rank() == null ? 0 : draft.rank();
        if (rank < 0 || rank > MAX_RANK) {
            throw new IllegalArgumentException("rank is invalid");
        }
        return new ValidVideo(title, author, city, cover, Boolean.TRUE.equals(draft.sample()), seconds, orientation,
                playUrl, caption, tags(draft.tags()), rank, refs(draft.refs()));
    }

    private ValidTopic validTopic(TopicDraft draft, OpsTopicRow current) {
        if (draft == null) {
            throw new IllegalArgumentException("topic is required");
        }
        String title = OpsText.required(draft.title(), MAX_TOPIC_TITLE, "title");
        String subtitle = OpsText.optional(draft.subtitle(), MAX_SUBTITLE, "subtitle");
        OpsContentAuthorView publisher = author(draft.publisher(), false);
        String city = requiredCity(draft.cityCode());
        LocalDate starts = date(draft.startsOn(), "startsOn");
        LocalDate ends = date(draft.endsOn(), "endsOn");
        if (ends.isBefore(starts)) {
            throw new IllegalArgumentException("topic dates are invalid");
        }
        String cover = OpsText.mediaUrl(draft.coverUrl(), current == null ? null : current.coverUrl(), "coverUrl");
        List<UUID> activities = ids(draft.activityIds(), MAX_TOPIC_ACTIVITIES, "activityIds");
        List<UUID> venues = ids(draft.venueIds(), MAX_TOPIC_VENUES, "venueIds");
        List<UUID> articles = ids(draft.articleIds(), MAX_TOPIC_ARTICLES, "articleIds");
        for (UUID article : articles) {
            if (mapper.countPlainArticle(article) != 1) {
                throw new OpsException(ContentOpsError.ARTICLE_UNKNOWN);
            }
        }
        List<ValidMerchantItem> merchantItems = merchantItems(draft.merchantItems(), city);
        return new ValidTopic(title, subtitle, publisher, city, starts, ends, cover,
                Boolean.TRUE.equals(draft.sample()), activities, venues, articles, merchantItems);
    }

    /**
     * 作者：平台写署名（不带机构）；服务商、商家带机构 ID，必须是在用的服务商类机构，名称取机构名称的快照。
     * caseArticle 为 true 时不能是平台（案例由服务商、商家发布）。
     */
    private OpsContentAuthorView author(AuthorDraft draft, boolean caseArticle) {
        if (draft == null) {
            throw new IllegalArgumentException("author is required");
        }
        OpsAuthorKind kind = OpsText.enumValue(OpsAuthorKind.class, draft.kind(), "authorKind");
        if (kind == OpsAuthorKind.PLATFORM) {
            if (draft.organizationId() != null) {
                throw new IllegalArgumentException("platform author has no organization");
            }
            if (caseArticle) {
                throw new OpsException(ContentOpsError.AUTHOR_INVALID);
            }
            return new OpsContentAuthorView(kind, null, OpsText.required(draft.name(), MAX_AUTHOR_NAME, "authorName"));
        }
        return new OpsContentAuthorView(kind, draft.organizationId(), organizationName(draft.organizationId()));
    }

    private String organizationName(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organization is required");
        }
        return organizations.find(organizationId)
                .filter(found -> PROVIDER_TYPE.equals(found.type()))
                .map(found -> OpsText.clip(found.name().strip(), MAX_AUTHOR_NAME))
                .orElseThrow(() -> new OpsException(ContentOpsError.AUTHOR_INVALID));
    }

    private List<OpsBlockView> blocks(List<BlockDraft> drafts, List<String> existingImages) {
        List<BlockDraft> list = drafts == null ? List.of() : drafts;
        if (list.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("too many blocks");
        }
        List<OpsBlockView> blocks = new ArrayList<>();
        for (BlockDraft draft : list) {
            if (draft == null) {
                throw new IllegalArgumentException("block is required");
            }
            OpsBlockType type = OpsText.enumValue(OpsBlockType.class, draft.type(), "blockType");
            List<BlockItemDraft> items = draft.items() == null ? List.of() : draft.items();
            boolean hasImageFields = !blank(draft.imageUrl()) || !blank(draft.imageAlt())
                    || !blank(draft.imageCaption());
            if (type.hasText()) {
                if (!items.isEmpty() || hasImageFields) {
                    throw new IllegalArgumentException("text block has no items or picture");
                }
                String text = type == OpsBlockType.HEADING
                        ? OpsText.required(draft.text(), MAX_HEADING, "heading")
                        : required(OpsText.multiline(draft.text(), MAX_BLOCK_TEXT, "text"), "text");
                blocks.add(new OpsBlockView(type, text, List.of(), null));
            } else if (type.hasItems()) {
                if (!blank(draft.text()) || hasImageFields || items.isEmpty() || items.size() > MAX_BLOCK_ITEMS) {
                    throw new IllegalArgumentException("item block needs 1-20 items and no text");
                }
                List<OpsBlockItemView> views = new ArrayList<>();
                for (BlockItemDraft item : items) {
                    if (item == null) {
                        throw new IllegalArgumentException("item is required");
                    }
                    views.add(new OpsBlockItemView(OpsText.required(item.title(), MAX_ITEM_TITLE, "itemTitle"),
                            OpsText.optional(item.text(), MAX_ITEM_TEXT, "itemText")));
                }
                blocks.add(new OpsBlockView(type, null, List.copyOf(views), null));
            } else {
                // IMAGE：替代文字必填；地址可以先空着（上线前必须有）；图注选填。
                if (!blank(draft.text()) || !items.isEmpty()) {
                    throw new IllegalArgumentException("image block has no text or items");
                }
                blocks.add(new OpsBlockView(type, null, List.of(), new OpsBlockImageView(
                        OpsText.mediaUrl(draft.imageUrl(), draft.imageUrl() != null && existingImages.contains(draft.imageUrl()) ? draft.imageUrl() : null, "imageUrl"),
                        OpsText.required(draft.imageAlt(), MAX_IMAGE_ALT, "imageAlt"),
                        OpsText.optional(draft.imageCaption(), MAX_IMAGE_CAPTION, "imageCaption"))));
            }
        }
        return List.copyOf(blocks);
    }

    private static List<ValidRef> refs(List<RefDraft> drafts) {
        List<RefDraft> list = drafts == null ? List.of() : drafts;
        if (list.size() > MAX_REFS) {
            throw new IllegalArgumentException("too many refs");
        }
        Set<String> seen = new HashSet<>();
        List<ValidRef> refs = new ArrayList<>();
        for (RefDraft draft : list) {
            if (draft == null || draft.refId() == null) {
                throw new IllegalArgumentException("ref is invalid");
            }
            OpsRefType type = OpsText.enumValue(OpsRefType.class, draft.type(), "refType");
            if (!seen.add(type + ":" + draft.refId())) {
                throw new IllegalArgumentException("ref is repeated");
            }
            refs.add(new ValidRef(type, draft.refId(), OpsText.required(draft.title(), MAX_REF_TITLE, "refTitle")));
        }
        return List.copyOf(refs);
    }

    /** 话题：最多 5 个，每个 1–20 字，不带空白（前面的 # 去掉），存成空格分隔。 */
    private static String tags(List<String> tags) {
        List<String> list = tags == null ? List.of() : tags;
        if (list.size() > MAX_TAGS) {
            throw new IllegalArgumentException("too many tags");
        }
        Set<String> seen = new HashSet<>();
        List<String> cleaned = new ArrayList<>();
        for (String tag : list) {
            String text = tag == null ? "" : tag.strip().replaceFirst("^#+", "");
            if (text.isEmpty() || OpsText.length(text) > MAX_TAG || text.contains("#")
                    || text.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                throw new IllegalArgumentException("tag is invalid");
            }
            if (seen.add(text)) {
                cleaned.add(text);
            }
        }
        return cleaned.isEmpty() ? null : String.join(" ", cleaned);
    }

    private List<ValidMerchantItem> merchantItems(List<MerchantItemDraft> drafts, String city) {
        List<MerchantItemDraft> list = drafts == null ? List.of() : drafts;
        if (list.size() > MAX_TOPIC_MERCHANT_ITEMS) {
            throw new IllegalArgumentException("too many merchant items");
        }
        Set<UUID> seen = new HashSet<>();
        List<ValidMerchantItem> items = new ArrayList<>();
        for (MerchantItemDraft draft : list) {
            if (draft == null || draft.productId() == null || draft.merchantOrganizationId() == null) {
                throw new IllegalArgumentException("merchant item is invalid");
            }
            if (!seen.add(draft.productId())) {
                throw new IllegalArgumentException("merchant item is repeated");
            }
            String merchant = organizationName(draft.merchantOrganizationId());
            if (!products.soldIn(draft.productId(), city)) {
                throw new OpsException(ContentOpsError.PRODUCT_UNKNOWN);
            }
            items.add(new ValidMerchantItem(draft.productId(),
                    new OpsContentAuthorView(OpsAuthorKind.MERCHANT, draft.merchantOrganizationId(), merchant)));
        }
        return List.copyOf(items);
    }

    private static List<UUID> ids(List<UUID> ids, int max, String field) {
        List<UUID> list = ids == null ? List.of() : ids;
        if (list.size() > max || list.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(list).size() != list.size()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return List.copyOf(list);
    }

    private String requiredCity(String cityCode) {
        String city = optionalCity(cityCode);
        if (city == null) {
            throw new IllegalArgumentException("city is required");
        }
        return city;
    }

    private String optionalCity(String cityCode) {
        if (cityCode == null || cityCode.isBlank()) {
            return null;
        }
        String code = cityCode.strip();
        if (cities.cityName(code).isEmpty()) {
            throw new OpsException(ContentOpsError.CITY_UNKNOWN);
        }
        return code;
    }

    /** 列表筛城市：只看格式（6 位数字），不查目录。 */
    private static String optionalCityFilter(String cityCode) {
        if (cityCode == null || cityCode.isBlank()) {
            return null;
        }
        String code = cityCode.strip();
        if (!code.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return code;
    }

    private static String statusFilter(String status) {
        OpsContentStatus parsed = OpsText.optionalEnum(OpsContentStatus.class, status, "status");
        return parsed == null ? null : parsed.name();
    }

    private static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(field + " is invalid", invalid);
        }
    }

    // ==== 写正文、提到的对象、专题条目 ====

    private void writeBlocks(UUID articleId, List<OpsBlockView> blocks) {
        int order = 10;
        for (OpsBlockView block : blocks) {
            UUID blockId = UUID.randomUUID();
            OpsBlockImageView image = block.image();
            mapper.insertBlock(blockId, articleId, order, block.type().name(), block.text(),
                    image == null ? null : image.url(), image == null ? null : image.alt(),
                    image == null ? null : image.caption());
            int itemOrder = 10;
            for (OpsBlockItemView item : block.items()) {
                mapper.insertBlockItem(blockId, itemOrder, item.title(), item.text());
                itemOrder += 10;
            }
            order += 10;
        }
    }

    private void writeRefs(UUID articleId, List<ValidRef> refs) {
        int order = 10;
        for (ValidRef ref : refs) {
            mapper.insertRef(articleId, order, ref.type().name(), ref.refId(), ref.title());
            order += 10;
        }
    }

    private void writeTopicItems(UUID topicId, ValidTopic topic) {
        int order = 10;
        for (UUID id : topic.activityIds()) {
            mapper.insertTopicItem(topicId, order, "ACTIVITY", id, null, null);
            order += 10;
        }
        for (UUID id : topic.venueIds()) {
            mapper.insertTopicItem(topicId, order, "VENUE", id, null, null);
            order += 10;
        }
        for (UUID id : topic.articleIds()) {
            mapper.insertTopicItem(topicId, order, "ARTICLE", id, null, null);
            order += 10;
        }
        for (ValidMerchantItem item : topic.merchantItems()) {
            mapper.insertTopicItem(topicId, order, "PRODUCT", item.productId(), item.merchant().organizationId(),
                    item.merchant().name());
            order += 10;
        }
    }

    // ==== 改动记录 ====

    private void record(OpsContentSubject subject, UUID id, OpsContentAction action, String summary, Operator who,
                        OffsetDateTime at) {
        if (mapper.insertChange(UUID.randomUUID(), subject.name(), id, action.name(), OpsText.clip(summary, 200),
                who.accountId(), who.name(), at) != 1) {
            throw new IllegalStateException("content change was not recorded");
        }
    }

    private List<OpsChangeView> changes(OpsContentSubject subject, UUID id) {
        return mapper.findChanges(subject.name(), id, CHANGE_LIMIT).stream()
                .map(row -> new OpsChangeView(row.id(), row.subjectType(), row.subjectId(), row.action(),
                        row.summary(), row.operatorName(), row.changedAt()))
                .toList();
    }

    // ==== 小工具 ====

    private static OpsArticleRow requireArticle(OpsArticleRow row, boolean video) {
        if (row == null || "VIDEO".equals(row.kind()) != video) {
            throw new OpsException(ContentOpsError.NOT_FOUND);
        }
        return row;
    }

    private static OpsTopicRow requireTopic(OpsTopicRow row) {
        if (row == null) {
            throw new OpsException(ContentOpsError.NOT_FOUND);
        }
        return row;
    }

    private static void checkVersion(int current, int expected) {
        if (current != expected) {
            throw new OpsException(ContentOpsError.VERSION_CONFLICT);
        }
    }

    private static int version(Integer version) {
        if (version == null || version < 0) {
            throw new IllegalArgumentException("version is required");
        }
        return version;
    }

    private static Operator operator(Operator operator) {
        if (operator == null || operator.accountId() == null) {
            throw new IllegalArgumentException("operator is required");
        }
        return new Operator(operator.accountId(), OpsText.operatorName(operator.name()));
    }

    private static UUID required(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return id;
    }

    private static String required(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
